#include <jni.h>
#include "purple/purple_playlists_step.h"
#include "purple/purple_sync_json.h"
#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <limits>
#include <stdexcept>

namespace {
using namespace Purple;
QString Name(PlaylistStepStatus value) {
    switch (value) {
    case PlaylistStepStatus::Blocked: return QString::fromLatin1("Blocked");
    case PlaylistStepStatus::Skipped: return QString::fromLatin1("Skipped");
    case PlaylistStepStatus::Planned: return QString::fromLatin1("Planned");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistStepError value) {
    switch (value) {
    case PlaylistStepError::None: return QString::fromLatin1("None");
    case PlaylistStepError::Unauthorized: return QString::fromLatin1("Unauthorized");
    case PlaylistStepError::InvalidSelfUserId: return QString::fromLatin1("InvalidSelfUserId");
    case PlaylistStepError::InvalidTrigger: return QString::fromLatin1("InvalidTrigger");
    case PlaylistStepError::LibraryReadFailed: return QString::fromLatin1("LibraryReadFailed");
    case PlaylistStepError::InvalidLibrary: return QString::fromLatin1("InvalidLibrary");
    case PlaylistStepError::NewerLibrary: return QString::fromLatin1("NewerLibrary");
    case PlaylistStepError::StateReadFailed: return QString::fromLatin1("StateReadFailed");
    case PlaylistStepError::InvalidState: return QString::fromLatin1("InvalidState");
    case PlaylistStepError::NewerState: return QString::fromLatin1("NewerState");
    case PlaylistStepError::InvalidInventory: return QString::fromLatin1("InvalidInventory");
    case PlaylistStepError::AccountMismatch: return QString::fromLatin1("AccountMismatch");
    case PlaylistStepError::MergeFailed: return QString::fromLatin1("MergeFailed");
    case PlaylistStepError::DiffFailed: return QString::fromLatin1("DiffFailed");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistStepPublication value) {
    switch (value) {
    case PlaylistStepPublication::NotPlanned: return QString::fromLatin1("NotPlanned");
    case PlaylistStepPublication::SyncOff: return QString::fromLatin1("SyncOff");
    case PlaylistStepPublication::DiscoveryUnavailable: return QString::fromLatin1("DiscoveryUnavailable");
    case PlaylistStepPublication::InitialEmpty: return QString::fromLatin1("InitialEmpty");
    case PlaylistStepPublication::EqualRecord: return QString::fromLatin1("EqualRecord");
    case PlaylistStepPublication::Required: return QString::fromLatin1("Required");
    case PlaylistStepPublication::ExpandedLimit: return QString::fromLatin1("ExpandedLimit");
    case PlaylistStepPublication::CompressedLimit: return QString::fromLatin1("CompressedLimit");
    case PlaylistStepPublication::EncodingFailed: return QString::fromLatin1("EncodingFailed");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistInventoryStatus value) {
    switch (value) {
    case PlaylistInventoryStatus::Valid: return QString::fromLatin1("Valid");
    case PlaylistInventoryStatus::InvalidInput: return QString::fromLatin1("InvalidInput");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistInventoryError value) {
    switch (value) {
    case PlaylistInventoryError::None: return QString::fromLatin1("None");
    case PlaylistInventoryError::InvalidSelfUserId: return QString::fromLatin1("InvalidSelfUserId");
    case PlaylistInventoryError::InvalidCurrentChat: return QString::fromLatin1("InvalidCurrentChat");
    case PlaylistInventoryError::InvalidPriorChat: return QString::fromLatin1("InvalidPriorChat");
    case PlaylistInventoryError::MissingPriorChat: return QString::fromLatin1("MissingPriorChat");
    case PlaylistInventoryError::InvalidMemo: return QString::fromLatin1("InvalidMemo");
    case PlaylistInventoryError::InvalidMessageId: return QString::fromLatin1("InvalidMessageId");
    case PlaylistInventoryError::InvalidDocumentId: return QString::fromLatin1("InvalidDocumentId");
    case PlaylistInventoryError::InvalidByIdOutcome: return QString::fromLatin1("InvalidByIdOutcome");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistAutoPurgeStatus value) {
    switch (value) {
    case PlaylistAutoPurgeStatus::Completed: return QString::fromLatin1("Completed");
    case PlaylistAutoPurgeStatus::InvalidLibrary: return QString::fromLatin1("InvalidLibrary");
    case PlaylistAutoPurgeStatus::NewerFormatLibrary: return QString::fromLatin1("NewerFormatLibrary");
    case PlaylistAutoPurgeStatus::BlockedServerRequestRequired: return QString::fromLatin1("BlockedServerRequestRequired");
    case PlaylistAutoPurgeStatus::BlockedIncompleteDiscovery: return QString::fromLatin1("BlockedIncompleteDiscovery");
    case PlaylistAutoPurgeStatus::BlockedNewerFormatRecord: return QString::fromLatin1("BlockedNewerFormatRecord");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordPruneStatus value) {
    switch (value) {
    case PlaylistRecordPruneStatus::InvalidInput: return QString::fromLatin1("InvalidInput");
    case PlaylistRecordPruneStatus::Skipped: return QString::fromLatin1("Skipped");
    case PlaylistRecordPruneStatus::Planned: return QString::fromLatin1("Planned");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordPruneError value) {
    switch (value) {
    case PlaylistRecordPruneError::None: return QString::fromLatin1("None");
    case PlaylistRecordPruneError::InvalidChat: return QString::fromLatin1("InvalidChat");
    case PlaylistRecordPruneError::InvalidInventory: return QString::fromLatin1("InvalidInventory");
    case PlaylistRecordPruneError::BindingMismatch: return QString::fromLatin1("BindingMismatch");
    case PlaylistRecordPruneError::InvalidIdentity: return QString::fromLatin1("InvalidIdentity");
    case PlaylistRecordPruneError::ConflictingPresence: return QString::fromLatin1("ConflictingPresence");
    case PlaylistRecordPruneError::ContainmentFailed: return QString::fromLatin1("ContainmentFailed");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordUnusableReason value) {
    switch (value) {
    case PlaylistRecordUnusableReason::ExcludedPresence: return QString::fromLatin1("ExcludedPresence");
    case PlaylistRecordUnusableReason::UnrecognizedRecord: return QString::fromLatin1("UnrecognizedRecord");
    case PlaylistRecordUnusableReason::MissingPayload: return QString::fromLatin1("MissingPayload");
    case PlaylistRecordUnusableReason::IdentityMismatch: return QString::fromLatin1("IdentityMismatch");
    case PlaylistRecordUnusableReason::ConflictingPayload: return QString::fromLatin1("ConflictingPayload");
    case PlaylistRecordUnusableReason::UnprovenPayload: return QString::fromLatin1("UnprovenPayload");
    case PlaylistRecordUnusableReason::ReadFailed: return QString::fromLatin1("ReadFailed");
    case PlaylistRecordUnusableReason::PublicationUnacknowledged: return QString::fromLatin1("PublicationUnacknowledged");
    case PlaylistRecordUnusableReason::InvalidCacheHash: return QString::fromLatin1("InvalidCacheHash");
    case PlaylistRecordUnusableReason::CacheHashMismatch: return QString::fromLatin1("CacheHashMismatch");
    case PlaylistRecordUnusableReason::InvalidPayload: return QString::fromLatin1("InvalidPayload");
    case PlaylistRecordUnusableReason::NewerFormat: return QString::fromLatin1("NewerFormat");
    case PlaylistRecordUnusableReason::Oversized: return QString::fromLatin1("Oversized");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistNoticeReason value) {
    switch (value) {
    case PlaylistNoticeReason::DeletedElsewhere: return QString::fromLatin1("DeletedElsewhere");
    case PlaylistNoticeReason::PurgedElsewhere: return QString::fromLatin1("PurgedElsewhere");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistNoticeAction value) {
    switch (value) {
    case PlaylistNoticeAction::Restore: return QString::fromLatin1("Restore");
    case PlaylistNoticeAction::KeepCopy: return QString::fromLatin1("KeepCopy");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordCandidateVerdict value) {
    switch (value) {
    case PlaylistRecordCandidateVerdict::Candidate: return QString::fromLatin1("Candidate");
    case PlaylistRecordCandidateVerdict::UnknownOwnership: return QString::fromLatin1("UnknownOwnership");
    case PlaylistRecordCandidateVerdict::AmbiguousSettings: return QString::fromLatin1("AmbiguousSettings");
    case PlaylistRecordCandidateVerdict::Ignored: return QString::fromLatin1("Ignored");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordStatus value) {
    switch (value) {
    case PlaylistRecordStatus::Valid: return QString::fromLatin1("Valid");
    case PlaylistRecordStatus::Invalid: return QString::fromLatin1("Invalid");
    case PlaylistRecordStatus::NewerFormat: return QString::fromLatin1("NewerFormat");
    case PlaylistRecordStatus::Oversized: return QString::fromLatin1("Oversized");
    case PlaylistRecordStatus::Incomplete: return QString::fromLatin1("Incomplete");
    case PlaylistRecordStatus::Ignored: return QString::fromLatin1("Ignored");
    case PlaylistRecordStatus::InvalidInput: return QString::fromLatin1("InvalidInput");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistRecordError value) {
    switch (value) {
    case PlaylistRecordError::None: return QString::fromLatin1("None");
    case PlaylistRecordError::InvalidMessageId: return QString::fromLatin1("InvalidMessageId");
    case PlaylistRecordError::InvalidDocumentId: return QString::fromLatin1("InvalidDocumentId");
    case PlaylistRecordError::InvalidSelfUserId: return QString::fromLatin1("InvalidSelfUserId");
    case PlaylistRecordError::UnknownOwnership: return QString::fromLatin1("UnknownOwnership");
    case PlaylistRecordError::AmbiguousSettingsConflict: return QString::fromLatin1("AmbiguousSettingsConflict");
    case PlaylistRecordError::NotOwnRecord: return QString::fromLatin1("NotOwnRecord");
    case PlaylistRecordError::UnrecognizedRecord: return QString::fromLatin1("UnrecognizedRecord");
    case PlaylistRecordError::ReadFailed: return QString::fromLatin1("ReadFailed");
    case PlaylistRecordError::ReadVanished: return QString::fromLatin1("ReadVanished");
    case PlaylistRecordError::ReadInaccessible: return QString::fromLatin1("ReadInaccessible");
    case PlaylistRecordError::ReadCancelled: return QString::fromLatin1("ReadCancelled");
    case PlaylistRecordError::CodecError: return QString::fromLatin1("CodecError");
    case PlaylistRecordError::SchemaError: return QString::fromLatin1("SchemaError");
    case PlaylistRecordError::SerializeFailed: return QString::fromLatin1("SerializeFailed");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistCodecStatus value) {
    switch (value) {
    case PlaylistCodecStatus::Valid: return QString::fromLatin1("Valid");
    case PlaylistCodecStatus::Invalid: return QString::fromLatin1("Invalid");
    case PlaylistCodecStatus::Oversized: return QString::fromLatin1("Oversized");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistCodecError value) {
    switch (value) {
    case PlaylistCodecError::None: return QString::fromLatin1("None");
    case PlaylistCodecError::InvalidCap: return QString::fromLatin1("InvalidCap");
    case PlaylistCodecError::InvalidGzip: return QString::fromLatin1("InvalidGzip");
    case PlaylistCodecError::CompressedSizeLimit: return QString::fromLatin1("CompressedSizeLimit");
    case PlaylistCodecError::UncompressedSizeLimit: return QString::fromLatin1("UncompressedSizeLimit");
    case PlaylistCodecError::CodecFailure: return QString::fromLatin1("CodecFailure");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistLibraryStatus value) {
    switch (value) {
    case PlaylistLibraryStatus::Valid: return QString::fromLatin1("Valid");
    case PlaylistLibraryStatus::NewerFormat: return QString::fromLatin1("NewerFormat");
    case PlaylistLibraryStatus::Invalid: return QString::fromLatin1("Invalid");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}

QString Name(PlaylistLibraryError value) {
    switch (value) {
    case PlaylistLibraryError::None: return QString::fromLatin1("None");
    case PlaylistLibraryError::InvalidJson: return QString::fromLatin1("InvalidJson");
    case PlaylistLibraryError::SizeLimit: return QString::fromLatin1("SizeLimit");
    case PlaylistLibraryError::MissingField: return QString::fromLatin1("MissingField");
    case PlaylistLibraryError::UnknownKey: return QString::fromLatin1("UnknownKey");
    case PlaylistLibraryError::FieldType: return QString::fromLatin1("FieldType");
    case PlaylistLibraryError::InvalidFormat: return QString::fromLatin1("InvalidFormat");
    case PlaylistLibraryError::InvalidId: return QString::fromLatin1("InvalidId");
    case PlaylistLibraryError::InvalidDocumentId: return QString::fromLatin1("InvalidDocumentId");
    case PlaylistLibraryError::InvalidPeerToken: return QString::fromLatin1("InvalidPeerToken");
    case PlaylistLibraryError::InvalidUsername: return QString::fromLatin1("InvalidUsername");
    case PlaylistLibraryError::InvalidTitle: return QString::fromLatin1("InvalidTitle");
    case PlaylistLibraryError::InvalidMeta: return QString::fromLatin1("InvalidMeta");
    case PlaylistLibraryError::InvalidStamp: return QString::fromLatin1("InvalidStamp");
    case PlaylistLibraryError::InvalidSince: return QString::fromLatin1("InvalidSince");
    case PlaylistLibraryError::InvalidOrder: return QString::fromLatin1("InvalidOrder");
    case PlaylistLibraryError::InvalidAlt: return QString::fromLatin1("InvalidAlt");
    case PlaylistLibraryError::InvalidPurged: return QString::fromLatin1("InvalidPurged");
    case PlaylistLibraryError::InvalidValue: return QString::fromLatin1("InvalidValue");
    case PlaylistLibraryError::EmptyPlaylist: return QString::fromLatin1("EmptyPlaylist");
    case PlaylistLibraryError::PurgedPlaylistPresent: return QString::fromLatin1("PurgedPlaylistPresent");
    case PlaylistLibraryError::InvalidSelfUserId: return QString::fromLatin1("InvalidSelfUserId");
    }
    throw std::logic_error("UnknownPlaylistSyncEnum");
}


QByteArray Bytes(JNIEnv *env, jbyteArray value) {
    if (!value) throw std::invalid_argument("MissingPlaylistSyncBytes");
    const auto size = env->GetArrayLength(value);
    auto result = QByteArray(size, Qt::Uninitialized);
    if (size) env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("PlaylistSyncBytesUnavailable");
    return result;
}
QJsonObject Object(const QByteArray &bytes) {
    const auto checked = CanonicalizeSyncJson(bytes, std::nullopt);
    if (!checked) throw std::invalid_argument("InvalidPlaylistSyncJson");
    const auto document = QJsonDocument::fromJson(checked.canonical);
    if (!document.isObject()) throw std::invalid_argument("PlaylistSyncObjectRequired");
    return document.object();
}
QString Text(const QJsonObject &value, const char *key) {
    const auto field = value.value(QLatin1String(key));
    if (!field.isString()) throw std::invalid_argument("PlaylistSyncStringRequired");
    return field.toString();
}
bool Flag(const QJsonObject &value, const char *key) {
    const auto field = value.value(QLatin1String(key));
    if (!field.isBool()) throw std::invalid_argument("PlaylistSyncBooleanRequired");
    return field.toBool();
}
QJsonArray List(const QJsonObject &value, const char *key) {
    const auto field = value.value(QLatin1String(key));
    if (!field.isArray()) throw std::invalid_argument("PlaylistSyncArrayRequired");
    return field.toArray();
}
QJsonObject Entry(const QJsonValue &value) {
    if (!value.isObject()) throw std::invalid_argument("PlaylistSyncEntryRequired");
    return value.toObject();
}
uint64_t Number(const QJsonObject &value, const char *key, bool zero = false) {
    const auto text = Text(value, key);
    if (text.isEmpty() || (text.size() > 1 && text.front() == QLatin1Char('0')))
        throw std::invalid_argument("InvalidPlaylistSyncNumber");
    for (const auto c : text) if (c < QLatin1Char('0') || c > QLatin1Char('9'))
        throw std::invalid_argument("InvalidPlaylistSyncNumber");
    auto ok = false;
    const auto result = text.toULongLong(&ok);
    if (!ok || (!zero && !result)) throw std::invalid_argument("InvalidPlaylistSyncNumber");
    return result;
}
int32_t MessageId(const QJsonObject &value) {
    const auto id = Number(value, "message");
    if (id > uint64_t(std::numeric_limits<int32_t>::max())) throw std::invalid_argument("InvalidPlaylistMessageId");
    return int32_t(id);
}
std::optional<PeerToken> Chat(const QJsonObject &value, const char *key, uint64_t self) {
    const auto field = value.value(QLatin1String(key));
    if (field.isNull()) return std::nullopt;
    const auto parsed = ParsePeerToken(Text(value, key), self);
    if (!parsed) throw std::invalid_argument("InvalidPlaylistSyncChat");
    return parsed;
}
QString Decimal(uint64_t value) { return QString::number(qulonglong(value)); }
QJsonObject Identity(int32_t message, uint64_t document) {
    return {{"message", Decimal(uint64_t(message))}, {"document", Decimal(document)}};
}
template <typename T>
QJsonArray Identities(const T &values) {
    auto result = QJsonArray();
    for (const auto &value : values) result.append(Identity(value.messageId, value.documentId));
    return result;
}
template <typename T>
QJsonArray Numbers(const T &values) {
    auto result = QJsonArray();
    for (const auto value : values) result.append(Decimal(uint64_t(value)));
    return result;
}
PlaylistRecordMessageMeta Metadata(const QJsonObject &value) {
    auto result = PlaylistRecordMessageMeta();
    result.isRegularMessage = Flag(value, "regular");
    result.forwarded = Flag(value, "forwarded");
    result.isDocument = Flag(value, "isDocument");
    result.caption = Text(value, "caption"); result.mimeType = Text(value, "mime");
    for (const auto name : List(value, "names")) {
        if (!name.isString()) throw std::invalid_argument("InvalidPlaylistRecordName");
        result.fileNames.push_back(name.toString());
    }
    return result;
}
SyncChatMessageOwnership Ownership(const QJsonObject &value) {
    const auto text = Text(value, "ownership");
    if (text == QLatin1String("Own")) return SyncChatMessageOwnership::Own;
    if (text == QLatin1String("Other")) return SyncChatMessageOwnership::Other;
    if (text == QLatin1String("Unknown")) return SyncChatMessageOwnership::Unknown;
    throw std::invalid_argument("InvalidPlaylistOwnership");
}
PlaylistStepLocalRead Read(const QJsonObject &value, const char *key) {
    const auto text = Text(value, key);
    if (text == QLatin1String("Succeeded")) return PlaylistStepLocalRead::Succeeded;
    if (text == QLatin1String("Missing")) return PlaylistStepLocalRead::Missing;
    if (text == QLatin1String("Failed")) return PlaylistStepLocalRead::Failed;
    throw std::invalid_argument("InvalidPlaylistReadOutcome");
}
PlaylistStepTrigger Trigger(const QString &text) {
    if (text == QLatin1String("Automatic")) return PlaylistStepTrigger::Automatic;
    if (text == QLatin1String("Manual")) return PlaylistStepTrigger::Manual;
    if (text == QLatin1String("Logout")) return PlaylistStepTrigger::Logout;
    if (text == QLatin1String("LocalPurge")) return PlaylistStepTrigger::LocalPurge;
    throw std::invalid_argument("InvalidPlaylistStepTrigger");
}
PlaylistPayloadOrigin Origin(const QString &text) {
    if (text == QLatin1String("Unproven")) return PlaylistPayloadOrigin::Unproven;
    if (text == QLatin1String("DownloadedThisStep")) return PlaylistPayloadOrigin::DownloadedThisStep;
    if (text == QLatin1String("Cached")) return PlaylistPayloadOrigin::Cached;
    if (text == QLatin1String("PublishedThisStep")) return PlaylistPayloadOrigin::PublishedThisStep;
    throw std::invalid_argument("InvalidPlaylistPayloadOrigin");
}
PlaylistRecordReadOutcome Outcome(const QString &text) {
    if (text == QLatin1String("Succeeded")) return PlaylistRecordReadOutcome::Succeeded;
    if (text == QLatin1String("Failed")) return PlaylistRecordReadOutcome::Failed;
    if (text == QLatin1String("Vanished")) return PlaylistRecordReadOutcome::Vanished;
    if (text == QLatin1String("Inaccessible")) return PlaylistRecordReadOutcome::Inaccessible;
    if (text == QLatin1String("Cancelled")) return PlaylistRecordReadOutcome::Cancelled;
    throw std::invalid_argument("InvalidPlaylistPayloadRead");
}
PlaylistByIdOutcome Lookup(const QString &text) {
    if (text == QLatin1String("Present")) return PlaylistByIdOutcome::Present;
    if (text == QLatin1String("Gone")) return PlaylistByIdOutcome::Gone;
    if (text == QLatin1String("Failed")) return PlaylistByIdOutcome::Failed;
    if (text == QLatin1String("Inaccessible")) return PlaylistByIdOutcome::Inaccessible;
    if (text == QLatin1String("Cancelled")) return PlaylistByIdOutcome::Cancelled;
    throw std::invalid_argument("InvalidPlaylistLookupOutcome");
}
struct Answer {
    QJsonObject detail;
    std::optional<QByteArray> library, state, publication;
};
Answer Blocked(const char *error) { return {{{"status", "Blocked"}, {"error", error}}, {}, {}, {}}; }
Answer Plan(JNIEnv *env, jbyteArray request, jbyteArray library, jbyteArray state, jobjectArray payloads, uint64_t self) {
    const auto value = Object(Bytes(env, request));
    auto input = PlaylistStepInput();
    input.selfUserId = self; input.accountAuthorized = Flag(value, "authorized");
    input.trigger = Trigger(Text(value, "trigger"));
    input.libraryRead = Read(value, "libraryRead"); input.stateRead = Read(value, "stateRead");
    if (input.libraryRead == PlaylistStepLocalRead::Succeeded) {
        const auto parsed = ParsePlaylistLibrary(Bytes(env, library), self, std::nullopt);
        if (!parsed) return Blocked(parsed.status == PlaylistLibraryStatus::NewerFormat ? "NewerLibrary" : "InvalidLibrary");
        input.library = parsed.library;
    }
    if (input.stateRead == PlaylistStepLocalRead::Succeeded) {
        const auto parsed = ParsePlaylistLocalState(Bytes(env, state), self, std::nullopt);
        if (!parsed) return Blocked(parsed.status == PlaylistLocalStatus::NewerFormat ? "NewerState" : "InvalidState");
        input.state = parsed.state;
    }
    auto &discovery = input.discovery;
    discovery.selfUserId = Number(value, "discoverySelf");
    discovery.currentChat = Chat(value, "chat", self);
    discovery.listingSucceeded = Flag(value, "listingSucceeded");
    discovery.listingExhausted = Flag(value, "listingExhausted");
    discovery.byIdSucceeded = Flag(value, "byIdSucceeded");
    for (const auto row : List(value, "listed")) {
        const auto item = Entry(row);
        discovery.listedObservations.push_back({MessageId(item), Number(item, "document", true), Ownership(item), Metadata(item)});
    }
    for (const auto row : List(value, "byId")) {
        const auto item = Entry(row);
        discovery.byIdObservations.push_back({MessageId(item), Lookup(Text(item, "outcome")),
            Number(item, "document", true), Ownership(item), Metadata(item)});
    }
    const auto clock = Entry(value.value("clock"));
    input.clock = {Number(clock, "self"), Chat(clock, "chat", self), Number(clock, "now", true), Flag(clock, "succeeded")};
    const auto proofs = List(value, "payloads");
    if (!payloads || env->GetArrayLength(payloads) != proofs.size()) throw std::invalid_argument("MismatchedPlaylistPayloads");
    for (auto i = 0; i < proofs.size(); ++i) {
        const auto proof = Entry(proofs.at(i));
        auto result = PlaylistRecordPayloadProof();
        result.chat = Chat(proof, "chat", self); result.messageId = MessageId(proof);
        result.documentId = Number(proof, "document"); result.origin = Origin(Text(proof, "origin"));
        result.readSucceeded = Flag(proof, "readSucceeded");
        result.publicationAcknowledged = Flag(proof, "acknowledged");
        if (!proof.value("expectedHash").isNull()) result.expectedCanonicalHash = Text(proof, "expectedHash");
        const auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(payloads, i));
        if (env->ExceptionCheck()) throw std::runtime_error("PlaylistPayloadUnavailable");
        if (bytes) { result.compressedBytes = Bytes(env, bytes); env->DeleteLocalRef(bytes); }
        input.payloads.push_back(std::move(result));
    }
    const auto plan = PlanPlaylistStep(input);
    auto detail = QJsonObject{{"status", Name(plan.status)}, {"error", Name(plan.error)},
        {"publication", Name(plan.publication)}, {"writeLibrary", plan.writeLibrary}, {"writeState", plan.writeState},
        {"steadyState", plan.steadyState}, {"discoveryComplete", plan.discoveryComplete}, {"canonicalHash", plan.canonicalHash},
        {"retryReads", Identities(plan.retryReads)}, {"purgeStatus", Name(plan.purgeStatus)},
        {"newlyMergedRecords", Decimal(plan.newlyMergedRecords)}};
    const auto &inventory = plan.inventory;
    detail.insert("inventory", QJsonObject{{"status", Name(inventory.status)}, {"error", Name(inventory.error)},
        {"bindingChanged", inventory.bindingChanged}, {"metadataComplete", inventory.metadataComplete},
        {"present", Identities(inventory.presentCandidates)}, {"reads", Identities(inventory.readCandidates)},
        {"pendingById", Numbers(inventory.pendingByIdIds)}, {"gone", Numbers(inventory.confirmedGoneIds)},
        {"unknownOwnership", Numbers(inventory.unknownOwnershipIds)}, {"conflicting", Numbers(inventory.conflictingIds)}});
    detail.insert("records", QJsonObject{{"valid", Decimal(plan.records.valid)}, {"invalid", Decimal(plan.records.invalid)},
        {"newer", Decimal(plan.records.newerFormat)}, {"oversized", Decimal(plan.records.oversized)}, {"unreadable", Decimal(plan.records.unreadable)}});
    auto prunes = QJsonArray();
    for (const auto &prune : plan.prune.prune) prunes.append(QJsonObject{{"target", Identity(prune.target.messageId, prune.target.documentId)},
        {"witness", Identity(prune.witness.messageId, prune.witness.documentId)}, {"hash", prune.witnessCanonicalHash}});
    auto unusable = QJsonArray();
    for (const auto &item : plan.prune.unusable) unusable.append(QJsonObject{{"message", Decimal(uint64_t(item.messageId))}, {"reason", Name(item.reason)}});
    detail.insert("prune", QJsonObject{{"status", Name(plan.prune.status)}, {"error", Name(plan.prune.error)},
        {"targets", prunes}, {"survivors", Identities(plan.prune.survivors)}, {"unusable", unusable}});
    auto notices = QJsonArray();
    for (const auto &notice : plan.diff.notices) notices.append(QJsonObject{{"id", notice.playlistId}, {"title", notice.title},
        {"reason", Name(notice.reason)}, {"action", Name(notice.suggestedAction)}});
    detail.insert("notices", notices);
    auto purged = QJsonArray(); for (const auto &id : plan.purgedIds) purged.append(id);
    detail.insert("purged", purged);
    auto answer = Answer{detail, {}, {}, {}};
    if (plan.status == PlaylistStepStatus::Planned) {
        if (!plan.state || !plan.library) throw std::logic_error("IncompletePlaylistStep");
        const auto local = SerializePlaylistLocalState(*plan.state, self);
        if (!local) throw std::logic_error("InvalidPlaylistStepState");
        answer.library = plan.canonicalBytes; answer.state = local.json;
        if (plan.publish()) answer.publication = plan.publicationBytes;
    }
    return answer;
}
Answer VerifyPrune(JNIEnv *env, jbyteArray request, jbyteArray target, jbyteArray witness, uint64_t self) {
    const auto value = Object(Bytes(env, request));
    const auto chat = Chat(value, "chat", self);
    const auto rows = List(value, "messages");
    const auto expectedTarget = Entry(value.value("target"));
    const auto expectedWitness = Entry(value.value("witness"));
    if (rows.size() != 2 || MessageId(expectedTarget) == MessageId(expectedWitness))
        return Blocked("InvalidPrunePair");
    auto inventory = PlaylistInventoryInput();
    inventory.selfUserId = self; inventory.currentChat = chat;
    inventory.listingSucceeded = true;
    auto input = PlaylistRecordPruneInput();
    input.selfUserId = self; input.currentChat = chat; input.presentSetChanged = true;
    for (auto i = 0; i != 2; ++i) {
        const auto row = Entry(rows.at(i));
        inventory.listedObservations.push_back({MessageId(row), Number(row, "document"), Ownership(row), Metadata(row)});
        const auto bytes = i ? witness : target;
        if (!bytes || env->GetArrayLength(bytes) > 10 * 1024 * 1024) return Blocked("PrunePayloadLimit");
        auto proof = PlaylistRecordPayloadProof();
        proof.chat = chat; proof.messageId = MessageId(row); proof.documentId = Number(row, "document");
        proof.origin = PlaylistPayloadOrigin::DownloadedThisStep;
        proof.readSucceeded = true; proof.compressedBytes = Bytes(env, bytes);
        input.payloads.push_back(std::move(proof));
    }
    input.inventory = ReconcilePlaylistInventory(inventory);
    const auto plan = PlanPlaylistRecordPrunes(input);
    const auto allowed = plan.status == PlaylistRecordPruneStatus::Planned
        && plan.prune.size() == 1
        && plan.prune.front().target == PlaylistRecordIdentity{MessageId(expectedTarget), Number(expectedTarget, "document")}
        && plan.prune.front().witness == PlaylistRecordIdentity{MessageId(expectedWitness), Number(expectedWitness, "document")}
        && plan.prune.front().witnessCanonicalHash == Text(value, "hash");
    return Answer{{{"allowed", allowed}, {"status", Name(plan.status)}, {"error", Name(plan.error)}}, {}, {}, {}};
}
jbyteArray Array(JNIEnv *env, const std::optional<QByteArray> &bytes) {
    if (!bytes) return nullptr;
    if (bytes->size() > std::numeric_limits<jsize>::max()) throw std::bad_alloc();
    const auto result = env->NewByteArray(jsize(bytes->size()));
    if (result && !bytes->isEmpty()) env->SetByteArrayRegion(result, 0, jsize(bytes->size()), reinterpret_cast<const jbyte*>(bytes->constData()));
    return result;
}
jobject Reply(JNIEnv *env, const Answer &answer) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsSyncCore$Result");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>", "([B[B[B[B)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    const auto detail = Array(env, QJsonDocument(answer.detail).toJson(QJsonDocument::Compact));
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    const auto library = Array(env, answer.library);
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    const auto state = Array(env, answer.state);
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    const auto publication = Array(env, answer.publication);
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    return env->PopLocalFrame(env->NewObject(type, constructor, detail, library, state, publication));
}
void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) { env->ThrowNew(error, message); env->DeleteLocalRef(error); }
}
template <typename Function>
jobject Perform(JNIEnv *env, jlong self, Function function) {
    try {
        if (self <= 0) throw std::invalid_argument("InvalidPlaylistSyncSelf");
        const auto answer = function(uint64_t(self));
        return env->ExceptionCheck() ? nullptr : Reply(env, answer);
    } catch (const std::bad_alloc &) { Throw(env, "java/lang/OutOfMemoryError", "Playlist sync allocation failed"); }
    catch (const std::invalid_argument &) { Throw(env, "java/lang/IllegalArgumentException", "Invalid playlist sync input"); }
    catch (const std::exception &) { Throw(env, "java/lang/IllegalStateException", "Playlist sync bridge failed"); }
    return nullptr;
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsSyncCore_metadataNative(JNIEnv *env, jclass, jbyteArray message, jlong self) {
    return Perform(env, self, [&](uint64_t) {
        const auto value = Object(Bytes(env, message));
        return Answer{{{"verdict", Name(InspectPlaylistRecordMetadata(Metadata(value), Ownership(value)))},
            {"fileName", PlaylistRecordFileName()}, {"mime", PlaylistRecordMimeType()}, {"caption", PlaylistRecordCaption()},
            {"classifierVersion", kPlaylistRecordClassifierVersion}}, {}, {}, {}};
    });
}
extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsSyncCore_classifyNative(JNIEnv *env, jclass, jbyteArray message, jbyteArray compressed, jlong self) {
    return Perform(env, self, [&](uint64_t user) {
        const auto value = Object(Bytes(env, message));
        auto input = PlaylistRecordInput{MessageId(value), Number(value, "document"), Ownership(value), Metadata(value), Outcome(Text(value, "read")), {}, user};
        if (input.readOutcome == PlaylistRecordReadOutcome::Succeeded) input.compressedBytes = Bytes(env, compressed);
        const auto result = ClassifyPlaylistRecord(input);
        return Answer{{{"status", Name(result.status)}, {"error", Name(result.error)}, {"codecError", Name(result.codecError)},
            {"libraryError", Name(result.libraryError)}, {"hash", result.canonicalHash ? QJsonValue(*result.canonicalHash) : QJsonValue(QJsonValue::Null)}}, result.canonicalBytes, {}, result.compressedBytes};
    });
}
extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsSyncCore_encodeNative(JNIEnv *env, jclass, jbyteArray library, jlong self) {
    return Perform(env, self, [&](uint64_t user) {
        const auto parsed = ParsePlaylistLibrary(Bytes(env, library), user, std::nullopt);
        if (!parsed) return Answer{{{"status", Name(parsed.status)}, {"error", Name(parsed.error)}}, {}, {}, {}};
        const auto canonical = SerializePlaylistLibrary(parsed.library, user);
        if (!canonical) throw std::logic_error("InvalidCanonicalPlaylist");
        const auto result = EncodePlaylistBytes(canonical.canonical);
        return Answer{{{"status", Name(result.status)}, {"error", Name(result.error)}}, result ? std::optional<QByteArray>(canonical.canonical) : std::nullopt, {}, result ? std::optional<QByteArray>(result.bytes) : std::nullopt};
    });
}
extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsSyncCore_planNative(JNIEnv *env, jclass, jbyteArray request,
        jbyteArray library, jbyteArray state, jobjectArray payloads, jlong self) {
    return Perform(env, self, [&](uint64_t user) { return Plan(env, request, library, state, payloads, user); });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsSyncCore_verifyPruneNative(JNIEnv *env, jclass, jbyteArray request,
        jbyteArray target, jbyteArray witness, jlong self) {
    return Perform(env, self, [&](uint64_t user) { return VerifyPrune(env, request, target, witness, user); });
}
