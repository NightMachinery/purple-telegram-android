#include <jni.h>
#include "purple/purple_sync_chat.h"
#include "purple/purple_types.h"
#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <limits>

namespace {

using namespace Purple;

QString Name(SyncChatKind value) {
	switch (value) {
	case SyncChatKind::Unknown: return u"Unknown"_q;
	case SyncChatKind::Self: return u"Self"_q;
	case SyncChatKind::Private: return u"Private"_q;
	case SyncChatKind::Bot: return u"Bot"_q;
	case SyncChatKind::BasicGroup: return u"BasicGroup"_q;
	case SyncChatKind::Supergroup: return u"Supergroup"_q;
	case SyncChatKind::Broadcast: return u"Broadcast"_q;
	case SyncChatKind::Secret: return u"Secret"_q;
	case SyncChatKind::ChannelDirectMessages: return u"ChannelDirectMessages"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatKind &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Unknown"_q) { result = SyncChatKind::Unknown; return true; }
	if (text == u"Self"_q) { result = SyncChatKind::Self; return true; }
	if (text == u"Private"_q) { result = SyncChatKind::Private; return true; }
	if (text == u"Bot"_q) { result = SyncChatKind::Bot; return true; }
	if (text == u"BasicGroup"_q) { result = SyncChatKind::BasicGroup; return true; }
	if (text == u"Supergroup"_q) { result = SyncChatKind::Supergroup; return true; }
	if (text == u"Broadcast"_q) { result = SyncChatKind::Broadcast; return true; }
	if (text == u"Secret"_q) { result = SyncChatKind::Secret; return true; }
	if (text == u"ChannelDirectMessages"_q) { result = SyncChatKind::ChannelDirectMessages; return true; }
	return false;
}

QString Name(SyncChatMessageOwnership value) {
	switch (value) {
	case SyncChatMessageOwnership::Unknown: return u"Unknown"_q;
	case SyncChatMessageOwnership::Own: return u"Own"_q;
	case SyncChatMessageOwnership::Other: return u"Other"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatMessageOwnership &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Unknown"_q) { result = SyncChatMessageOwnership::Unknown; return true; }
	if (text == u"Own"_q) { result = SyncChatMessageOwnership::Own; return true; }
	if (text == u"Other"_q) { result = SyncChatMessageOwnership::Other; return true; }
	return false;
}

QString Name(SyncChatBroadcastOwnership value) {
	switch (value) {
	case SyncChatBroadcastOwnership::Unselected: return u"Unselected"_q;
	case SyncChatBroadcastOwnership::CreatorAndSoleAdmin: return u"CreatorAndSoleAdmin"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatBroadcastOwnership &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Unselected"_q) { result = SyncChatBroadcastOwnership::Unselected; return true; }
	if (text == u"CreatorAndSoleAdmin"_q) { result = SyncChatBroadcastOwnership::CreatorAndSoleAdmin; return true; }
	return false;
}

QString Name(SyncChatRefusal value) {
	switch (value) {
	case SyncChatRefusal::None: return u"None"_q;
	case SyncChatRefusal::UnknownKind: return u"UnknownKind"_q;
	case SyncChatRefusal::Secret: return u"Secret"_q;
	case SyncChatRefusal::TopicRouting: return u"TopicRouting"_q;
	case SyncChatRefusal::Public: return u"Public"_q;
	case SyncChatRefusal::Protected: return u"Protected"_q;
	case SyncChatRefusal::Discussion: return u"Discussion"_q;
	case SyncChatRefusal::AutoDelete: return u"AutoDelete"_q;
	case SyncChatRefusal::Paid: return u"Paid"_q;
	case SyncChatRefusal::CannotSendFiles: return u"CannotSendFiles"_q;
	case SyncChatRefusal::SendAsOther: return u"SendAsOther"_q;
	case SyncChatRefusal::Migrated: return u"Migrated"_q;
	case SyncChatRefusal::SlowMode: return u"SlowMode"_q;
	case SyncChatRefusal::BroadcastOwnershipUnselected: return u"BroadcastOwnershipUnselected"_q;
	case SyncChatRefusal::BroadcastNeedsCreatorAndSoleAdmin: return u"BroadcastNeedsCreatorAndSoleAdmin"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatRefusal &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"None"_q) { result = SyncChatRefusal::None; return true; }
	if (text == u"UnknownKind"_q) { result = SyncChatRefusal::UnknownKind; return true; }
	if (text == u"Secret"_q) { result = SyncChatRefusal::Secret; return true; }
	if (text == u"TopicRouting"_q) { result = SyncChatRefusal::TopicRouting; return true; }
	if (text == u"Public"_q) { result = SyncChatRefusal::Public; return true; }
	if (text == u"Protected"_q) { result = SyncChatRefusal::Protected; return true; }
	if (text == u"Discussion"_q) { result = SyncChatRefusal::Discussion; return true; }
	if (text == u"AutoDelete"_q) { result = SyncChatRefusal::AutoDelete; return true; }
	if (text == u"Paid"_q) { result = SyncChatRefusal::Paid; return true; }
	if (text == u"CannotSendFiles"_q) { result = SyncChatRefusal::CannotSendFiles; return true; }
	if (text == u"SendAsOther"_q) { result = SyncChatRefusal::SendAsOther; return true; }
	if (text == u"Migrated"_q) { result = SyncChatRefusal::Migrated; return true; }
	if (text == u"SlowMode"_q) { result = SyncChatRefusal::SlowMode; return true; }
	if (text == u"BroadcastOwnershipUnselected"_q) { result = SyncChatRefusal::BroadcastOwnershipUnselected; return true; }
	if (text == u"BroadcastNeedsCreatorAndSoleAdmin"_q) { result = SyncChatRefusal::BroadcastNeedsCreatorAndSoleAdmin; return true; }
	return false;
}

bool Decimal(const QJsonValue &value, int64_t &result) {
    if (!value.isString()) return false;
    const auto text = value.toString();
    auto ok = false;
    result = text.toLongLong(&ok);
    return ok && result >= 0 && QString::number(result) == text;
}

bool Boolean(const QJsonObject &object, const QString &key, bool &result) {
    const auto value = object.value(key);
    if (!value.isBool()) return false;
    result = value.toBool();
    return true;
}

bool OptionalBoolean(const QJsonObject &object, const QString &key, std::optional<bool> &result) {
    if (!object.contains(key)) { result.reset(); return true; }
    auto value = false;
    if (!Boolean(object, key, value)) return false;
    result = value;
    return true;
}

bool OptionalCount(const QJsonObject &object, const QString &key, std::optional<uint64_t> &result) {
    if (!object.contains(key)) { result.reset(); return true; }
    auto value = int64_t(0);
    if (!Decimal(object.value(key), value)) return false;
    result = uint64_t(value);
    return true;
}

bool Peer(const QJsonValue &value, PeerToken &result, uint64_t self = 0) {
    if (!value.isString()) return false;
    const auto parsed = ParsePeerToken(value.toString(), self);
    if (!parsed) return false;
    result = *parsed;
    return true;
}

QJsonObject Failure() { return {{u"ok"_q, false}}; }

QJsonObject Evaluate(const QString &operation, const QJsonObject &object) {
    auto result = QJsonObject{{u"ok"_q, true}};
    if (operation == u"token"_q) {
        auto self = int64_t(0); auto peer = PeerToken();
        if (!Decimal(object.value(u"selfUserId"_q), self) || !Peer(object.value(u"token"_q), peer, self)) return Failure();
        result.insert(u"token"_q, *PeerTokenText(peer, self));
    } else if (operation == u"ownership"_q) {
        auto facts = SyncChatMessageFacts(); auto self = int64_t(0);
        if (!Read(object.value(u"kind"_q), facts.kind) || !Read(object.value(u"policy"_q), facts.broadcastOwnership)
            || !Decimal(object.value(u"selfUserId"_q), self)) return Failure();
        facts.selfUserId = uint64_t(self);
        if (!OptionalBoolean(object, u"regular"_q, facts.regular)) return Failure();
        if (!OptionalBoolean(object, u"forwarded"_q, facts.forwarded)) return Failure();
        if (!OptionalBoolean(object, u"viaBot"_q, facts.viaBot)) return Failure();
        if (!OptionalBoolean(object, u"scheduled"_q, facts.scheduled)) return Failure();
        if (!OptionalBoolean(object, u"hasTtl"_q, facts.hasTtl)) return Failure();
        if (!OptionalBoolean(object, u"sentAsOther"_q, facts.sentAsOther)) return Failure();
        if (!OptionalBoolean(object, u"out"_q, facts.out)) return Failure();
        if (!OptionalBoolean(object, u"creator"_q, facts.creator)) return Failure();
        if (!OptionalBoolean(object, u"soleAdmin"_q, facts.soleAdmin)) return Failure();
        if (object.contains(u"sender"_q)) { auto peer = PeerToken(); if (!Peer(object.value(u"sender"_q), peer)) return Failure(); facts.sender = peer; }
        result.insert(u"ownership"_q, Name(GetSyncChatMessageOwnership(facts)));
        result.insert(u"own"_q, IsOwnSyncChatMessage(facts));
    } else if (operation == u"eligibility"_q) {
        auto facts = SyncChatFacts(); auto policy = SyncChatBroadcastOwnership::Unselected;
        if (!Read(object.value(u"kind"_q), facts.kind) || !Read(object.value(u"policy"_q), policy)) return Failure();
        if (!Boolean(object, u"publicUsername"_q, facts.publicUsername)) return Failure();
        if (!Boolean(object, u"protectedContent"_q, facts.protectedContent)) return Failure();
        if (!Boolean(object, u"forum"_q, facts.forum)) return Failure();
        if (!Boolean(object, u"linkedDiscussion"_q, facts.linkedDiscussion)) return Failure();
        if (!Boolean(object, u"autoDelete"_q, facts.autoDelete)) return Failure();
        if (!Boolean(object, u"paidMessages"_q, facts.paidMessages)) return Failure();
        if (!Boolean(object, u"canSendFiles"_q, facts.canSendFiles)) return Failure();
        if (!Boolean(object, u"anonymousAdmin"_q, facts.anonymousAdmin)) return Failure();
        if (!Boolean(object, u"postsAsOther"_q, facts.postsAsOther)) return Failure();
        if (!Boolean(object, u"migrated"_q, facts.migrated)) return Failure();
        if (!Boolean(object, u"slowMode"_q, facts.slowMode)) return Failure();
        if (!Boolean(object, u"admin"_q, facts.admin)) return Failure();
        if (!Boolean(object, u"creator"_q, facts.creator)) return Failure();
        if (!Boolean(object, u"soleAdmin"_q, facts.soleAdmin)) return Failure();
        if (!OptionalCount(object, u"otherMembers"_q, facts.otherMembers) || !OptionalCount(object, u"otherAdmins"_q, facts.otherAdmins)) return Failure();
        const auto verdict = SyncChatEligibility(facts, policy);
        result.insert(u"refusal"_q, Name(verdict.refusal));
    } else {
        return Failure();
    }
    return result;
}

bool String(JNIEnv *env, jstring value, QString &result, int limit) {
    if (!value || env->GetStringLength(value) > limit) return false;
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return false;
    result = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), env->GetStringLength(value));
    env->ReleaseStringChars(value, chars);
    return true;
}

}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleSyncChatCore_requestNative(JNIEnv *env, jclass, jstring operation, jstring input) {
    auto op = QString(); auto text = QString(); auto answer = Failure();
    if (String(env, operation, op, 32) && String(env, input, text, 1024 * 1024)) {
        auto error = QJsonParseError(); const auto document = QJsonDocument::fromJson(text.toUtf8(), &error);
        if (error.error == QJsonParseError::NoError && document.isObject()) answer = Evaluate(op, document.object());
    }
    const auto reply = QString::fromUtf8(QJsonDocument(answer).toJson(QJsonDocument::Compact));
    return env->NewString(reinterpret_cast<const jchar*>(reply.utf16()), reply.size());
}
