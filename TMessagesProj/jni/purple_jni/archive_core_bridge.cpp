#include <jni.h>
#include "purple/purple_archive_lifecycle.h"
#include "purple/purple_archive_staging.h"
#include "purple/purple_archive_views.h"
#include "purple/purple_archive_source.h"
#include "purple/purple_archive_sql_maintenance.h"
#include <map>
#include <mutex>
#include "purple/purple_sync_json.h"
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonArray>
#include <QtCore/QCryptographicHash>
#include <stdexcept>
#include <type_traits>
#include <limits>

namespace {
using namespace Purple;

QString Text(const QJsonObject &object, const QString &key) {
    auto value = object.value(key);
    if (!value.isString()) throw std::runtime_error("archive string");
    return value.toString();
}
int64 Signed(const QJsonValue &value) {
    if (!value.isString()) throw std::runtime_error("archive int64");
    auto text = value.toString(); auto ok = false; auto result = text.toLongLong(&ok);
    if (!ok || QString::number(result) != text) throw std::runtime_error("archive int64");
    return result;
}
uint64_t Unsigned(const QJsonValue &value) {
    if (!value.isString()) throw std::runtime_error("archive uint64");
    auto text = value.toString(); auto ok = false; auto result = text.toULongLong(&ok);
    if (!ok || QString::number(result) != text) throw std::runtime_error("archive uint64");
    return result;
}
QByteArray Bytes(const QJsonValue &value) {
    if (!value.isString()) throw std::runtime_error("archive bytes");
    auto text = value.toString(); auto latin = text.toLatin1();
    auto decoded = QByteArray::fromBase64Encoding(latin, QByteArray::AbortOnBase64DecodingErrors);
    if (QString::fromLatin1(latin) != text || !decoded || decoded.decoded.toBase64() != latin)
        throw std::runtime_error("archive bytes");
    return decoded.decoded;
}
QString Encoded(const QByteArray &value) { return QString::fromLatin1(value.toBase64()); }
QByteArray Array(JNIEnv *env, jbyteArray value, int limit = 64 * 1024 * 1024) {
    if (!value) throw std::runtime_error("archive JNI bytes");
    auto length = env->GetArrayLength(value);
    if (length < 0 || length > limit) throw std::runtime_error("archive JNI limit");
    auto bytes = QByteArray(length, Qt::Uninitialized);
    if (length) env->GetByteArrayRegion(value, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("archive JNI copy");
    return bytes;
}
jbyteArray JavaBytes(JNIEnv *env, const QByteArray &value) {
    auto result = env->NewByteArray(value.size());
    if (result && !value.isEmpty()) env->SetByteArrayRegion(result, 0, value.size(), reinterpret_cast<const jbyte*>(value.constData()));
    return result;
}
jstring JavaText(JNIEnv *env, const QString &value) { return env->NewString(reinterpret_cast<const jchar*>(value.utf16()), value.size()); }
QString String(JNIEnv *env, jstring value) {
    if (!value) throw std::runtime_error("archive JNI string");
    auto chars = env->GetStringChars(value, nullptr);
    if (!chars) throw std::runtime_error("archive JNI string");
    auto result = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars); return result;
}
struct Crypto {
    JNIEnv *env;
    jobject object;
    jmethodID random = nullptr, seal = nullptr, open = nullptr, hmac = nullptr;
    Crypto(JNIEnv *env, jobject object) : env(env), object(object) {
        if (!object) return;
        auto type = env->GetObjectClass(object);
        random = env->GetMethodID(type, "random", "(I)[B");
        seal = env->GetMethodID(type, "seal", "(Ljava/lang/String;[B[B[B)[[B");
        open = env->GetMethodID(type, "open", "(Ljava/lang/String;[B[B[B[B)[B");
        hmac = env->GetMethodID(type, "hmac", "([B)[B");
        env->DeleteLocalRef(type);
    }
    std::optional<QByteArray> copy(jbyteArray value) const {
        if (!value || env->ExceptionCheck()) return {};
        auto result = Array(env, value);
        auto zeros = QByteArray(result.size(), 0);
        if (!zeros.isEmpty()) env->SetByteArrayRegion(value, 0, zeros.size(), reinterpret_cast<const jbyte*>(zeros.constData()));
        env->DeleteLocalRef(value); return result;
    }
    ArchiveCrypto callbacks() const {
        ArchiveCrypto result;
        result.random = [this](int length) -> std::optional<QByteArray> {
            return object && random ? copy(static_cast<jbyteArray>(env->CallObjectMethod(object, random, jint(length)))) : std::nullopt;
        };
        result.sealAes256Gcm = [this](const QString &key, const QByteArray &nonce, const QByteArray &plain,
                const QByteArray &aad) -> std::optional<ArchiveSealedBytes> {
            if (!object || !seal) return {};
            auto id = JavaText(env, key); auto n = JavaBytes(env, nonce); auto p = JavaBytes(env, plain); auto a = JavaBytes(env, aad);
            if (!id || !n || !p || !a || env->ExceptionCheck()) return {};
            auto value = static_cast<jobjectArray>(env->CallObjectMethod(object, seal, id, n, p, a));
            auto zeros = QByteArray(plain.size(), 0);
            if (!env->ExceptionCheck() && !zeros.isEmpty()) env->SetByteArrayRegion(p, 0, zeros.size(), reinterpret_cast<const jbyte*>(zeros.constData()));
            env->DeleteLocalRef(id); env->DeleteLocalRef(n); env->DeleteLocalRef(p); env->DeleteLocalRef(a);
            if (!value || env->ExceptionCheck() || env->GetArrayLength(value) != 2) return {};
            auto ciphertext = copy(static_cast<jbyteArray>(env->GetObjectArrayElement(value, 0)));
            auto tag = copy(static_cast<jbyteArray>(env->GetObjectArrayElement(value, 1))); env->DeleteLocalRef(value);
            if (!ciphertext || !tag) return {};
            return ArchiveSealedBytes{ *ciphertext, *tag };
        };
        result.openAes256Gcm = [this](const QString &key, const QByteArray &nonce, const ArchiveSealedBytes &sealed,
                const QByteArray &aad) -> std::optional<QByteArray> {
            if (!object || !open) return {};
            auto id = JavaText(env, key); auto n = JavaBytes(env, nonce); auto c = JavaBytes(env, sealed.ciphertext);
            auto t = JavaBytes(env, sealed.tag); auto a = JavaBytes(env, aad);
            if (!id || !n || !c || !t || !a || env->ExceptionCheck()) return {};
            auto value = static_cast<jbyteArray>(env->CallObjectMethod(object, open, id, n, c, t, a));
            env->DeleteLocalRef(id); env->DeleteLocalRef(n); env->DeleteLocalRef(c); env->DeleteLocalRef(t); env->DeleteLocalRef(a);
            return copy(value);
        };
        return result;
    }
    std::optional<QByteArray> hash(const QByteArray &input) const {
        if (!object || !hmac) return {};
        auto bytes = JavaBytes(env, input);
        if (!bytes) return {};
        auto value = static_cast<jbyteArray>(env->CallObjectMethod(object, hmac, bytes)); env->DeleteLocalRef(bytes); return copy(value);
    }
};
AccountKey Account(const QJsonObject &args) {
    auto value = ParseAccountKey(Text(args, u"account"_q));
    if (!value) throw std::runtime_error("archive account");
    return *value;
}
ArchiveChatKey Chat(QJsonObject value) {
    auto result = ArchiveChatKey(); auto kind = value.take(u"kind"_q).toInt(-1);
    if (kind < 0 || kind > 3) throw std::runtime_error("archive chat kind");
    result.kind = ArchiveChatKind(kind); result.id = Signed(value.take(u"id"_q)); result.preserved = value;
    if (!IsArchiveChatKey(result)) throw std::runtime_error("archive chat");
    return result;
}
ArchiveMessageKey Key(QJsonObject value) {
    auto result = ArchiveMessageKey(); auto account = ParseAccountKey(value.take(u"account"_q).toString());
    if (!account) throw std::runtime_error("archive message account");
    result.account = *account; result.chat = Chat(value.take(u"chat"_q).toObject());
    result.messageId = Signed(value.take(u"messageId"_q));
    auto collection = value.take(u"collection"_q).toInt(-1);
    if (collection < 0 || collection > 2) throw std::runtime_error("archive collection");
    result.collection = ArchiveCollection(collection); result.collectionId = Signed(value.take(u"collectionId"_q));
    auto representation = value.take(u"representation"_q);
    if (!representation.isString()) throw std::runtime_error("archive representation");
    result.representation = representation.toString(); result.preserved = value;
    if (!IsArchiveMessageKey(result)) throw std::runtime_error("archive message key");
    return result;
}
ArchiveSqlSecurity Security(const QJsonObject &args, const Crypto &crypto) {
    auto result = ArchiveSqlSecurity(); result.storeId = Text(args, u"store"_q); result.keyId = Text(args, u"keyId"_q);
    result.crypto = crypto.callbacks(); result.hmacSha256 = [&crypto](const QByteArray &value) { return crypto.hash(value); };
    return result;
}
QJsonObject Statement(const ArchiveSqlStatement &value) {
    auto binds = QJsonArray();
    for (const auto &bind : value.binds) {
        binds.push_back(std::visit([](const auto &entry) -> QJsonArray {
            using T = std::decay_t<decltype(entry)>;
            if constexpr (std::is_same_v<T, std::nullptr_t>) return { 0 };
            else if constexpr (std::is_same_v<T, int64>) return { 1, QString::number(entry) };
            else if constexpr (std::is_same_v<T, QString>) return { 2, entry };
            else return { 3, Encoded(entry) };
        }, bind));
    }
    return { {u"sql"_q,value.sql}, {u"binds"_q,binds}, {u"expectation"_q,int(value.expectation)}, {u"counter"_q,int(value.counter)},
        {u"minRows"_q,QString::number(value.rows.minimum)}, {u"maxRows"_q,QString::number(value.rows.maximum)},
        {u"minChanges"_q,QString::number(value.changes.minimum)}, {u"maxChanges"_q,QString::number(value.changes.maximum)} };
}
QString Status(ArchiveSqlStatus status) {
    switch (status) {
    case ArchiveSqlStatus::Ready: return u"Ready"_q;
    case ArchiveSqlStatus::Invalid: return u"Invalid"_q;
    case ArchiveSqlStatus::ReadOnly: return u"ReadOnly"_q;
    case ArchiveSqlStatus::EncodingFailed: return u"EncodingFailed"_q;
    case ArchiveSqlStatus::TooLarge: return u"TooLarge"_q;
    case ArchiveSqlStatus::RetentionGap: return u"RetentionGap"_q;
    case ArchiveSqlStatus::SchemaMismatch: return u"SchemaMismatch"_q;
    }
    return u"Invalid"_q;
}
QJsonObject Program(const ArchiveSqlProgram &value) {
    if (value && !IsBoundedArchiveSqlProgram(value)) return {{u"status"_q,u"TooLarge"_q},{u"statements"_q,QJsonArray()}};
    auto statements = QJsonArray();
    if (value && IsBoundedArchiveSqlProgram(value)) for (const auto &statement : value.statements) statements.push_back(Statement(statement));
    return { {u"status"_q,Status(value.status)}, {u"retentionGap"_q,value.retentionGapReason}, {u"statements"_q,statements} };
}
ArchiveStoredRow Row(const QJsonObject &value, const AccountKey &account, const ArchiveSqlSecurity &security) {
    auto kind = value.value(u"kind"_q).toInt(-1); auto id = Text(value, u"id"_q);
    if (kind < 1 || kind > 8 || id.size() > 4096) throw std::runtime_error("archive row identity");
    auto frame = Bytes(value.value(u"frame"_q));
    auto opened = OpenArchiveEnvelope({account, security.storeId, ArchiveRecordKind(kind), id.toUtf8()}, security.keyId, frame, security.crypto);
    if (!opened) throw std::runtime_error("archive row authentication");
    auto decoded = DecodeArchiveRow(opened.bytes);
    if (!decoded.row || int(ArchiveRowKind(*decoded.row)) != kind) throw std::runtime_error("archive row codec");
    if (decoded.row->body) {
        auto chunks = value.value(u"chunks"_q);
        if (!chunks.isArray() || chunks.toArray().size() > 4) throw std::runtime_error("archive body chunks");
        auto frames = std::vector<QByteArray>(); for (const auto &chunk : chunks.toArray()) frames.push_back(Bytes(chunk));
        auto restored = RestoreArchiveSqlVersionBody(*decoded.row, frames, security);
        if (!restored) throw std::runtime_error("archive body authentication");
        decoded.row = std::move(restored);
    }
    return {id, Signed(value.value(u"position"_q)), *decoded.row};
}
ArchiveSqlSlice Slice(const QJsonObject &args, const AccountKey &account, const ArchiveSqlSecurity &security) {
    auto values = args.value(u"rows"_q);
    if (!values.isArray() || values.toArray().size() > kArchiveSqlSliceRowLimit) throw std::runtime_error("archive slice rows");
    auto rows = std::vector<ArchiveStoredRow>();
    for (const auto &value : values.toArray()) rows.push_back(Row(value.toObject(), account, security));
    auto result = RestoreArchiveSqlSlice(std::move(rows), security);
    if (!result || result->state.account != account) throw std::runtime_error("archive closed slice");
    return *result;
}
QJsonObject Removal(const ArchiveRemoval &value) {
    auto encoded = EncodeArchiveRemovalTombstone(value);
    if (!encoded) throw std::runtime_error("archive removal codec");
    return { {u"status"_q,u"Ready"_q}, {u"tombstone"_q,Encoded(*encoded)}, {u"action"_q,int(value.action)},
        {u"generation"_q,QString::number(value.epoch.generation)}, {u"operation"_q,value.operationId} };
}
#include "archive_staging_bridge.inc"
#include "archive_access_bridge.inc"
#include "archive_query_bridge.inc"
#include "archive_maintenance_bridge.inc"
#include "archive_source_bridge.inc"
QJsonObject Dispatch(const QString &operation, const QJsonObject &args, const Crypto &crypto) {
    if (auto result = ArchiveSourceDispatch(operation,args,crypto)) return *result;
    if (auto result = ArchiveMaintenanceDispatch(operation,args,crypto)) return *result;
    if (auto result = ArchiveQueryDispatch(operation,args,crypto)) return *result;
    if (auto result = ArchiveAccessDispatch(operation,args,crypto)) return *result;
    if(auto media=ArchiveMediaDispatch(operation,args,crypto))return *media;
    if (operation == u"canonical"_q) {
        auto result = CanonicalizeSyncJson(Bytes(args.value(u"bytes"_q)));
        return result ? QJsonObject{{u"status"_q,u"Ready"_q},{u"bytes"_q,Encoded(result.canonical)}} : QJsonObject{{u"status"_q,u"Invalid"_q}};
    }
    auto account = Account(args);
    if (operation == u"connection"_q) {
        auto values=QJsonArray(); for (const auto &sql : ArchiveSqlConnectionSetup()) values.push_back(sql);
        return {{u"status"_q,u"Ready"_q},{u"setup"_q,values}};
    }
    if (operation == u"keyOpen"_q) {
        auto facts = ArchiveKeyFacts(); facts.archiveExists = args.value(u"archiveExists"_q).toBool();
        facts.wrappedKeyExists = args.value(u"wrappedKeyExists"_q).toBool(); facts.platformKeyAvailable = args.value(u"platformKeyAvailable"_q).toBool();
        facts.removalPending = args.value(u"removalPending"_q).toBool(); facts.storageVersion = Signed(args.value(u"storageVersion"_q));
        facts.envelopeVersion = Signed(args.value(u"envelopeVersion"_q)); facts.capability.platform = ArchiveKeyPlatform::Android;
        facts.capability.androidApiLevel = args.value(u"api"_q).toInt(); facts.capability.wrappingAvailable = args.value(u"wrappingAvailable"_q).toBool();
        return {{u"action"_q,int(PlanArchiveKeyOpen(facts))}};
    }
    if (operation == u"keyInspect"_q) {
        auto context=ArchiveEnvelopeContext{account,Text(args,u"store"_q),ArchiveRecordKind::WrappedKey,Text(args,u"keyId"_q).toUtf8()};
        auto result=OpenArchiveEnvelope(context,Text(args,u"platformKeyId"_q),Bytes(args.value(u"frame"_q)),{});
        auto status=result.status;
        return {{u"status"_q,status==ArchiveEnvelopeStatus::NewerVersion || status==ArchiveEnvelopeStatus::UnsupportedSuite
            ?u"ReadOnly"_q:status==ArchiveEnvelopeStatus::CryptoFailure?u"Ready"_q:u"Invalid"_q}};
    }
    if (operation == u"derive"_q) {
        auto derivation = ArchiveKeyDerivationFor(account, Text(args,u"store"_q), QString(), ArchiveKeyPurpose::IndexTokens);
        if (!derivation) return {{u"status"_q,u"Invalid"_q}};
        return {{u"status"_q,u"Ready"_q},{u"salt"_q,Encoded(derivation->salt)},{u"info"_q,Encoded(derivation->info)},{u"length"_q,derivation->outputBytes}};
    }
    if (operation == u"schema"_q) return {{u"status"_q,Status(CheckArchiveSqlVersion(Signed(args.value(u"version"_q))))}};
    if (operation == u"admit"_q) {
        auto values=args.value(u"definitions"_q); if (!values.isArray() || values.toArray().size()>128) throw std::runtime_error("archive schema definitions");
        auto definitions=std::vector<QString>();
        for (const auto &value:values.toArray()) { if (!value.isString() || value.toString().size()>1024*1024) throw std::runtime_error("archive schema definition"); definitions.push_back(value.toString()); }
        return {{u"status"_q,Status(CheckArchiveSqlSchema(Signed(args.value(u"version"_q)),Text(args,u"fingerprint"_q),definitions))}};
    }
    if (operation == u"removeBegin"_q) {
        auto epoch = ArchiveStoreEpoch{account, Text(args,u"store"_q), Unsigned(args.value(u"generation"_q)), false};
        auto plan = BeginArchiveRemoval(epoch, Text(args,u"operationId"_q)); return plan ? Removal(*plan) : QJsonObject{{u"status"_q,u"Invalid"_q}};
    }
    if (operation == u"removeResume"_q || operation == u"removeAdvance"_q) {
        auto value = ResumeArchiveRemoval(Bytes(args.value(u"tombstone"_q)));
        if (!value || value->epoch.account != account || value->epoch.storeId != Text(args,u"store"_q)) return {{u"status"_q,u"Invalid"_q}};
        if (operation == u"removeResume"_q) return Removal(*value);
        auto action = args.value(u"action"_q).toInt(-1);
        if (action < 0 || action >= int(ArchiveRemovalAction::Done)) return {{u"status"_q,u"Invalid"_q}};
        value->action = ArchiveRemovalAction(action);
        auto ack = ArchiveRemovalAck{account, Text(args,u"store"_q), Text(args,u"operationId"_q), Unsigned(args.value(u"generation"_q)),
            ArchiveRemovalAction(args.value(u"action"_q).toInt(-1)), args.value(u"success"_q).toBool(), args.value(u"durable"_q).toBool(),
            Unsigned(args.value(u"readers"_q)), Unsigned(args.value(u"writers"_q)), args.value(u"closed"_q).toBool()};
        auto result = AdvanceArchiveRemoval(*value, ack); auto output = Removal(result.state); output.insert(u"advance"_q,int(result.status)); return output;
    }
    if (operation == u"usage"_q) {
        auto epoch=ArchiveStoreEpoch{account,Text(args,u"store"_q),Unsigned(args.value(u"generation"_q)),false};
        auto scan=ArchiveUsageScan{epoch,args.value(u"cursor"_q).toString(),Unsigned(args.value(u"bytes"_q)),false};
        auto entries=args.value(u"entries"_q).toArray(); if (entries.size()>128) throw std::runtime_error("archive usage page");
        auto page=std::vector<ArchiveUsageEntry>();
        for (const auto &entry:entries) { auto value=entry.toObject(); page.push_back({Text(value,u"token"_q),Unsigned(value.value(u"bytes"_q))}); }
        auto result=ContinueArchiveUsage(scan,epoch,page,args.value(u"complete"_q).toBool());
        if (!result) return {{u"status"_q,u"Invalid"_q}};
        return {{u"status"_q,u"Ready"_q},{u"bytes"_q,QString::number(result->bytes)},{u"cursor"_q,result->cursor},{u"complete"_q,result->complete}};
    }
    if (operation == u"snapshot"_q) {
        auto value = args.value(u"body"_q).toObject();
        auto snapshot = ArchiveVersionSnapshot(); snapshot.key = Key(args.value(u"key"_q).toObject());
        auto &version = snapshot.version; version.id = u"snapshot"_q; version.revision = version.id;
        version.origin = ArchiveOrigin::Baseline; version.completeness = ArchiveCompleteness(value.value(u"completeness"_q).toInt(0));
        version.content.text = value.value(u"text"_q).toString(); version.content.caption = value.value(u"caption"_q).toString();
        version.content.entities = value.value(u"entities"_q).toArray(); version.content.rich = value.value(u"rich"_q);
        version.content.opaqueFormat = value.value(u"opaqueFormat"_q).toString(); version.content.opaque = Bytes(value.value(u"opaque"_q));
        for (const auto &item : value.value(u"media"_q).toArray()) {
            auto descriptor = item.toObject(); auto id = Text(descriptor,u"identity"_q);
            version.content.media.push_back({id,Text(descriptor,u"kind"_q),descriptor.value(u"attributes"_q).toObject(),{}});
            version.media.push_back({id,ArchiveMediaState::Missing,{},{}});
        }
        version.observation.observedAt = Signed(value.value(u"observedAt"_q)); version.firstObservedAt = version.observation.observedAt;
        version.observation.serverTime = Signed(value.value(u"serverTime"_q)); version.observation.topicId = Signed(value.value(u"topicId"_q));
        version.observation.representation = snapshot.key.representation;
        version.observation.context = value.value(u"context"_q).toObject(); version.observation.transport = value.value(u"transport"_q).toObject();
        version.observation.payloadFormat = Text(value,u"payloadFormat"_q); version.observation.payload = Bytes(value.value(u"payload"_q));
        version.missing.revision = version.revision;
        for (const auto &item : value.value(u"missing"_q).toArray()) {
            auto slot = ArchiveMissingSlot(); for (const auto &part : item.toArray()) slot.path.push_back(part.toString());
            version.missing.content.push_back(std::move(slot));
        }
        auto bytes = EncodeArchiveVersionSnapshot(snapshot);
        if (!bytes || bytes->size() > kArchiveCodecByteLimit) return {{u"status"_q,u"RetentionGap"_q}};
        return {{u"status"_q,u"Ready"_q},{u"snapshot"_q,Encoded(*bytes)},{u"hash"_q,ArchiveContentHash(version.content)}};
    }
    auto security = Security(args, crypto);
    if (operation == u"journalSeal"_q || operation == u"journalOpen"_q) {
        auto token = Text(args,u"token"_q);
        if (!IsArchiveHash(token)) throw std::runtime_error("archive journal identity");
        auto context = ArchiveEnvelopeContext{account,security.storeId,ArchiveRecordKind::Maintenance,(u"capture-journal/"_q+token).toUtf8()};
        auto input = Bytes(args.value(u"bytes"_q));
        if (input.size() > 65536) throw std::runtime_error("archive journal size");
        auto output = operation == u"journalSeal"_q ? SealArchiveEnvelope(context,security.keyId,input,security.crypto)
            : OpenArchiveEnvelope(context,security.keyId,input,security.crypto);
        return output ? QJsonObject{{u"status"_q,u"Ready"_q},{u"bytes"_q,Encoded(output.bytes)}} : QJsonObject{{u"status"_q,u"Invalid"_q}};
    }

    if (operation == u"causeToken"_q) {
        auto token=ArchiveSqlToken(account,ArchiveIndexKind::Blob,(u"lifecycle-task/"_q+Text(args,u"task"_q)).toUtf8(),security);
        return token.isEmpty()?QJsonObject{{u"status"_q,u"Invalid"_q}}:QJsonObject{{u"status"_q,u"Ready"_q},{u"token"_q,token}};
    }
    if (operation == u"blobToken"_q) {
        auto token=ArchiveSqlToken(account,ArchiveIndexKind::Blob,Text(args,u"blob"_q).toUtf8(),security);
        return token.isEmpty()?QJsonObject{{u"status"_q,u"Invalid"_q}}:QJsonObject{{u"status"_q,u"Ready"_q},{u"token"_q,token}};
    }
    if (operation == u"initialize"_q) {
        auto state = ArchiveState(); state.account = account;
        return Program(InitializeArchiveSql(state, security));
    }
    if (operation == u"query"_q) {
        auto query = Text(args,u"query"_q); auto statement = ArchiveSqlStatement(); auto id = QString();
        if (query == u"header"_q) { statement = ArchiveSqlReadPage(ArchiveRecordKind::Account, 1); id = u"account"_q; }
        else if (query == u"tables"_q) statement = ArchiveSqlStatement{u"SELECT name,type FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY name"_q,{},ArchiveSqlExpectation::Rows};
        else if (query == u"schemaVersion"_q) statement = ArchiveSqlStatement{u"SELECT schema_version FROM archive_meta WHERE singleton=1"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"schemaFingerprint"_q) statement = ArchiveSqlStatement{u"SELECT schema_fingerprint FROM archive_meta WHERE singleton=1"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"schemaDefinitions"_q) statement = ArchiveSqlReadSchema();
        else if (query == u"metadata"_q) statement = ArchiveSqlStatement{u"SELECT schema_version,account_token,store_id,revision,generation,maintenance_id FROM archive_meta WHERE singleton=1"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"foreignKeys"_q) statement = ArchiveSqlStatement{u"PRAGMA foreign_keys"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"journalMode"_q) statement = ArchiveSqlStatement{u"PRAGMA journal_mode"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"synchronous"_q) statement = ArchiveSqlStatement{u"PRAGMA synchronous"_q,{},ArchiveSqlExpectation::OneRow};
        else if (query == u"gaps"_q) statement = ArchiveSqlReadCaptureGaps(args.value(u"limit"_q).toInt(128));
        else if (query == u"lookup"_q) {
            auto dialog = Signed(args.value(u"dialog"_q)); auto message = Signed(args.value(u"messageId"_q));
            auto representation = ArchiveSqlToken(account,ArchiveIndexKind::Representation,"original",security);
            if (message <= 0 || representation.isEmpty()) throw std::runtime_error("archive lookup identity");
            auto sql = u"SELECT message_key,envelope FROM messages WHERE message_id=? AND collection=0 AND representation_token=? AND EXISTS(SELECT 1 FROM archive_meta WHERE maintenance_id='')"_q;
            auto binds = std::vector<ArchiveSqlValue>{message,representation};
            if (dialog == 0) sql += u" AND chat_kind IN(0,1)"_q;
            else {
                auto tokens = QJsonArray();
                for (auto kind : {ArchiveChatKind::Group,ArchiveChatKind::Channel}) tokens.push_back(ArchiveSqlChatId({kind,-dialog,{}},account,security));
                sql += u" AND chat_token IN(?,?)"_q; binds.push_back(tokens[0].toString()); binds.push_back(tokens[1].toString());
            }
            statement = ArchiveSqlStatement{sql + u" LIMIT 2"_q,std::move(binds),ArchiveSqlExpectation::Rows}; statement.rows.maximum = 2;
        }
        else {
            auto key = Key(args.value(u"key"_q).toObject()); if (key.account != account) throw std::runtime_error("archive query account");
            if (query == u"message"_q) { statement = ArchiveSqlReadMessage(key,security); id = ArchiveSqlMessageId(key,security); }
            else if (query == u"head"_q) statement = ArchiveSqlReadHead(key,security);
            else if (query == u"jobs"_q) statement = ArchiveSqlVersionJobs(key,Text(args,u"version"_q),128,security);
            else if (query == u"job"_q || query == u"blob"_q) statement = ArchiveSqlReadRecord(account,
                query == u"job"_q ? ArchiveRecordKind::Job : ArchiveRecordKind::Blob,Text(args,u"record"_q),security);
            else if (query == u"versions"_q) statement = ArchiveSqlReadVersions(key,args.value(u"limit"_q).toInt(32),security,args.value(u"before"_q).toString());
            else if (query == u"version"_q) { statement = ArchiveSqlReadVersion(key,Text(args,u"version"_q),security); id = ArchiveSqlChildId(key,ArchiveIndexKind::Version,Text(args,u"version"_q),security); }
            else if (query == u"receipt"_q) { statement = ArchiveSqlReadReceipt(key,Text(args,u"event"_q),security); id = ArchiveSqlChildId(key,ArchiveIndexKind::Event,Text(args,u"event"_q),security); }
            else if (query == u"epoch"_q) { statement = ArchiveSqlReadChatEpoch(account,key.chat,security); id = ArchiveSqlChatId(key.chat,account,security); }
            else throw std::runtime_error("archive query");
        }
        if (statement.sql.isEmpty()) return {{u"status"_q,u"Invalid"_q}};
        return {{u"status"_q,u"Ready"_q},{u"statement"_q,Statement(statement)},{u"id"_q,id},
            {u"accountToken"_q,ArchiveSqlToken(account,ArchiveIndexKind::Account,"account",security)}};
    }
    if (operation == u"openRow"_q || operation == u"bodyQuery"_q) {
        auto value = args.value(u"row"_q).toObject();
        if (operation == u"bodyQuery"_q) {
            auto id = Text(value,u"id"_q); auto kind = value.value(u"kind"_q).toInt(-1);
            if (kind != int(ArchiveRecordKind::Version)) throw std::runtime_error("archive body query kind");
            auto opened = OpenArchiveEnvelope({account,security.storeId,ArchiveRecordKind::Version,id.toUtf8()},security.keyId,Bytes(value.value(u"frame"_q)),security.crypto);
            auto decoded = opened ? DecodeArchiveRow(opened.bytes) : ArchiveRowDecodeResult();
            if (!decoded.row) throw std::runtime_error("archive body query row");
            return {{u"status"_q,u"Ready"_q},{u"needed"_q,decoded.row->body.has_value()},
                {u"statement"_q,Statement(ArchiveSqlReadBodyChunks(*decoded.row,security))}};
        }
        auto row = Row(value,account,security); auto encoded = EncodeArchiveRow(row.row);
        if (!encoded) throw std::runtime_error("archive opened row");
        auto output = QJsonObject{{u"status"_q,u"Ready"_q},{u"row"_q,Encoded(*encoded)}};
        if (const auto state=std::get_if<ArchiveState>(&row.row.value)) {
            if (state->account!=account) throw std::runtime_error("archive header account");
            output.insert(u"revision"_q,QString::number(state->revision)); output.insert(u"accountGeneration"_q,QString::number(state->accountGeneration));
        }
        return output;
    }
    if (operation == u"gap"_q) {
        auto gap = ArchiveCaptureGap{account,security.storeId,Text(args,u"id"_q),Signed(args.value(u"observedAt"_q)),Text(args,u"reason"_q),args.value(u"details"_q).toObject()};
        return Program(PlanArchiveCaptureGap(gap,security));
    }
    if (operation == u"leaseAcquire"_q || operation == u"leaseRelease"_q) {
        auto slice=Slice(args,account,security); auto plan=ArchiveTransaction();
        if (operation==u"leaseRelease"_q) plan=PlanArchiveReaderRelease(slice.state,Text(args,u"id"_q));
        else {
            auto decoded=DecodeArchiveRow(Bytes(args.value(u"lease"_q)));
            auto lease=decoded.row?std::get_if<ArchiveReaderLease>(&decoded.row->value):nullptr;
            if (!lease || lease->key.account!=account) throw std::runtime_error("archive reader lease");
            plan=PlanArchiveReaderAcquire(slice.state,*lease);
        }
        auto output=Program(TranslateArchiveSql(plan,slice,security)); output.insert(u"modelStatus"_q,int(plan.status)); return output;
    }
    if (operation == u"facts"_q) {
        auto slice = Slice(args,account,security); auto key = Key(args.value(u"key"_q).toObject());
        if (key.account != account) throw std::runtime_error("archive facts account");
        auto generation = ArchiveGenerationFor(slice.state,key.chat); auto message = FindArchiveMessage(slice.state,key);
        auto output = QJsonObject{{u"status"_q,u"Ready"_q},{u"revision"_q,QString::number(slice.state.revision)},
            {u"accountGeneration"_q,QString::number(generation.account)},{u"chatGeneration"_q,QString::number(generation.chat)},
            {u"order"_q,QString::number(message?message->order:0)},{u"deleted"_q,message&&message->deleted}};
        if (message && !message->versions.empty()) {
            const auto &version = message->versions.back(); output.insert(u"head"_q,version.id); output.insert(u"revisionId"_q,version.revision);
            output.insert(u"hash"_q,ArchiveContentHash(version.content));
        }
        return output;
    }
    if (operation == u"capture"_q) {
        auto slice = Slice(args,account,security); auto input = args.value(u"event"_q).toObject(); auto event = ArchiveEvent();
        auto origin = input.value(u"origin"_q).toInt(-1); if (origin < 0 || origin > 7) throw std::runtime_error("archive origin");
        event.origin = ArchiveOrigin(origin); event.id = Text(input,u"id"_q); event.order = Unsigned(input.value(u"order"_q));
        event.generation = {Unsigned(input.value(u"accountGeneration"_q)),Unsigned(input.value(u"chatGeneration"_q))};
        if (input.contains(u"snapshot"_q)) {
            auto decoded = DecodeArchiveVersionSnapshot(Bytes(input.value(u"snapshot"_q)));
            if (!decoded.snapshot) throw std::runtime_error("archive capture snapshot");
            const auto &snapshot = *decoded.snapshot; event.key = snapshot.key; event.content = snapshot.version.content;
            event.completeness = snapshot.version.completeness; event.missing = snapshot.version.missing;
            event.observation = snapshot.version.observation; event.source = snapshot.version.source; event.missing.revision.clear();
        } else {
            event.key = Key(input.value(u"key"_q).toObject()); event.observation.observedAt = Signed(input.value(u"observedAt"_q));
            event.observation.serverTime = Signed(input.value(u"serverTime"_q)); event.observation.representation = event.key.representation;
        }
        if (event.key.account != account) throw std::runtime_error("archive capture account");
        event.targetVersion = input.value(u"targetVersion"_q).toString(); event.targetRevision = input.value(u"targetRevision"_q).toString();
        event.expectedContentHash = input.value(u"expectedContentHash"_q).toString();
        if (input.contains(u"lifecycleEpoch"_q)) {
            auto captured=input.value(u"lifecycleEpoch"_q).toObject();
            event.lifecycleEpoch=ArchiveStoreEpoch{Account(captured),Text(captured,u"store"_q),Unsigned(captured.value(u"generation"_q)),captured.value(u"removalPending"_q).toBool()};
            event.lifecycleMediaIdentity=Text(input,u"lifecycleMediaIdentity"_q);
        }
        auto current=ArchiveStoreEpoch{account,security.storeId,Unsigned(args.value(u"workGeneration"_q)),false};
        auto plan = PlanArchiveSqlCapture(slice,event,security,&current); auto output = Program(plan.program);
        output.insert(u"modelStatus"_q,int(plan.transaction.status)); output.insert(u"mediaRefusal"_q,int(plan.transaction.mediaRefusal)); return output;
    }
    return {{u"status"_q,u"Invalid"_q}};
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveCore_callNative(JNIEnv *env, jclass, jbyteArray input, jobject provider) {
    try {
        auto document = QJsonDocument::fromJson(Array(env,input));
        if (!document.isObject()) throw std::runtime_error("archive bridge JSON");
        auto request = document.object(); auto crypto = Crypto(env,provider);
        auto result = Dispatch(Text(request,u"operation"_q),request.value(u"arguments"_q).toObject(),crypto);
        if (env->ExceptionCheck()) return nullptr;
        return JavaBytes(env,QJsonDocument(result).toJson(QJsonDocument::Compact));
    } catch (const std::exception &) {
        if (env->ExceptionCheck()) return nullptr;
        return JavaBytes(env,"{\"status\":\"Invalid\"}");
    }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveCore_envelopeNative(JNIEnv *env, jclass, jstring accountText, jstring storeText,
        jstring dataIdText, jstring platformIdText, jbyteArray input, jboolean wrap, jobject provider) {
    try {
        auto account = ParseAccountKey(String(env,accountText)); if (!account) return nullptr;
        auto value = Array(env,input,8 * 1024 * 1024); auto crypto = Crypto(env,provider);
        auto result = wrap ? WrapArchiveDataKey(*account,String(env,storeText),String(env,dataIdText),String(env,platformIdText),value,crypto.callbacks())
            : UnwrapArchiveDataKey(*account,String(env,storeText),String(env,dataIdText),String(env,platformIdText),value,crypto.callbacks());
        value.fill(0);
        if (!result || env->ExceptionCheck()) return nullptr;
        auto bytes = JavaBytes(env,result.bytes); result.bytes.fill(0); return bytes;
    } catch (const std::exception &) { return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveCore_mediaNative(JNIEnv *env,jclass,jstring accountText,jstring storeText,
        jstring keyText,jstring blobText,jlong total,jlong index,jbyteArray input,jboolean seal,jobject provider) {
    try {
        auto account=ParseAccountKey(String(env,accountText)); if (!account || total<0 || index<0) return nullptr;
        auto context=ArchiveMediaChunkContext(*account,String(env,storeText),String(env,blobText),uint64_t(total),uint64_t(index));
        auto size=ArchiveMediaChunkSize(uint64_t(total),uint64_t(index)); if (!context || !size) return nullptr;
        auto value=Array(env,input,2*1024*1024); auto crypto=Crypto(env,provider);
        if (seal && value.size()!=*size) { value.fill(0); return nullptr; }
        auto result=seal?SealArchiveEnvelope(*context,String(env,keyText),value,crypto.callbacks())
            :OpenArchiveEnvelope(*context,String(env,keyText),value,crypto.callbacks());
        value.fill(0);
        if (!result || (!seal && result.bytes.size()!=*size) || env->ExceptionCheck()) return nullptr;
        auto output=JavaBytes(env,result.bytes); result.bytes.fill(0); return output;
    } catch (const std::exception &) { return nullptr; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveCore_checkNative(JNIEnv *, jclass, jint expectation,
        jlong minRows, jlong maxRows, jlong minChanges, jlong maxChanges, jint counter,
        jboolean reachedDone, jlong rows, jlong direct, jlong total) {
    if (expectation < 0 || expectation > 5 || counter < 0 || counter > 1) return false;
    auto statement = ArchiveSqlStatement(); statement.expectation = ArchiveSqlExpectation(expectation);
    statement.rows = {minRows,maxRows}; statement.changes = {minChanges,maxChanges}; statement.counter = ArchiveSqlChangeCounter(counter);
    return CheckArchiveSqlResult(statement,reachedDone,rows,direct,total);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleArchiveCore_acknowledgeNative(JNIEnv *, jclass, jboolean checked, jboolean durable) {
    return AcknowledgeArchiveSqlCommit(checked,durable);
}
