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

QString Name(SyncChatMarkStatus value) {
	switch (value) {
	case SyncChatMarkStatus::NotMark: return u"NotMark"_q;
	case SyncChatMarkStatus::Supported: return u"Supported"_q;
	case SyncChatMarkStatus::NewerVersion: return u"NewerVersion"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatMarkStatus &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"NotMark"_q) { result = SyncChatMarkStatus::NotMark; return true; }
	if (text == u"Supported"_q) { result = SyncChatMarkStatus::Supported; return true; }
	if (text == u"NewerVersion"_q) { result = SyncChatMarkStatus::NewerVersion; return true; }
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

QString Name(SyncChatRecordPresence value) {
	switch (value) {
	case SyncChatRecordPresence::Unknown: return u"Unknown"_q;
	case SyncChatRecordPresence::Empty: return u"Empty"_q;
	case SyncChatRecordPresence::Present: return u"Present"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatRecordPresence &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Unknown"_q) { result = SyncChatRecordPresence::Unknown; return true; }
	if (text == u"Empty"_q) { result = SyncChatRecordPresence::Empty; return true; }
	if (text == u"Present"_q) { result = SyncChatRecordPresence::Present; return true; }
	return false;
}

QString Name(SyncChatDirectRead value) {
	switch (value) {
	case SyncChatDirectRead::Unknown: return u"Unknown"_q;
	case SyncChatDirectRead::Valid: return u"Valid"_q;
	case SyncChatDirectRead::MarkGone: return u"MarkGone"_q;
	case SyncChatDirectRead::ChatGone: return u"ChatGone"_q;
	case SyncChatDirectRead::Migrated: return u"Migrated"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatDirectRead &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Unknown"_q) { result = SyncChatDirectRead::Unknown; return true; }
	if (text == u"Valid"_q) { result = SyncChatDirectRead::Valid; return true; }
	if (text == u"MarkGone"_q) { result = SyncChatDirectRead::MarkGone; return true; }
	if (text == u"ChatGone"_q) { result = SyncChatDirectRead::ChatGone; return true; }
	if (text == u"Migrated"_q) { result = SyncChatDirectRead::Migrated; return true; }
	return false;
}

QString Name(SyncChatSettingsHint value) {
	switch (value) {
	case SyncChatSettingsHint::NotRun: return u"NotRun"_q;
	case SyncChatSettingsHint::Unknown: return u"Unknown"_q;
	case SyncChatSettingsHint::Absent: return u"Absent"_q;
	case SyncChatSettingsHint::Found: return u"Found"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatSettingsHint &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"NotRun"_q) { result = SyncChatSettingsHint::NotRun; return true; }
	if (text == u"Unknown"_q) { result = SyncChatSettingsHint::Unknown; return true; }
	if (text == u"Absent"_q) { result = SyncChatSettingsHint::Absent; return true; }
	if (text == u"Found"_q) { result = SyncChatSettingsHint::Found; return true; }
	return false;
}

QString Name(SyncChatAction value) {
	switch (value) {
	case SyncChatAction::Invalid: return u"Invalid"_q;
	case SyncChatAction::Unset: return u"Unset"_q;
	case SyncChatAction::Use: return u"Use"_q;
	case SyncChatAction::VerifyMark: return u"VerifyMark"_q;
	case SyncChatAction::Follow: return u"Follow"_q;
	case SyncChatAction::Fill: return u"Fill"_q;
	case SyncChatAction::Searching: return u"Searching"_q;
	case SyncChatAction::Lost: return u"Lost"_q;
	case SyncChatAction::NewerMark: return u"NewerMark"_q;
	case SyncChatAction::ReadWinnerInventory: return u"ReadWinnerInventory"_q;
	}
	return QString();
}

bool Read(const QJsonValue &value, SyncChatAction &result) {
	if (!value.isString()) return false;
	const auto text = value.toString();
	if (text == u"Invalid"_q) { result = SyncChatAction::Invalid; return true; }
	if (text == u"Unset"_q) { result = SyncChatAction::Unset; return true; }
	if (text == u"Use"_q) { result = SyncChatAction::Use; return true; }
	if (text == u"VerifyMark"_q) { result = SyncChatAction::VerifyMark; return true; }
	if (text == u"Follow"_q) { result = SyncChatAction::Follow; return true; }
	if (text == u"Fill"_q) { result = SyncChatAction::Fill; return true; }
	if (text == u"Searching"_q) { result = SyncChatAction::Searching; return true; }
	if (text == u"Lost"_q) { result = SyncChatAction::Lost; return true; }
	if (text == u"NewerMark"_q) { result = SyncChatAction::NewerMark; return true; }
	if (text == u"ReadWinnerInventory"_q) { result = SyncChatAction::ReadWinnerInventory; return true; }
	return false;
}

bool Decimal(const QJsonValue &value, int64_t &result) {
    if (!value.isString()) return false;
    const auto text = value.toString();
    auto ok = false;
    result = text.toLongLong(&ok);
    return ok && result >= 0 && QString::number(result) == text;
}

bool Number(const QJsonValue &value, int &result) {
    if (!value.isDouble()) return false;
    const auto number = value.toDouble();
    if (number < 0 || number > std::numeric_limits<int>::max() || int(number) != number) return false;
    result = int(number);
    return true;
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

bool Cache(const QJsonValue &value, SyncChatCache &result) {
    if (!value.isObject()) return false;
    const auto object = value.toObject();
    return Peer(object.value(u"chat"_q), result.chat)
        && Number(object.value(u"markId"_q), result.markId)
        && Decimal(object.value(u"markDate"_q), result.markDate);
}

QJsonObject CacheJson(const SyncChatCache &value) {
    return { {u"chat"_q, *PeerTokenText(value.chat)}, {u"markId"_q, value.markId}, {u"markDate"_q, QString::number(value.markDate)} };
}

QJsonObject ReadersJson(const SyncChatReaderFacts &value) {
    auto result = QJsonObject{{u"warningRequired"_q, value.warningRequired}, {u"privateRecipient"_q, value.privateRecipient},
        {u"botOwner"_q, value.botOwner}, {u"populationUnknown"_q, value.populationUnknown}};
    if (value.otherMembers) result.insert(u"otherMembers"_q, QString::number(*value.otherMembers));
    if (value.otherAdmins) result.insert(u"otherAdmins"_q, QString::number(*value.otherAdmins));
    return result;
}

bool Eligibility(const QJsonValue &value, SyncChatEligibilityResult &result) {
    if (!value.isObject()) return false;
    const auto object = value.toObject();
    if (!Read(object.value(u"refusal"_q), result.refusal)) return false;
    if (result.refusal == SyncChatRefusal::UnknownKind) { result.readers.populationUnknown = true; return true; }
    if (!object.value(u"readers"_q).isObject()) return false;
    const auto readers = object.value(u"readers"_q).toObject();
    return Boolean(readers, u"warningRequired"_q, result.readers.warningRequired)
        && Boolean(readers, u"privateRecipient"_q, result.readers.privateRecipient)
        && Boolean(readers, u"botOwner"_q, result.readers.botOwner)
        && Boolean(readers, u"populationUnknown"_q, result.readers.populationUnknown)
        && OptionalCount(readers, u"otherMembers"_q, result.readers.otherMembers)
        && OptionalCount(readers, u"otherAdmins"_q, result.readers.otherAdmins);
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
    } else if (operation == u"parse"_q) {
        auto ownership = SyncChatMessageOwnership::Unknown; auto media = false;
        if (!object.value(u"text"_q).isString() || !Boolean(object, u"hasMedia"_q, media)
            || !Read(object.value(u"ownership"_q), ownership)) return Failure();
        const auto parsed = ParseSyncChatMark(object.value(u"text"_q).toString(), media, ownership);
        result.insert(u"status"_q, Name(parsed.status)); result.insert(u"version"_q, parsed.version);
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
        result.insert(u"refusal"_q, Name(verdict.refusal)); result.insert(u"readers"_q, ReadersJson(verdict.readers));
    } else if (operation == u"outranks"_q) {
        auto first = SyncChatCache(); auto second = SyncChatCache();
        if (!Cache(object.value(u"first"_q), first) || !Cache(object.value(u"second"_q), second)) return Failure();
        result.insert(u"outranks"_q, SyncChatMarkOutranks(first, second));
    } else if (operation == u"resolve"_q) {
        auto input = SyncChatResolveInput(); auto self = int64_t(0);
        if (!Decimal(object.value(u"selfUserId"_q), self) || self == 0
            || !Boolean(object, u"settingsBound"_q, input.settingsBound)
            || !Peer(object.value(u"settingsChat"_q), input.settingsChat)
            || !Read(object.value(u"cacheRead"_q), input.cacheRead)
            || !Read(object.value(u"settingsHint"_q), input.settingsHint)
            || !Boolean(object, u"readerWarningAccepted"_q, input.readerWarningAccepted)
            || !Number(object.value(u"searchRetriesCompleted"_q), input.searchRetriesCompleted)
            || !Eligibility(object.value(u"cachedEligibility"_q), input.cachedEligibility)) return Failure();
        input.selfUserId = uint64_t(self);
        if (object.contains(u"cache"_q)) { auto cache = SyncChatCache(); if (!Cache(object.value(u"cache"_q), cache)) return Failure(); input.cache = cache; }
        if (!object.value(u"marks"_q).isArray()) return Failure();
        const auto marks = object.value(u"marks"_q).toArray();
        if (marks.size() > 4096) return Failure();
        for (const auto &value : marks) {
            if (!value.isObject()) return Failure();
            const auto row = value.toObject(); auto mark = SyncChatMark();
            if (!Cache(row.value(u"location"_q), mark.location) || !Read(row.value(u"status"_q), mark.status)
                || !Eligibility(row.value(u"eligibility"_q), mark.eligibility) || !Read(row.value(u"records"_q), mark.records)
                || !Boolean(row, u"readable"_q, mark.readable) || !Boolean(row, u"freshlyRead"_q, mark.freshlyRead)) return Failure();
            input.marks.push_back(mark);
        }
        const auto resolved = ResolveSyncChat(input);
        result.insert(u"action"_q, Name(resolved.action));
        if (resolved.cache) result.insert(u"cache"_q, CacheJson(*resolved.cache));
        if (resolved.winner) result.insert(u"winner"_q, CacheJson(*resolved.winner));
        if (resolved.readChat) result.insert(u"readChat"_q, *PeerTokenText(*resolved.readChat));
        if (resolved.source) result.insert(u"source"_q, *PeerTokenText(*resolved.source));
        if (resolved.olderAlternative) result.insert(u"olderAlternative"_q, *PeerTokenText(*resolved.olderAlternative));
        result.insert(u"readers"_q, ReadersJson(resolved.readers));
        result.insert(u"needsReaderWarning"_q, resolved.needsReaderWarning); result.insert(u"postingHeld"_q, resolved.postingHeld);
        result.insert(u"retryAfterSeconds"_q, resolved.retryAfterSeconds);
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
