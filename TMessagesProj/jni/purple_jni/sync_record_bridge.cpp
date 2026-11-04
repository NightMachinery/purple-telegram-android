#include <jni.h>
#include "purple/purple_account_files.h"
#include "purple/purple_peer_token.h"
#include "purple/purple_sync_inventory.h"
#include "purple/purple_sync_json.h"
#include "purple/purple_types.h"
#include <QtCore/QCryptographicHash>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <limits>

namespace {
using namespace Purple;
struct InvalidInput {};

void Require(bool condition) {
    if (!condition) throw InvalidInput();
}

QString Text(const QJsonValue &value) {
    Require(value.isString());
    return value.toString();
}

uint64_t Counter(const QJsonValue &value) {
    const auto text = Text(value);
    auto ok = false;
    const auto result = text.toULongLong(&ok);
    Require(ok && QString::number(result) == text);
    return result;
}

int64_t Date(const QJsonValue &value) {
    const auto result = Counter(value);
    Require(result <= uint64_t(std::numeric_limits<int64_t>::max()));
    return int64_t(result);
}

int32_t Id(const QJsonValue &value) {
    const auto result = Counter(value);
    Require(result <= uint64_t(std::numeric_limits<int32_t>::max()));
    return int32_t(result);
}

QByteArray Bytes(const QJsonValue &value) {
    const auto text = Text(value);
    const auto encoded = text.toLatin1();
    const auto decoded = QByteArray::fromBase64(encoded);
    Require(QString::fromLatin1(encoded) == text && decoded.toBase64() == encoded);
    return decoded;
}

AccountKey Account(const QJsonValue &value) {
    const auto text = Text(value);
    const auto result = ParseAccountKey(text);
    Require(result.has_value());
    Require(AccountKeyText(result->environment, result->userId) == text);
    return *result;
}

PeerToken Peer(const QJsonValue &value, const AccountKey &account) {
    const auto text = Text(value);
    const auto result = ParsePeerToken(text);
    Require(result.has_value());
    Require(PeerTokenText(*result, uint64_t(account.userId)) == text);
    return *result;
}

QJsonObject Document(const QByteArray &bytes) {
    const auto strict = CanonicalizeSyncJson(bytes);
    Require(bool(strict));
    const auto document = QJsonDocument::fromJson(strict.canonical);
    Require(document.isObject());
    return document.object();
}

QByteArray Canonical(const QJsonObject &object) {
    const auto result = CanonicalizeSyncJson(QJsonDocument(object).toJson(QJsonDocument::Compact));
    Require(bool(result));
    return result.canonical;
}

QJsonObject Evaluate(const QString &op, const QJsonObject &o) {
    const auto hash = [](const QByteArray &bytes) {
        return QString::fromLatin1(QCryptographicHash::hash(bytes, QCryptographicHash::Sha256).toHex());
    };
    if (op == u"hash"_q) {
        return {{u"status"_q, u"Ready"_q}, {u"hash"_q, hash(Bytes(o.value(u"bytes"_q)))}};
    }
    Require(op == u"message"_q);
    const auto account = Account(o.value(u"account"_q));
    const auto chat = Peer(o.value(u"chat"_q), account);
    const auto id = Id(o.value(u"id"_q));
    const auto date = Date(o.value(u"date"_q));
    const auto bytes = Bytes(o.value(u"bytes"_q));
    const auto ownership = Text(o.value(u"ownership"_q));
    const auto family = Text(o.value(u"family"_q));
    Require(id > 0 && date > 0 && uint64_t(date) <= 9007199254740991ULL
        && bytes.size() <= kSyncRecordMaximumBytes
        && (ownership == u"Unknown"_q || ownership == u"Own"_q || ownership == u"Other"_q)
        && (family == u"Settings"_q || family == u"Playlists"_q));
    return {{u"status"_q, u"Ready"_q}, {u"reference"_q, QJsonObject{
        {u"account"_q, *AccountKeyText(account.environment, account.userId)},
        {u"chat"_q, *PeerTokenText(chat)}, {u"id"_q, QString::number(id)}, {u"hash"_q, hash(bytes)}}}};
}

}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleSyncRecordCore_requestNative(
        JNIEnv *env, jclass, jstring operation, jbyteArray input) {
    auto answer = QJsonObject{{u"status"_q, u"Invalid"_q}, {u"canonical"_q, QString()}};
    try {
        Require(operation && input);
        const auto length = env->GetStringLength(operation);
        Require(length <= 32);
        const auto chars = env->GetStringChars(operation, nullptr);
        if (!chars) return nullptr;
        const auto op = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), length);
        env->ReleaseStringChars(operation, chars);
        const auto size = env->GetArrayLength(input);
        Require(size > 0 && size <= 4 * 1024 * 1024);
        auto bytes = QByteArray(size, Qt::Uninitialized);
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return nullptr;
        answer = Evaluate(op, Document(bytes));
    } catch (const InvalidInput &) {
    } catch (const std::exception &) {
    }
    auto bytes = QByteArray();
    try {
        bytes = Canonical(answer);
    } catch (const InvalidInput &) {
        bytes = QByteArray("{\"status\":\"Invalid\"}");
    }
    const auto result = env->NewByteArray(bytes.size());
    if (result) env->SetByteArrayRegion(result, 0, bytes.size(), reinterpret_cast<const jbyte*>(bytes.constData()));
    return result;
}
