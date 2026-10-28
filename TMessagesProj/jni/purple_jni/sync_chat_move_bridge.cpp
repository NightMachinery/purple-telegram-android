#include <jni.h>
#include "purple/purple_sync_chat_journal.h"
#include "purple/purple_sync_json.h"

#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <array>
#include <limits>

namespace {

using namespace Purple;

struct InvalidInput {};

void Require(bool condition) {
    if (!condition) throw InvalidInput();
}

template <typename T> struct EnumValue {
    T value;
    const char16_t *name;
};

template <typename T> EnumValue(T, const char16_t*) -> EnumValue<T>;

template <typename T> const auto &Values();

template <> const auto &Values<SyncCarryMode>() {
    static const auto values = std::array{
        EnumValue{SyncCarryMode::Switch, u"Switch"},
        EnumValue{SyncCarryMode::Follow, u"Follow"},
        EnumValue{SyncCarryMode::Fill, u"Fill"},
    };
    return values;
}

template <> const auto &Values<SyncCarrySourceAccess>() {
    static const auto values = std::array{
        EnumValue{SyncCarrySourceAccess::Unknown, u"Unknown"},
        EnumValue{SyncCarrySourceAccess::Readable, u"Readable"},
        EnumValue{SyncCarrySourceAccess::Protected, u"Protected"},
        EnumValue{SyncCarrySourceAccess::Unreadable, u"Unreadable"},
        EnumValue{SyncCarrySourceAccess::ChatGone, u"ChatGone"},
    };
    return values;
}

template <> const auto &Values<SyncCarryFamily>() {
    static const auto values = std::array{
        EnumValue{SyncCarryFamily::Settings, u"Settings"},
        EnumValue{SyncCarryFamily::Playlists, u"Playlists"},
    };
    return values;
}

template <> const auto &Values<SyncCarryCleanup>() {
    static const auto values = std::array{
        EnumValue{SyncCarryCleanup::None, u"None"},
        EnumValue{SyncCarryCleanup::OwnLatePosts, u"OwnLatePosts"},
        EnumValue{SyncCarryCleanup::AllSettings, u"AllSettings"},
    };
    return values;
}

template <> const auto &Values<SyncCarryStatus>() {
    static const auto values = std::array{
        EnumValue{SyncCarryStatus::Invalid, u"Invalid"},
        EnumValue{SyncCarryStatus::Ready, u"Ready"},
        EnumValue{SyncCarryStatus::ReadSource, u"ReadSource"},
        EnumValue{SyncCarryStatus::ReadDestination, u"ReadDestination"},
        EnumValue{SyncCarryStatus::FinishPendingOld, u"FinishPendingOld"},
        EnumValue{SyncCarryStatus::AbandonPending, u"AbandonPending"},
        EnumValue{SyncCarryStatus::UnknownSource, u"UnknownSource"},
        EnumValue{SyncCarryStatus::MissingLocalVersion, u"MissingLocalVersion"},
        EnumValue{SyncCarryStatus::NeedsReview, u"NeedsReview"},
        EnumValue{SyncCarryStatus::ConsentRequired, u"ConsentRequired"},
        EnumValue{SyncCarryStatus::WrongScope, u"WrongScope"},
        EnumValue{SyncCarryStatus::MissingRecords, u"MissingRecords"},
        EnumValue{SyncCarryStatus::PlaylistRequired, u"PlaylistRequired"},
    };
    return values;
}

template <> const auto &Values<SyncPlaylistCarryStatus>() {
    static const auto values = std::array{
        EnumValue{SyncPlaylistCarryStatus::NotInUse, u"NotInUse"},
        EnumValue{SyncPlaylistCarryStatus::Incomplete, u"Incomplete"},
        EnumValue{SyncPlaylistCarryStatus::Verified, u"Verified"},
        EnumValue{SyncPlaylistCarryStatus::Failed, u"Failed"},
    };
    return values;
}

template <> const auto &Values<SyncCarryPendingStatus>() {
    static const auto values = std::array{
        EnumValue{SyncCarryPendingStatus::Invalid, u"Invalid"},
        EnumValue{SyncCarryPendingStatus::Ready, u"Ready"},
        EnumValue{SyncCarryPendingStatus::NoPending, u"NoPending"},
        EnumValue{SyncCarryPendingStatus::WrongChat, u"WrongChat"},
        EnumValue{SyncCarryPendingStatus::NotChatGone, u"NotChatGone"},
        EnumValue{SyncCarryPendingStatus::LocalCopyRequired, u"LocalCopyRequired"},
        EnumValue{SyncCarryPendingStatus::InvalidStage, u"InvalidStage"},
        EnumValue{SyncCarryPendingStatus::LibraryPending, u"LibraryPending"},
    };
    return values;
}

template <> const auto &Values<SyncCarryPhase>() {
    static const auto values = std::array{
        EnumValue{SyncCarryPhase::ResolvePending, u"ResolvePending"},
        EnumValue{SyncCarryPhase::WritePendingState, u"WritePendingState"},
        EnumValue{SyncCarryPhase::RetirePendingStage, u"RetirePendingStage"},
        EnumValue{SyncCarryPhase::ResolveAgain, u"ResolveAgain"},
        EnumValue{SyncCarryPhase::Plan, u"Plan"},
        EnumValue{SyncCarryPhase::StageOwn, u"StageOwn"},
        EnumValue{SyncCarryPhase::Deliver, u"Deliver"},
        EnumValue{SyncCarryPhase::VerifyDestination, u"VerifyDestination"},
        EnumValue{SyncCarryPhase::Mark, u"Mark"},
        EnumValue{SyncCarryPhase::PrepareCache, u"PrepareCache"},
        EnumValue{SyncCarryPhase::CommitState, u"CommitState"},
        EnumValue{SyncCarryPhase::RetireOwnStage, u"RetireOwnStage"},
        EnumValue{SyncCarryPhase::CompleteCache, u"CompleteCache"},
        EnumValue{SyncCarryPhase::Cleanup, u"Cleanup"},
        EnumValue{SyncCarryPhase::Done, u"Done"},
        EnumValue{SyncCarryPhase::Aborted, u"Aborted"},
    };
    return values;
}

template <> const auto &Values<SyncCarryOutcome>() {
    static const auto values = std::array{
        EnumValue{SyncCarryOutcome::Success, u"Success"},
        EnumValue{SyncCarryOutcome::Failed, u"Failed"},
        EnumValue{SyncCarryOutcome::Unknown, u"Unknown"},
    };
    return values;
}

template <> const auto &Values<SyncCarryJournalAction>() {
    static const auto values = std::array{
        EnumValue{SyncCarryJournalAction::None, u"None"},
        EnumValue{SyncCarryJournalAction::ResolvePending, u"ResolvePending"},
        EnumValue{SyncCarryJournalAction::WritePendingState, u"WritePendingState"},
        EnumValue{SyncCarryJournalAction::RetirePendingStage, u"RetirePendingStage"},
        EnumValue{SyncCarryJournalAction::ResolveAgain, u"ResolveAgain"},
        EnumValue{SyncCarryJournalAction::Plan, u"Plan"},
        EnumValue{SyncCarryJournalAction::StageOwn, u"StageOwn"},
        EnumValue{SyncCarryJournalAction::Deliver, u"Deliver"},
        EnumValue{SyncCarryJournalAction::VerifyDestination, u"VerifyDestination"},
        EnumValue{SyncCarryJournalAction::PostMark, u"PostMark"},
        EnumValue{SyncCarryJournalAction::ReadMark, u"ReadMark"},
        EnumValue{SyncCarryJournalAction::PrepareCache, u"PrepareCache"},
        EnumValue{SyncCarryJournalAction::CommitState, u"CommitState"},
        EnumValue{SyncCarryJournalAction::RetireOwnStage, u"RetireOwnStage"},
        EnumValue{SyncCarryJournalAction::CompleteCache, u"CompleteCache"},
        EnumValue{SyncCarryJournalAction::Cleanup, u"Cleanup"},
    };
    return values;
}

template <> const auto &Values<SyncCarryMarkOutcome>() {
    static const auto values = std::array{
        EnumValue{SyncCarryMarkOutcome::Unknown, u"Unknown"},
        EnumValue{SyncCarryMarkOutcome::Absent, u"Absent"},
        EnumValue{SyncCarryMarkOutcome::Valid, u"Valid"},
        EnumValue{SyncCarryMarkOutcome::ChatGone, u"ChatGone"},
    };
    return values;
}

template <> const auto &Values<SyncCarryEventKind>() {
    static const auto values = std::array{
        EnumValue{SyncCarryEventKind::PendingPrepared, u"PendingPrepared"},
        EnumValue{SyncCarryEventKind::StateObserved, u"StateObserved"},
        EnumValue{SyncCarryEventKind::StageRetired, u"StageRetired"},
        EnumValue{SyncCarryEventKind::Resolution, u"Resolution"},
        EnumValue{SyncCarryEventKind::PlanPrepared, u"PlanPrepared"},
        EnumValue{SyncCarryEventKind::OwnStagePersisted, u"OwnStagePersisted"},
        EnumValue{SyncCarryEventKind::DeliveryPrepared, u"DeliveryPrepared"},
        EnumValue{SyncCarryEventKind::DeliveryResult, u"DeliveryResult"},
        EnumValue{SyncCarryEventKind::DestinationRead, u"DestinationRead"},
        EnumValue{SyncCarryEventKind::MarkPostPrepared, u"MarkPostPrepared"},
        EnumValue{SyncCarryEventKind::MarkPostResult, u"MarkPostResult"},
        EnumValue{SyncCarryEventKind::MarkRead, u"MarkRead"},
        EnumValue{SyncCarryEventKind::CachePrepared, u"CachePrepared"},
        EnumValue{SyncCarryEventKind::CacheCompleted, u"CacheCompleted"},
        EnumValue{SyncCarryEventKind::CleanupCompleted, u"CleanupCompleted"},
        EnumValue{SyncCarryEventKind::Cancel, u"Cancel"},
    };
    return values;
}

template <> const auto &Values<SyncCarryJournalStatus>() {
    static const auto values = std::array{
        EnumValue{SyncCarryJournalStatus::Invalid, u"Invalid"},
        EnumValue{SyncCarryJournalStatus::Valid, u"Valid"},
        EnumValue{SyncCarryJournalStatus::NewerVersion, u"NewerVersion"},
        EnumValue{SyncCarryJournalStatus::Wait, u"Wait"},
        EnumValue{SyncCarryJournalStatus::Retry, u"Retry"},
        EnumValue{SyncCarryJournalStatus::NeedsReview, u"NeedsReview"},
        EnumValue{SyncCarryJournalStatus::Superseded, u"Superseded"},
    };
    return values;
}

template <> const auto &Values<SyncChatMessageOwnership>() {
    static const auto values = std::array{
        EnumValue{SyncChatMessageOwnership::Unknown, u"Unknown"},
        EnumValue{SyncChatMessageOwnership::Own, u"Own"},
        EnumValue{SyncChatMessageOwnership::Other, u"Other"},
    };
    return values;
}

template <> const auto &Values<SyncChatDirectRead>() {
    static const auto values = std::array{
        EnumValue{SyncChatDirectRead::Unknown, u"Unknown"},
        EnumValue{SyncChatDirectRead::Valid, u"Valid"},
        EnumValue{SyncChatDirectRead::MarkGone, u"MarkGone"},
        EnumValue{SyncChatDirectRead::ChatGone, u"ChatGone"},
        EnumValue{SyncChatDirectRead::Migrated, u"Migrated"},
    };
    return values;
}

template <> const auto &Values<SyncChatAction>() {
    static const auto values = std::array{
        EnumValue{SyncChatAction::Invalid, u"Invalid"},
        EnumValue{SyncChatAction::Unset, u"Unset"},
        EnumValue{SyncChatAction::Use, u"Use"},
        EnumValue{SyncChatAction::VerifyMark, u"VerifyMark"},
        EnumValue{SyncChatAction::Follow, u"Follow"},
        EnumValue{SyncChatAction::Fill, u"Fill"},
        EnumValue{SyncChatAction::Searching, u"Searching"},
        EnumValue{SyncChatAction::Lost, u"Lost"},
        EnumValue{SyncChatAction::NewerMark, u"NewerMark"},
        EnumValue{SyncChatAction::ReadWinnerInventory, u"ReadWinnerInventory"},
    };
    return values;
}

template <> const auto &Values<SyncChatRefusal>() {
    static const auto values = std::array{
        EnumValue{SyncChatRefusal::None, u"None"},
        EnumValue{SyncChatRefusal::UnknownKind, u"UnknownKind"},
        EnumValue{SyncChatRefusal::Secret, u"Secret"},
        EnumValue{SyncChatRefusal::TopicRouting, u"TopicRouting"},
        EnumValue{SyncChatRefusal::Public, u"Public"},
        EnumValue{SyncChatRefusal::Protected, u"Protected"},
        EnumValue{SyncChatRefusal::Discussion, u"Discussion"},
        EnumValue{SyncChatRefusal::AutoDelete, u"AutoDelete"},
        EnumValue{SyncChatRefusal::Paid, u"Paid"},
        EnumValue{SyncChatRefusal::CannotSendFiles, u"CannotSendFiles"},
        EnumValue{SyncChatRefusal::SendAsOther, u"SendAsOther"},
        EnumValue{SyncChatRefusal::Migrated, u"Migrated"},
        EnumValue{SyncChatRefusal::SlowMode, u"SlowMode"},
        EnumValue{SyncChatRefusal::BroadcastOwnershipUnselected, u"BroadcastOwnershipUnselected"},
        EnumValue{SyncChatRefusal::BroadcastNeedsCreatorAndSoleAdmin, u"BroadcastNeedsCreatorAndSoleAdmin"},
    };
    return values;
}

template <typename T> QString Name(T value) {
    for (const auto &entry : Values<T>()) {
        if (entry.value == value) return QString::fromUtf16(entry.name);
    }
    throw InvalidInput();
}

template <typename T> T Enum(const QJsonValue &value) {
    Require(value.isString());
    for (const auto &entry : Values<T>()) {
        if (value.toString() == QString::fromUtf16(entry.name)) return entry.value;
    }
    throw InvalidInput();
}

QJsonObject Object(const QJsonValue &value) {
    Require(value.isObject());
    return value.toObject();
}

QString Text(const QJsonValue &value) {
    Require(value.isString());
    return value.toString();
}

bool Flag(const QJsonObject &object, const QString &key) {
    const auto value = object.value(key);
    if (value.isUndefined()) return false;
    Require(value.isBool());
    return value.toBool();
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

QString Blob(const QByteArray &bytes) {
    return QString::fromLatin1(bytes.toBase64());
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

QString PeerText(const PeerToken &peer) {
    const auto text = PeerTokenText(peer);
    Require(text.has_value());
    return *text;
}

std::optional<SyncLocalState> State(const QJsonValue &value) {
    if (value.isNull()) return std::nullopt;
    const auto parsed = ParseSyncLocalState(Bytes(value));
    Require(bool(parsed));
    return parsed.state;
}

QString Image(const SyncLocalState &state) {
    const auto result = SerializeSyncLocalState(state);
    Require(bool(result));
    return Blob(result.canonical);
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

SyncCarryPlan Plan(const QJsonValue &value) {
    const auto result = ParseSyncCarryPlan(Document(Bytes(value)));
    Require(result.has_value());
    return *result;
}

SyncChatCache Cache(const QJsonValue &value, const AccountKey &account) {
    const auto o = Object(value);
    return {Peer(o.value(u"chat"_q), account), Id(o.value(u"id"_q)), Date(o.value(u"date"_q))};
}

SyncChatResolution Resolution(const QJsonValue &value, const AccountKey &account) {
    const auto o = Object(value);
    auto r = SyncChatResolution();
    r.action = Enum<SyncChatAction>(o.value(u"action"_q));
    if (o.contains(u"cache"_q)) r.cache = Cache(o.value(u"cache"_q), account);
    if (o.contains(u"winner"_q)) r.winner = Cache(o.value(u"winner"_q), account);
    if (o.contains(u"source"_q)) r.source = Peer(o.value(u"source"_q), account);
    return r;
}

SyncChatEligibilityResult Eligibility(const QJsonValue &value) {
    const auto o = Object(value);
    auto r = SyncChatEligibilityResult();
    r.refusal = Enum<SyncChatRefusal>(o.value(u"refusal"_q));
    const auto readers = Object(o.value(u"readers"_q));
    for (const auto &key : {u"warningRequired"_q, u"privateRecipient"_q, u"botOwner"_q, u"populationUnknown"_q}) {
        Require(readers.value(key).isBool());
    }
    r.readers.warningRequired = Flag(readers, u"warningRequired"_q);
    r.readers.privateRecipient = Flag(readers, u"privateRecipient"_q);
    r.readers.botOwner = Flag(readers, u"botOwner"_q);
    Require(readers.value(u"populationUnknown"_q).isBool());
    r.readers.populationUnknown = Flag(readers, u"populationUnknown"_q);
    if (readers.contains(u"otherMembers"_q)) r.readers.otherMembers = Counter(readers.value(u"otherMembers"_q));
    if (readers.contains(u"otherAdmins"_q)) r.readers.otherAdmins = Counter(readers.value(u"otherAdmins"_q));
    return r;
}

SyncCarryReference Reference(const QJsonValue &value) {
    const auto o = Object(value);
    const auto account = Account(o.value(u"account"_q));
    const auto hash = Text(o.value(u"hash"_q));
    const auto id = Id(o.value(u"id"_q));
    Require(id > 0 && IsSyncCarryHash(hash));
    return {account, Peer(o.value(u"chat"_q), account), id, hash};
}

QJsonObject Json(const SyncCarryReference &r) {
    return {{u"account"_q, *AccountKeyText(r.account.environment, r.account.userId)},
        {u"chat"_q, PeerText(r.chat)}, {u"id"_q, QString::number(r.messageId)}, {u"hash"_q, r.recordHash}};
}

QJsonArray References(const std::vector<SyncCarryReference> &refs) {
    auto result = QJsonArray();
    for (const auto &ref : refs) result.append(Json(ref));
    return result;
}

SyncCarryMessage Message(const QJsonValue &value) {
    const auto o = Object(value);
    const auto account = Account(o.value(u"account"_q));
    const auto result = MakeSyncCarryMessage(account, Peer(o.value(u"chat"_q), account),
        Id(o.value(u"id"_q)), Date(o.value(u"date"_q)), Bytes(o.value(u"bytes"_q)),
        Enum<SyncChatMessageOwnership>(o.value(u"ownership"_q)), Enum<SyncCarryFamily>(o.value(u"family"_q)));
    Require(result.has_value());
    return *result;
}

SyncCarryInventory Inventory(const QJsonValue &value) {
    const auto o = Object(value);
    auto r = SyncCarryInventory();
    r.account = Account(o.value(u"account"_q));
    r.chat = Peer(o.value(u"chat"_q), r.account);
    Require(o.value(u"complete"_q).isBool());
    r.complete = Flag(o, u"complete"_q);
    Require(o.value(u"messages"_q).isArray());
    const auto messages = o.value(u"messages"_q).toArray();
    Require(messages.size() <= 4096);
    for (const auto &value : messages) {
        auto message = Message(value);
        Require(message.reference.account == r.account && message.reference.chat == r.chat);
        r.messages.push_back(std::move(message));
    }
    return r;
}

SyncPlaylistCarryReceipt Playlist(const QJsonValue &value) {
    if (value.isUndefined()) return {};
    const auto o = Object(value);
    auto r = SyncPlaylistCarryReceipt();
    r.status = Enum<SyncPlaylistCarryStatus>(o.value(u"status"_q));
    r.account = Account(o.value(u"account"_q));
    r.source = Peer(o.value(u"source"_q), r.account);
    r.target = Peer(o.value(u"target"_q), r.account);
    r.planStamp = Text(o.value(u"planStamp"_q));
    r.destinationStamp = Text(o.value(u"destinationStamp"_q));
    return r;
}

SyncCarryMarkReadback Mark(const QJsonValue &value) {
    const auto o = Object(value);
    auto r = SyncCarryMarkReadback();
    r.outcome = Enum<SyncCarryMarkOutcome>(o.value(u"outcome"_q));
    r.account = Account(o.value(u"account"_q));
    r.chat = Peer(o.value(u"chat"_q), r.account);
    r.messageId = Id(o.value(u"id"_q));
    r.date = Date(o.value(u"date"_q));
    r.text = Text(o.value(u"text"_q));
    Require(o.value(u"hasMedia"_q).isBool());
    r.hasMedia = Flag(o, u"hasMedia"_q);
    r.ownership = Enum<SyncChatMessageOwnership>(o.value(u"ownership"_q));
    return r;
}

SyncCarryCacheReceipt Receipt(const QJsonValue &value) {
    const auto o = Object(value);
    auto r = SyncCarryCacheReceipt();
    r.account = Account(o.value(u"account"_q));
    r.operationId = Text(o.value(u"operation"_q));
    r.mark = Cache(o.value(u"mark"_q), r.account);
    Require(o.value(u"complete"_q).isBool());
    r.complete = Flag(o, u"complete"_q);
    return r;
}

SyncCarryInput Input(const QJsonObject &o) {
    auto r = SyncCarryInput();
    r.account = Account(o.value(u"account"_q));
    r.source = Peer(o.value(u"source"_q), r.account);
    r.target = Peer(o.value(u"target"_q), r.account);
    r.mode = Enum<SyncCarryMode>(o.value(u"mode"_q));
    r.access = Enum<SyncCarrySourceAccess>(o.value(u"access"_q));
    r.targetEligibility = Eligibility(o.value(u"eligibility"_q));
    r.readerConsentAccepted = Flag(o, u"readerConsentAccepted"_q);
    r.state = State(o.value(u"state"_q));
    r.preferenceToken = Bytes(o.value(u"preferenceToken"_q));
    Require(o.value(u"otherStagesPresent"_q).isBool());
    r.otherStagesPresent = Flag(o, u"otherStagesPresent"_q);
    r.oldInventory = Inventory(o.value(u"oldInventory"_q));
    r.newInventory = Inventory(o.value(u"newInventory"_q));
    if (o.contains(u"ownVersion"_q)) {
        const auto v = Object(o.value(u"ownVersion"_q));
        auto own = SyncCarryOwnVersion();
        own.text = Bytes(v.value(u"text"_q));
        own.version.key = Text(v.value(u"key"_q));
        for (const auto &pair : {std::pair(u"parents"_q, &own.version.parents), std::pair(u"lineage"_q, &own.version.lineage)}) {
            Require(v.value(pair.first).isArray());
            for (const auto &item : v.value(pair.first).toArray()) pair.second->push_back(Text(item));
        }
        own.platform = Text(v.value(u"platform"_q));
        own.app = Text(v.value(u"app"_q));
        own.at = Counter(v.value(u"at"_q));
        r.ownVersion = std::move(own);
    }
    if (o.contains(u"winner"_q)) r.winner = Cache(o.value(u"winner"_q), r.account);
    r.cleanup = Enum<SyncCarryCleanup>(o.value(u"cleanup"_q));
    r.cleanupAuthorized = Flag(o, u"cleanupAuthorized"_q);
    r.playlistsInUse = Flag(o, u"playlistsInUse"_q);
    return r;
}

SyncCarryJournalStart Start(const QJsonObject &o) {
    auto r = SyncCarryJournalStart();
    r.account = Account(o.value(u"account"_q));
    r.source = Peer(o.value(u"source"_q), r.account);
    r.target = Peer(o.value(u"target"_q), r.account);
    r.mode = Enum<SyncCarryMode>(o.value(u"mode"_q));
    r.operationId = Text(o.value(u"operation"_q));
    r.state = State(o.value(u"state"_q));
    r.stateAbsentConfirmed = Flag(o, u"stateAbsentConfirmed"_q);
    r.preferenceToken = Bytes(o.value(u"preferenceToken"_q));
    Require(o.value(u"otherStagesPresent"_q).isBool());
    r.otherStagesPresent = Flag(o, u"otherStagesPresent"_q);
    r.resolution = Resolution(o.value(u"resolution"_q), r.account);
    r.targetEligibility = Eligibility(o.value(u"eligibility"_q));
    r.readerConsentAccepted = Flag(o, u"readerConsentAccepted"_q);
    r.operationConfirmed = Flag(o, u"operationConfirmed"_q);
    r.markText = Text(o.value(u"markText"_q));
    r.markRequestId = Text(o.value(u"markRequestId"_q));
    r.ownRequestId = Text(o.value(u"ownRequestId"_q));
    r.removeOldMarkAuthorized = Flag(o, u"removeOldMarkAuthorized"_q);
    return r;
}

SyncCarryJournalEvent Event(const QJsonObject &o) {
    auto r = SyncCarryJournalEvent();
    r.operationId = Text(o.value(u"operation"_q));
    r.revision = Counter(o.value(u"revision"_q));
    r.account = Account(o.value(u"account"_q));
    r.source = Peer(o.value(u"source"_q), r.account);
    r.target = Peer(o.value(u"target"_q), r.account);
    r.kind = Enum<SyncCarryEventKind>(o.value(u"kind"_q));
    r.outcome = Enum<SyncCarryOutcome>(o.value(u"outcome"_q));
    r.objectsDurable = Flag(o, u"objectsDurable"_q);
    if (o.contains(u"state"_q)) r.state = State(o.value(u"state"_q));
    r.stateAbsentConfirmed = Flag(o, u"stateAbsentConfirmed"_q);
    if (o.contains(u"stagedRecord"_q)) r.stagedRecord = Bytes(o.value(u"stagedRecord"_q));
    if (o.contains(u"retainedLocalText"_q)) r.retainedLocalText = Bytes(o.value(u"retainedLocalText"_q));
    if (o.contains(u"oldRead"_q)) r.oldRead = Enum<SyncChatDirectRead>(o.value(u"oldRead"_q));
    if (o.contains(u"pendingReadback"_q)) r.pendingReadback = Message(o.value(u"pendingReadback"_q));
    if (o.contains(u"preparedImageHash"_q)) r.preparedImageHash = Text(o.value(u"preparedImageHash"_q));
    if (o.contains(u"resolution"_q)) r.resolution = Resolution(o.value(u"resolution"_q), r.account);
    if (o.contains(u"plan"_q)) r.plan = Plan(o.value(u"plan"_q));
    if (o.contains(u"destination"_q)) r.destination = Inventory(o.value(u"destination"_q));
    r.playlist = Playlist(o.value(u"playlist"_q));
    if (o.contains(u"mark"_q)) r.mark = Mark(o.value(u"mark"_q));
    if (o.contains(u"cache"_q)) r.cache = Receipt(o.value(u"cache"_q));
    if (o.contains(u"retiredStageHash"_q)) r.retiredStageHash = Text(o.value(u"retiredStageHash"_q));
    if (o.contains(u"deleted"_q)) {
        Require(o.value(u"deleted"_q).isArray());
        for (const auto &ref : o.value(u"deleted"_q).toArray()) r.deleted.push_back(Reference(ref));
    }
    r.noRequestInFlight = Flag(o, u"noRequestInFlight"_q);
    r.noPendingFiles = Flag(o, u"noPendingFiles"_q);
    return r;
}

void Scope(const QJsonObject &o, const AccountKey &account, const PeerToken &source, const PeerToken &target) {
    Require(Account(o.value(u"account"_q)) == account);
    Require(Peer(o.value(u"source"_q), account) == source);
    Require(Peer(o.value(u"target"_q), account) == target);
}

QJsonObject JournalResult(const SyncCarryJournalResult &r) {
    auto result = QJsonObject{{u"status"_q, Name(r.status)}, {u"canonical"_q, Blob(r.canonical)}};
    if (!r.canonical.isEmpty()) {
        const auto parsed = ParseSyncCarryJournal(r.canonical);
        Require(bool(parsed));
        result.insert(u"nextAction"_q, Name(NextSyncCarryJournalAction(parsed.journal)));
        result.insert(u"phase"_q, Name(parsed.journal.phase));
        result.insert(u"revision"_q, QString::number(parsed.journal.revision));
    }
    return result;
}

QJsonObject Pending(const SyncCarryPendingResult &r) {
    auto result = QJsonObject{{u"status"_q, Name(r.status)}, {u"beforeHash"_q, r.beforeHash},
        {u"stageHash"_q, r.stageHash}, {u"stageSeq"_q, QString::number(r.stageSeq)}};
    if (r) result.insert(u"state"_q, Image(r.state));
    return result;
}

QJsonObject Evaluate(const QString &op, const QJsonObject &o) {
    if (op == u"hash"_q) return {{u"status"_q, u"Ready"_q}, {u"hash"_q, SyncCarryRecordHash(Bytes(o.value(u"bytes"_q)))}};
    if (op == u"message"_q) {
        const auto m = Message(o);
        return {{u"status"_q, u"Ready"_q}, {u"reference"_q, Json(m.reference)}};
    }
    if (op == u"select"_q) {
        const auto r = SelectSyncCarryHeads(Inventory(o.value(u"inventory"_q)));
        auto records = QJsonArray();
        auto heads = QJsonArray();
        auto hashes = QJsonArray();
        auto selected = SyncCarryPlan();
        selected.selected = r.records;
        records = SyncCarryPlanJson(selected).value(u"selected"_q).toArray();
        selected.selected = r.heads;
        heads = SyncCarryPlanJson(selected).value(u"selected"_q).toArray();
        for (const auto &h : r.hashes) hashes.append(h);
        return {{u"status"_q, Name(r.status)}, {u"records"_q, records}, {u"heads"_q, heads},
            {u"hashes"_q, hashes}, {u"playlists"_q, r.playlists}, {u"stamp"_q, r.stamp}};
    }
    if (op == u"plan"_q) {
        const auto r = PlanCarry(Input(o));
        auto result = QJsonObject{{u"status"_q, Name(r.status)}};
        if (r) result.insert(u"plan"_q, Blob(Canonical(SyncCarryPlanJson(r.plan))));
        if (r.reservedState) result.insert(u"state"_q, Image(*r.reservedState));
        return result;
    }
    if (op == u"begin"_q) return JournalResult(BeginSyncCarryJournal(Start(o)));
    if (op == u"finishPending"_q || op == u"abandonPending"_q) {
        const auto state = State(o.value(u"state"_q));
        Require(state.has_value());
        const auto account = Account(o.value(u"account"_q));
        if (op == u"finishPending"_q) return Pending(FinishPendingStageForCarry(account, *state,
            Bytes(o.value(u"stagedRecord"_q)), Message(o.value(u"readback"_q))));
        return Pending(AbandonStageForLostChat(account, *state, Peer(o.value(u"source"_q), account),
            Enum<SyncChatDirectRead>(o.value(u"oldRead"_q)), Bytes(o.value(u"stagedRecord"_q)), Bytes(o.value(u"retainedLocalText"_q))));
    }
    if (o.contains(u"journal"_q)) {
        const auto parsed = ParseSyncCarryJournal(Bytes(o.value(u"journal"_q)));
        if (!parsed) return JournalResult(parsed);
        const auto &j = parsed.journal;
        Scope(o, j.account, j.source, j.target);
        if (op == u"parse"_q || op == u"serialize"_q || op == u"next"_q) return JournalResult(SerializeSyncCarryJournal(j));
        if (op == u"event"_q) {
            const auto e = SyncCarryEvent(j, Enum<SyncCarryEventKind>(o.value(u"kind"_q)));
            return {{u"status"_q, u"Valid"_q}, {u"event"_q, QJsonObject{
                {u"operation"_q, e.operationId}, {u"account"_q, *AccountKeyText(e.account.environment, e.account.userId)},
                {u"source"_q, PeerText(e.source)}, {u"target"_q, PeerText(e.target)},
                {u"revision"_q, QString::number(e.revision)}, {u"kind"_q, Name(e.kind)}, {u"outcome"_q, Name(e.outcome)}}}};
        }
        if (op == u"advance"_q) return JournalResult(AdvanceSyncCarryJournal(j, Event(Object(o.value(u"event"_q)))));
        if (op == u"resume"_q) return JournalResult(ResumeSyncCarryJournal(j, State(o.value(u"state"_q)),
            Flag(o, u"stateAbsentConfirmed"_q), Flag(o, u"stateDurableConfirmed"_q)));
        if (op == u"commands"_q) return {{u"status"_q, u"Valid"_q},
            {u"forwards"_q, References(SyncCarryJournalForwards(j))}, {u"postsOwn"_q, SyncCarryJournalPostsOwnRecord(j)}};
        if (op == u"checkDeletion"_q || op == u"checkOldMarkDeletion"_q) {
            const auto destination = Inventory(o.value(u"destination"_q));
            const auto playlist = Playlist(o.value(u"playlist"_q));
            const auto allowed = op == u"checkDeletion"_q
                ? CheckSyncCarryDeletion(j, Message(o.value(u"message"_q)), destination, playlist)
                : CheckSyncCarryOldMarkDeletion(j, Mark(o.value(u"mark"_q)), destination, playlist);
            return {{u"status"_q, u"Valid"_q}, {u"allowed"_q, allowed}};
        }
        throw InvalidInput();
    }
    const auto plan = Plan(o.value(u"plan"_q));
    Scope(o, plan.account, plan.source, plan.target);
    if (op == u"parsePlan"_q) return {{u"status"_q, u"Ready"_q},
        {u"plan"_q, Blob(Canonical(SyncCarryPlanJson(plan)))}, {u"stamp"_q, SyncCarryPlanStamp(plan)}};
    if (op == u"usesState"_q || op == u"prepareStage"_q) {
        const auto state = State(o.value(u"state"_q));
        if (op == u"usesState"_q) return {{u"status"_q, u"Ready"_q}, {u"usesState"_q, SyncCarryPlanUsesState(plan, state)}};
        Require(state.has_value());
        const auto prepared = PrepareSyncCarryStage(plan, *state);
        return prepared ? QJsonObject{{u"status"_q, u"Ready"_q}, {u"state"_q, Image(*prepared)}}
            : QJsonObject{{u"status"_q, u"Invalid"_q}};
    }
    const auto destination = Inventory(o.value(u"destination"_q));
    const auto playlist = Playlist(o.value(u"playlist"_q));
    if (op == u"verify"_q) {
        const auto r = VerifySyncCarryReadback(plan, destination, playlist);
        auto missing = QJsonArray();
        for (const auto &h : r.missingHashes) missing.append(h);
        return {{u"status"_q, Name(r.status)}, {u"missing"_q, missing}, {u"destinationStamp"_q, r.destinationStamp}};
    }
    if (op == u"prepareCommit"_q) {
        const auto r = PrepareSyncCarryCommit(plan, State(o.value(u"state"_q)), destination, playlist);
        auto result = QJsonObject{{u"status"_q, Name(r.status)}, {u"canonical"_q, Blob(r.canonical)}, {u"stateHash"_q, r.stateHash}};
        return result;
    }
    if (op == u"cleanupCandidates"_q) return {{u"status"_q, u"Ready"_q}, {u"references"_q, References(SyncCarryCleanupCandidates(plan, destination, playlist))}};
    if (op == u"sourceMatchesCleanup"_q) return {{u"status"_q, u"Ready"_q},
        {u"allowed"_q, SyncCarrySourceMatchesCleanup(plan, Message(o.value(u"message"_q)), destination, playlist)}};
    throw InvalidInput();
}

}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleSyncChatMoveCore_requestNative(
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
