#include "retained_config_bases.h"
#include "purple/purple_config_payload.h"
#include "purple/purple_sync_envelope.h"
#include "purple/purple_types.h"
#include <QtCore/QCryptographicHash>
#include <QtCore/QJsonDocument>
#include <set>

namespace PurpleAndroid {
namespace {

bool ValidRecord(const Purple::SyncLocalState &state, const QByteArray &record) {
    const auto parsed = Purple::ParseSyncEnvelope(record);
    const auto encoded = parsed ? Purple::SerializeSyncEnvelope(parsed.envelope) : Purple::SyncEnvelopeWriteResult();
    return encoded && encoded.canonical == record
        && parsed.envelope.document.value(u"space"_q).toString() == state.space
        && Purple::InspectConfigPayload(parsed).status == Purple::ConfigPayloadStatus::Valid;
}

}

QString RecordHash(const QByteArray &bytes) {
    return QString::fromLatin1(QCryptographicHash::hash(bytes, QCryptographicHash::Sha256).toHex());
}

bool MatchesConfigBase(const Purple::SyncLocalState &state, const QByteArray &record) {
    const auto payload = Purple::InspectConfigPayload(Purple::ParseSyncEnvelope(record));
    return payload.status == Purple::ConfigPayloadStatus::Valid
        && payload.version.key == state.configData.base
        && payload.version.lineage == state.configData.baseLineage && ValidRecord(state, record);
}

RetainedConfig ReadRetainedConfig(const Purple::SyncLocalState &state) {
    auto result = RetainedConfig();
    const auto value = state.preserved.value(u"retained_config_bases"_q);
    if (value.isUndefined()) return result;
    const auto fail = [&](const QString &status) { result.status = status; return result; };
    if (!value.isObject()) return fail(u"InvalidRetention"_q);
    result.object = value.toObject();
    const auto version = result.object.value(u"version"_q);
    if (version.isDouble() && version.toDouble() > 1) return fail(u"NewerRetention"_q);
    if (!version.isDouble() || version.toDouble() != 1 || !result.object.value(u"records"_q).isArray()) return fail(u"InvalidRetention"_q);
    result.entries = result.object.value(u"records"_q).toArray();
    if (result.entries.size() > 16) return fail(u"RetentionFull"_q);
    auto hashes = std::set<QString>();
    for (const auto &entry : result.entries) {
        const auto object = entry.toObject();
        const auto record = object.value(u"record"_q), hash = object.value(u"sha256"_q);
        if (!entry.isObject() || !record.isString() || !hash.isString()) return fail(u"InvalidRetention"_q);
        const auto bytes = record.toString().toUtf8();
        if (bytes.size() > 262144) return fail(u"BaseRecordTooLarge"_q);
        result.total += bytes.size();
        if (result.total > 2097152) return fail(u"RetentionFull"_q);
        if (hash.toString() != RecordHash(bytes) || !hashes.insert(hash.toString()).second || !ValidRecord(state, bytes)) return fail(u"InvalidRetention"_q);
    }
    return result;
}

QString RetainConfig(Purple::SyncLocalState &state, const QByteArray &record) {
    auto retained = ReadRetainedConfig(state);
    if (retained.status != u"Ready"_q) return retained.status;
    if (record.size() > 262144) return u"BaseRecordTooLarge"_q;
    if (!ValidRecord(state, record)) return u"InvalidRecord"_q;
    const auto hash = RecordHash(record);
    for (const auto &entry : retained.entries) if (entry.toObject().value(u"sha256"_q).toString() == hash)
        return entry.toObject().value(u"record"_q).toString().toUtf8() == record ? u"Ready"_q : u"InvalidRetention"_q;
    if (retained.entries.size() == 16 || retained.total + record.size() > 2097152) return u"RetentionFull"_q;
    retained.entries.append(QJsonObject{{u"sha256"_q, hash}, {u"record"_q, QString::fromUtf8(record)}});
    retained.object.insert(u"version"_q, 1);
    retained.object.insert(u"records"_q, retained.entries);
    auto next = state;
    next.preserved.insert(u"retained_config_bases"_q, retained.object);
    if (!Purple::SerializeSyncLocalState(next)) return u"RetentionFull"_q;
    state = std::move(next);
    return u"Ready"_q;
}

QByteArray RetainedConfigBase(const Purple::SyncLocalState &state) {
    const auto retained = ReadRetainedConfig(state);
    if (retained.status != u"Ready"_q) return {};
    for (const auto &entry : retained.entries) {
        const auto bytes = entry.toObject().value(u"record"_q).toString().toUtf8();
        if (MatchesConfigBase(state, bytes)) return bytes;
    }
    return {};
}

QString PrepareRetainedConfig(const Purple::SyncLocalState &before,
        Purple::SyncLocalState &next, const std::vector<QByteArray> &candidates) {
    const auto old = ReadRetainedConfig(before), proposed = ReadRetainedConfig(next);
    if (old.status != u"Ready"_q) return old.status;
    if (proposed.status != u"Ready"_q) return proposed.status;
    if (!old.object.isEmpty() && proposed.object.isEmpty()) return u"InvalidRetention"_q;
    auto merged = proposed.object;
    for (auto i = old.object.begin(); i != old.object.end(); ++i) {
        if (i.key() == u"version"_q || i.key() == u"records"_q) continue;
        if (merged.contains(i.key()) && merged.value(i.key()) != i.value()) return u"InvalidRetention"_q;
        merged.insert(i.key(), i.value());
    }
    if (!old.object.isEmpty()) next.preserved.insert(u"retained_config_bases"_q, merged);
    for (const auto &entry : old.entries) {
        bool found = false;
        for (const auto &candidate : proposed.entries) if (candidate == entry) found = true;
        if (!found) return u"InvalidRetention"_q;
    }
    for (const auto &record : candidates) {
        if (MatchesConfigBase(before, record) || MatchesConfigBase(next, record)) {
            const auto status = RetainConfig(next, record);
            if (status != u"Ready"_q) return status;
        }
    }
    auto previous = next;
    previous.configData = before.configData;
    if ((!before.configData.base.isEmpty() && RetainedConfigBase(previous).isEmpty())
        || (!next.configData.base.isEmpty() && RetainedConfigBase(next).isEmpty())) return u"MissingLocalVersion"_q;
    return u"Ready"_q;
}

QString CompactRetainedConfig(Purple::SyncLocalState &state) {
    const auto retained = ReadRetainedConfig(state);
    if (retained.status != u"Ready"_q) return retained.status;
    if (retained.entries.isEmpty()) return u"Ready"_q;
    auto kept = QJsonArray();
    for (const auto &entry : retained.entries) {
        const auto object = entry.toObject();
        if (object.size() != 2 || MatchesConfigBase(state, object.value(u"record"_q).toString().toUtf8())) kept.append(entry);
    }
    auto object = retained.object;
    object.insert(u"records"_q, kept);
    auto next = state;
    next.preserved.insert(u"retained_config_bases"_q, object);
    if (!Purple::SerializeSyncLocalState(next)) return u"RetentionFull"_q;
    state = std::move(next);
    return u"Ready"_q;
}

}
