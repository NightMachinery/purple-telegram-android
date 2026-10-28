#pragma once
#include "purple/purple_sync_auto.h"
#include "retained_config_bases.h"
#include "purple/purple_sync_json.h"
#include <QtCore/QJsonDocument>
#include <limits>

namespace PurpleAndroid {
inline QByteArray AutoBytes(const QJsonValue &value, bool &ok) {
    if (!value.isString()) {
        ok = false;
        return {};
    }
    const auto text = value.toString().toLatin1();
    const auto decoded = QByteArray::fromBase64Encoding(text, QByteArray::AbortOnBase64DecodingErrors);
    if (!decoded || decoded.decoded.toBase64() != text) {
        ok = false;
        return {};
    }
    return decoded.decoded;
}
inline uint64_t AutoCounter(const QJsonValue &value, bool &ok) {
    auto good = false;
    const auto text = value.toString();
    const auto n = text.toULongLong(&good);
    ok = ok && value.isString() && good && QString::number(n) == text;
    return n;
}
inline QString AutoEncoded(const QByteArray &bytes) { return QString::fromLatin1(bytes.toBase64()); }
inline QJsonObject AutoRecord(const QByteArray &bytes, const QString &key, bool missing) {
    const auto account = Purple::ParseAccountKey(key);
    if (!account)
        return {{"status", 2}};
    auto record = Purple::SyncAutoRecord();
    record.account = *account;
    if (!missing) {
        const auto parsed = Purple::ParseSyncAutoRecord(bytes);
        if (!parsed.record || parsed.record->account != *account)
            return {{"status", int(parsed.status == Purple::SyncAutoCodecStatus::NewerVersion ? 1 : 2)}};
        record = *parsed.record;
    }
    const auto canonical = Purple::SerializeSyncAutoRecord(record);
    return canonical ? QJsonObject{{"status", 0}, {"record", AutoEncoded(*canonical)}} : QJsonObject{{"status", 2}};
}
} // namespace PurpleAndroid

extern "C" JNIEXPORT jstring JNICALL Java_org_telegram_messenger_purple_PurpleSyncAutoCore_codecNative(JNIEnv *env,
                                                                                                       jclass,
                                                                                                       jbyteArray bytes,
                                                                                                       jstring key) {
    auto account = QString();
    auto data = QByteArray();
    auto error = QString();
    if (!ReadString(env, key, account) || !ReadOptionalBytes(env, bytes, data, error))
        return nullptr;
    const auto result = PurpleAndroid::AutoRecord(data, account, !bytes);
    const auto text = QString::fromUtf8(QJsonDocument(result).toJson(QJsonDocument::Compact));
    return env->NewString(reinterpret_cast<const jchar *>(text.utf16()), text.size());
}

extern "C" JNIEXPORT jstring JNICALL Java_org_telegram_messenger_purple_PurpleSyncAutoCore_planNative(
    JNIEnv *env, jclass, jstring request, jlong user, jboolean complete, jintArray ids, jintArray transport,
    jlongArray documents, jlongArray edits, jobjectArray records, jboolean global, jint mode, jbyteArray globalFile,
    jobjectArray accounts, jobjectArray files, jintArray modes) {
    auto text = QString();
    auto error = QString();
    if (!ReadString(env, request, text) || text.size() > 8 * 1024 * 1024)
        return nullptr;
    const auto doc = QJsonDocument::fromJson(text.toUtf8());
    if (!doc.isObject())
        return nullptr;
    const auto json = doc.object();
    auto ok = true;
    const auto parsed = Purple::ParseSyncAutoRecord(PurpleAndroid::AutoBytes(json.value("record"), ok));
    const auto f = json.value("facts").toObject();
    const auto account = Purple::ParseAccountKey(f.value("account").toString());
    const auto fileAccount = Purple::ParseAccountKey(f.value("fileAccount").toString());
    if (!ok || !parsed.record || !account || !fileAccount)
        return nullptr;
    auto input = Purple::SyncAutoInput();
    input.record = *parsed.record;
    input.sequence = PurpleAndroid::AutoCounter(json.value("sequence"), ok);
    const auto now = PurpleAndroid::AutoCounter(json.value("now"), ok);
    if (now > uint64_t(std::numeric_limits<int64_t>::max()))
        return nullptr;
    input.now = int64_t(now);
    input.completedTicket = PurpleAndroid::AutoCounter(json.value("completedTicket"), ok);
    const auto event = json.value("event").toInt(-1), outcome = json.value("outcome").toInt(-1);
    if (event < 0 || event > int(Purple::SyncAutoEvent::Completed) || outcome < 0 ||
        outcome > int(Purple::SyncAutoOutcome::DeletionRefused))
        return nullptr;
    input.event = Purple::SyncAutoEvent(event);
    input.outcome = Purple::SyncAutoOutcome(outcome);
    auto &facts = input.facts;
    facts.account = *account;
    facts.fileAccount = *fileAccount;
    const auto boolean = [&](const char *name) {
        const auto v = f.value(name);
        ok = ok && v.isBool();
        return v.toBool();
    };
    facts.signedIn = boolean("signedIn");
    facts.ownerAnswered = boolean("ownerAnswered");
    facts.accountLock = boolean("accountLock");
    facts.chatResolved = boolean("chatResolved");
    facts.chatWritable = boolean("chatWritable");
    facts.saveDebounceFinished = boolean("saveDebounceFinished");
    facts.editSupported = boolean("editSupported");
    facts.session = f.value("session").toString();
    facts.inFlightSession = f.value("inFlightSession").toString();
    facts.currentDevice = f.value("device").toString();
    facts.bindingToken = f.value("bindingToken").toString().toLatin1();
    facts.savedFingerprint = f.value("savedFingerprint").toString();
    facts.stagedRecord = PurpleAndroid::AutoBytes(f.value("staged"), ok);
    const auto state = PurpleAndroid::AutoBytes(f.value("state"), ok);
    if (!state.isEmpty()) {
        const auto s = Purple::ParseSyncLocalState(state);
        if (!s)
            return nullptr;
        facts.state = s.state;
    }
    facts.chat = Purple::PeerToken();
    const auto localStatus = f.value("localStatus").toInt(-1);
    if (localStatus < 0 || localStatus > 2)
        return nullptr;
    facts.local = Purple::MakeSyncSettingsFile(Purple::SyncSettingsFileStatus(localStatus),
                                               PurpleAndroid::AutoBytes(f.value("local"), ok), boolean("lastGood"));
    facts.queue =
        boolean("sendQueued") ? Purple::SyncConfigSendQueue::HoldsSyncRecord : Purple::SyncConfigSendQueue::Empty;
    facts.writer = {u"Android"_q, u"Purple Telegram Android"_q};
    const auto impact = PurpleAndroid::ReadImpactContext(env, global, mode, globalFile, accounts, files, modes);
    facts.impactContextComplete = boolean("impactComplete") && bool(impact);
    if (impact) {
        facts.impactContext = *impact;
        facts.proposedWorkMode = impact->workMode;
    }
    if (json.value("inventory").isObject()) {
        const auto data = json.value("inventory").toObject();
        const auto inventory = ReadInventory(env, user, complete, ids, transport, documents, edits, records, error);
        if (!inventory || user != account->userId)
            return nullptr;
        auto snapshot = Purple::SyncAutoInventory();
        snapshot.account = *account;
        snapshot.chat = facts.chat;
        snapshot.session = data.value("session").toString();
        snapshot.ticket = PurpleAndroid::AutoCounter(data.value("ticket"), ok);
        const auto time = PurpleAndroid::AutoCounter(data.value("readAt"), ok);
        if (time > uint64_t(std::numeric_limits<int64_t>::max()))
            return nullptr;
        snapshot.readAt = int64_t(time);
        snapshot.fresh = data.value("fresh").toBool();
        snapshot.inventory = *inventory;
        const auto reads = data.value("reads");
        if (!reads.isArray() || reads.toArray().size() > 10000)
            return nullptr;
        for (const auto value : reads.toArray()) {
            const auto row = value.toObject();
            const auto presence = row.value("presence").toInt(-1);
            if (presence < 0 || presence > 2 || !row.value("fresh").isBool() || !row.value("owned").isBool())
                return nullptr;
            snapshot.reads.push_back({row.value("id").toInt(), PurpleAndroid::AutoBytes(row.value("record"), ok),
                                      row.value("fresh").toBool(), row.value("owned").toBool(),
                                      Purple::SyncAutoMessagePresence(presence)});
        }
        input.inventory = std::move(snapshot);
    }
    if (!ok || env->ExceptionCheck())
        return nullptr;
    auto plan = Purple::PlanSyncAuto(input);
    if (plan.nextState && facts.state) {
        auto candidates = std::vector<QByteArray>{facts.stagedRecord, plan.record.record};
        if (input.inventory) for (const auto &read : input.inventory->reads) candidates.push_back(read.record);
        const auto retained = PurpleAndroid::PrepareRetainedConfig(*facts.state, *plan.nextState, candidates);
        if (retained != u"Ready"_q) {
            plan.action = Purple::SyncAutoAction::Hold;
            plan.reason = Purple::SyncAutoReason::NeedsReview;
            plan.record.active = false;
            plan.record.pending = Purple::SyncAutoAction::NoOp;
            plan.record.held = plan.reason;
            plan.nextState.reset();
        } else {
            plan.record.expectedState = Purple::SerializeSyncLocalState(*plan.nextState).canonical;
        }
    }
    const auto encoded = Purple::SerializeSyncAutoRecord(plan.record);
    if (!encoded)
        return nullptr;
    const auto next = plan.nextState ? Purple::SerializeSyncLocalState(*plan.nextState).canonical : QByteArray();
    auto previous = QByteArray();
    if (input.inventory && plan.action == Purple::SyncAutoAction::EditInPlace)
        for (const auto &read : input.inventory->reads)
            if (read.messageId == plan.messageId)
                previous = read.record;
    const auto result = QJsonObject{{"previous", PurpleAndroid::AutoEncoded(previous)},
                                    {"action", int(plan.action)},
                                    {"reason", int(plan.reason)},
                                    {"notice", plan.notice},
                                    {"record", PurpleAndroid::AutoEncoded(*encoded)},
                                    {"ticket", QString::number(plan.ticket)},
                                    {"retryAt", QString::number(plan.retryAt)},
                                    {"messageId", plan.messageId},
                                    {"bytes", PurpleAndroid::AutoEncoded(plan.bytes)},
                                    {"nextState", PurpleAndroid::AutoEncoded(next)},
                                    {"commitOnly", plan.commitOnly}};
    const auto out = QString::fromUtf8(QJsonDocument(result).toJson(QJsonDocument::Compact));
    return env->NewString(reinterpret_cast<const jchar *>(out.utf16()), out.size());
}
