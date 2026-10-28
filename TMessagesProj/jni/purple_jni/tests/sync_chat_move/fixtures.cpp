#include "purple/purple_sync_chat_journal.h"
#include "purple/purple_sync_json.h"
#include <QtCore/QJsonArray>
#include <QtCore/QJsonDocument>
#include <QtCore/QFile>
#include <set>
#include <cstdlib>

namespace Probe {
using namespace Purple;
QFile Output;
std::set<QByteArray> Seen;
template <typename T> QString Name(T value);
template <> QString Name(SyncCarryMode value) {
    switch (value) {
    case SyncCarryMode::Switch: return u"Switch"_q;
    case SyncCarryMode::Follow: return u"Follow"_q;
    case SyncCarryMode::Fill: return u"Fill"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarrySourceAccess value) {
    switch (value) {
    case SyncCarrySourceAccess::Unknown: return u"Unknown"_q;
    case SyncCarrySourceAccess::Readable: return u"Readable"_q;
    case SyncCarrySourceAccess::Protected: return u"Protected"_q;
    case SyncCarrySourceAccess::Unreadable: return u"Unreadable"_q;
    case SyncCarrySourceAccess::ChatGone: return u"ChatGone"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryFamily value) {
    switch (value) {
    case SyncCarryFamily::Settings: return u"Settings"_q;
    case SyncCarryFamily::Playlists: return u"Playlists"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryCleanup value) {
    switch (value) {
    case SyncCarryCleanup::None: return u"None"_q;
    case SyncCarryCleanup::OwnLatePosts: return u"OwnLatePosts"_q;
    case SyncCarryCleanup::AllSettings: return u"AllSettings"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryStatus value) {
    switch (value) {
    case SyncCarryStatus::Invalid: return u"Invalid"_q;
    case SyncCarryStatus::Ready: return u"Ready"_q;
    case SyncCarryStatus::ReadSource: return u"ReadSource"_q;
    case SyncCarryStatus::ReadDestination: return u"ReadDestination"_q;
    case SyncCarryStatus::FinishPendingOld: return u"FinishPendingOld"_q;
    case SyncCarryStatus::AbandonPending: return u"AbandonPending"_q;
    case SyncCarryStatus::UnknownSource: return u"UnknownSource"_q;
    case SyncCarryStatus::MissingLocalVersion: return u"MissingLocalVersion"_q;
    case SyncCarryStatus::NeedsReview: return u"NeedsReview"_q;
    case SyncCarryStatus::ConsentRequired: return u"ConsentRequired"_q;
    case SyncCarryStatus::WrongScope: return u"WrongScope"_q;
    case SyncCarryStatus::MissingRecords: return u"MissingRecords"_q;
    case SyncCarryStatus::PlaylistRequired: return u"PlaylistRequired"_q;
    }
    std::abort();
}
template <> QString Name(SyncPlaylistCarryStatus value) {
    switch (value) {
    case SyncPlaylistCarryStatus::NotInUse: return u"NotInUse"_q;
    case SyncPlaylistCarryStatus::Incomplete: return u"Incomplete"_q;
    case SyncPlaylistCarryStatus::Verified: return u"Verified"_q;
    case SyncPlaylistCarryStatus::Failed: return u"Failed"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryPendingStatus value) {
    switch (value) {
    case SyncCarryPendingStatus::Invalid: return u"Invalid"_q;
    case SyncCarryPendingStatus::Ready: return u"Ready"_q;
    case SyncCarryPendingStatus::NoPending: return u"NoPending"_q;
    case SyncCarryPendingStatus::WrongChat: return u"WrongChat"_q;
    case SyncCarryPendingStatus::NotChatGone: return u"NotChatGone"_q;
    case SyncCarryPendingStatus::LocalCopyRequired: return u"LocalCopyRequired"_q;
    case SyncCarryPendingStatus::InvalidStage: return u"InvalidStage"_q;
    case SyncCarryPendingStatus::LibraryPending: return u"LibraryPending"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryPhase value) {
    switch (value) {
    case SyncCarryPhase::ResolvePending: return u"ResolvePending"_q;
    case SyncCarryPhase::WritePendingState: return u"WritePendingState"_q;
    case SyncCarryPhase::RetirePendingStage: return u"RetirePendingStage"_q;
    case SyncCarryPhase::ResolveAgain: return u"ResolveAgain"_q;
    case SyncCarryPhase::Plan: return u"Plan"_q;
    case SyncCarryPhase::StageOwn: return u"StageOwn"_q;
    case SyncCarryPhase::Deliver: return u"Deliver"_q;
    case SyncCarryPhase::VerifyDestination: return u"VerifyDestination"_q;
    case SyncCarryPhase::Mark: return u"Mark"_q;
    case SyncCarryPhase::PrepareCache: return u"PrepareCache"_q;
    case SyncCarryPhase::CommitState: return u"CommitState"_q;
    case SyncCarryPhase::RetireOwnStage: return u"RetireOwnStage"_q;
    case SyncCarryPhase::CompleteCache: return u"CompleteCache"_q;
    case SyncCarryPhase::Cleanup: return u"Cleanup"_q;
    case SyncCarryPhase::Done: return u"Done"_q;
    case SyncCarryPhase::Aborted: return u"Aborted"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryOutcome value) {
    switch (value) {
    case SyncCarryOutcome::Success: return u"Success"_q;
    case SyncCarryOutcome::Failed: return u"Failed"_q;
    case SyncCarryOutcome::Unknown: return u"Unknown"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryJournalAction value) {
    switch (value) {
    case SyncCarryJournalAction::None: return u"None"_q;
    case SyncCarryJournalAction::ResolvePending: return u"ResolvePending"_q;
    case SyncCarryJournalAction::WritePendingState: return u"WritePendingState"_q;
    case SyncCarryJournalAction::RetirePendingStage: return u"RetirePendingStage"_q;
    case SyncCarryJournalAction::ResolveAgain: return u"ResolveAgain"_q;
    case SyncCarryJournalAction::Plan: return u"Plan"_q;
    case SyncCarryJournalAction::StageOwn: return u"StageOwn"_q;
    case SyncCarryJournalAction::Deliver: return u"Deliver"_q;
    case SyncCarryJournalAction::VerifyDestination: return u"VerifyDestination"_q;
    case SyncCarryJournalAction::PostMark: return u"PostMark"_q;
    case SyncCarryJournalAction::ReadMark: return u"ReadMark"_q;
    case SyncCarryJournalAction::PrepareCache: return u"PrepareCache"_q;
    case SyncCarryJournalAction::CommitState: return u"CommitState"_q;
    case SyncCarryJournalAction::RetireOwnStage: return u"RetireOwnStage"_q;
    case SyncCarryJournalAction::CompleteCache: return u"CompleteCache"_q;
    case SyncCarryJournalAction::Cleanup: return u"Cleanup"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryMarkOutcome value) {
    switch (value) {
    case SyncCarryMarkOutcome::Unknown: return u"Unknown"_q;
    case SyncCarryMarkOutcome::Absent: return u"Absent"_q;
    case SyncCarryMarkOutcome::Valid: return u"Valid"_q;
    case SyncCarryMarkOutcome::ChatGone: return u"ChatGone"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryEventKind value) {
    switch (value) {
    case SyncCarryEventKind::PendingPrepared: return u"PendingPrepared"_q;
    case SyncCarryEventKind::StateObserved: return u"StateObserved"_q;
    case SyncCarryEventKind::StageRetired: return u"StageRetired"_q;
    case SyncCarryEventKind::Resolution: return u"Resolution"_q;
    case SyncCarryEventKind::PlanPrepared: return u"PlanPrepared"_q;
    case SyncCarryEventKind::OwnStagePersisted: return u"OwnStagePersisted"_q;
    case SyncCarryEventKind::DeliveryPrepared: return u"DeliveryPrepared"_q;
    case SyncCarryEventKind::DeliveryResult: return u"DeliveryResult"_q;
    case SyncCarryEventKind::DestinationRead: return u"DestinationRead"_q;
    case SyncCarryEventKind::MarkPostPrepared: return u"MarkPostPrepared"_q;
    case SyncCarryEventKind::MarkPostResult: return u"MarkPostResult"_q;
    case SyncCarryEventKind::MarkRead: return u"MarkRead"_q;
    case SyncCarryEventKind::CachePrepared: return u"CachePrepared"_q;
    case SyncCarryEventKind::CacheCompleted: return u"CacheCompleted"_q;
    case SyncCarryEventKind::CleanupCompleted: return u"CleanupCompleted"_q;
    case SyncCarryEventKind::Cancel: return u"Cancel"_q;
    }
    std::abort();
}
template <> QString Name(SyncCarryJournalStatus value) {
    switch (value) {
    case SyncCarryJournalStatus::Invalid: return u"Invalid"_q;
    case SyncCarryJournalStatus::Valid: return u"Valid"_q;
    case SyncCarryJournalStatus::NewerVersion: return u"NewerVersion"_q;
    case SyncCarryJournalStatus::Wait: return u"Wait"_q;
    case SyncCarryJournalStatus::Retry: return u"Retry"_q;
    case SyncCarryJournalStatus::NeedsReview: return u"NeedsReview"_q;
    case SyncCarryJournalStatus::Superseded: return u"Superseded"_q;
    }
    std::abort();
}
template <> QString Name(SyncChatMessageOwnership value) {
    switch (value) {
    case SyncChatMessageOwnership::Unknown: return u"Unknown"_q;
    case SyncChatMessageOwnership::Own: return u"Own"_q;
    case SyncChatMessageOwnership::Other: return u"Other"_q;
    }
    std::abort();
}
template <> QString Name(SyncChatDirectRead value) {
    switch (value) {
    case SyncChatDirectRead::Unknown: return u"Unknown"_q;
    case SyncChatDirectRead::Valid: return u"Valid"_q;
    case SyncChatDirectRead::MarkGone: return u"MarkGone"_q;
    case SyncChatDirectRead::ChatGone: return u"ChatGone"_q;
    case SyncChatDirectRead::Migrated: return u"Migrated"_q;
    }
    std::abort();
}
template <> QString Name(SyncChatAction value) {
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
    std::abort();
}
template <> QString Name(SyncChatRefusal value) {
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
    std::abort();
}

QString Blob(const QByteArray &bytes) { return QString::fromLatin1(bytes.toBase64()); }
QByteArray Canonical(const QJsonObject &object) { return CanonicalizeSyncJson(QJsonDocument(object).toJson(QJsonDocument::Compact)).canonical; }
QJsonValue State(const std::optional<SyncLocalState> &state) {
    if (!state) return QJsonValue(QJsonValue::Null);
    const auto result = SerializeSyncLocalState(*state);
    return result ? QJsonValue(Blob(result.canonical)) : QJsonValue();
}
QJsonObject Scope(const AccountKey &a, const PeerToken &s, const PeerToken &t) {
    return {{u"account"_q, AccountKeyText(a.environment,a.userId).value_or(QString())},
        {u"source"_q, PeerTokenText(s).value_or(QString())}, {u"target"_q, PeerTokenText(t).value_or(QString())}};
}
QJsonObject Journal(const SyncCarryJournal &j) {
    auto o = Scope(j.account,j.source,j.target);
    o.insert(u"journal"_q, Blob(SerializeSyncCarryJournal(j).canonical));
    return o;
}
QJsonObject Cache(const SyncChatCache &c) {
    return {{u"chat"_q, *PeerTokenText(c.chat)}, {u"id"_q, QString::number(c.markId)}, {u"date"_q, QString::number(c.markDate)}};
}
QJsonObject Resolution(const SyncChatResolution &r) {
    auto o = QJsonObject{{u"action"_q, Name(r.action)}};
    if(r.cache) o.insert(u"cache"_q,Cache(*r.cache));
    if(r.winner) o.insert(u"winner"_q,Cache(*r.winner));
    if(r.source) o.insert(u"source"_q,*PeerTokenText(*r.source));
    return o;
}
QJsonObject Eligibility(const SyncChatEligibilityResult &r) {
    auto readers = QJsonObject{{u"warningRequired"_q,r.readers.warningRequired},
        {u"privateRecipient"_q,r.readers.privateRecipient}, {u"botOwner"_q,r.readers.botOwner},
        {u"populationUnknown"_q,r.readers.populationUnknown}};
    if(r.readers.otherMembers) readers.insert(u"otherMembers"_q,QString::number(*r.readers.otherMembers));
    if(r.readers.otherAdmins) readers.insert(u"otherAdmins"_q,QString::number(*r.readers.otherAdmins));
    return {{u"refusal"_q,Name(r.refusal)}, {u"readers"_q,readers}};
}
QJsonObject Reference(const SyncCarryReference &r) {
    return {{u"account"_q,*AccountKeyText(r.account.environment,r.account.userId)},
        {u"chat"_q,*PeerTokenText(r.chat)}, {u"id"_q,QString::number(r.messageId)}, {u"hash"_q,r.recordHash}};
}
QJsonObject Message(const SyncCarryMessage &m) {
    auto o = Reference(m.reference);
    o.insert(u"date"_q,QString::number(m.date)); o.insert(u"bytes"_q,Blob(m.bytes));
    o.insert(u"family"_q,Name(m.family)); o.insert(u"ownership"_q,Name(m.ownership));
    return o;
}
QJsonObject Inventory(const SyncCarryInventory &i) {
    auto messages = QJsonArray();
    for(const auto &m:i.messages) messages.append(Message(m));
    return {{u"account"_q,*AccountKeyText(i.account.environment,i.account.userId)},
        {u"chat"_q,*PeerTokenText(i.chat)}, {u"complete"_q,i.complete}, {u"messages"_q,messages}};
}
QJsonObject Mark(const SyncCarryMarkReadback &m) {
    return {{u"outcome"_q,Name(m.outcome)}, {u"account"_q,*AccountKeyText(m.account.environment,m.account.userId)},
        {u"chat"_q,*PeerTokenText(m.chat)}, {u"id"_q,QString::number(m.messageId)}, {u"date"_q,QString::number(m.date)},
        {u"text"_q,m.text}, {u"hasMedia"_q,m.hasMedia}, {u"ownership"_q,Name(m.ownership)}};
}
QJsonObject Playlist(const SyncPlaylistCarryReceipt &p) {
    return {{u"status"_q,Name(p.status)}, {u"account"_q,AccountKeyText(p.account.environment,p.account.userId).value_or(QString())},
        {u"source"_q,PeerTokenText(p.source).value_or(QString())}, {u"target"_q,PeerTokenText(p.target).value_or(QString())},
        {u"planStamp"_q,p.planStamp}, {u"destinationStamp"_q,p.destinationStamp}};
}
QJsonObject Event(const SyncCarryJournalEvent &e) {
    auto o = Scope(e.account,e.source,e.target);
    o.insert(u"operation"_q,e.operationId); o.insert(u"revision"_q,QString::number(e.revision));
    o.insert(u"kind"_q,Name(e.kind)); o.insert(u"outcome"_q,Name(e.outcome));
    o.insert(u"objectsDurable"_q,e.objectsDurable); o.insert(u"state"_q,State(e.state));
    o.insert(u"stateAbsentConfirmed"_q,e.stateAbsentConfirmed);
    o.insert(u"stagedRecord"_q,Blob(e.stagedRecord)); o.insert(u"retainedLocalText"_q,Blob(e.retainedLocalText));
    o.insert(u"oldRead"_q,Name(e.oldRead));
    if(e.pendingReadback) o.insert(u"pendingReadback"_q,Message(*e.pendingReadback));
    o.insert(u"preparedImageHash"_q,e.preparedImageHash);
    o.insert(u"resolution"_q,Resolution(e.resolution));
    if(e.plan) o.insert(u"plan"_q,Blob(Canonical(SyncCarryPlanJson(*e.plan))));
    if(e.destination) o.insert(u"destination"_q,Inventory(*e.destination));
    if(e.playlist.status != SyncPlaylistCarryStatus::NotInUse) o.insert(u"playlist"_q,Playlist(e.playlist));
    if(AccountKeyText(e.mark.account.environment,e.mark.account.userId)) o.insert(u"mark"_q,Mark(e.mark));
    if(AccountKeyText(e.cache.account.environment,e.cache.account.userId)) o.insert(u"cache"_q,QJsonObject{
        {u"account"_q,*AccountKeyText(e.cache.account.environment,e.cache.account.userId)}, {u"operation"_q,e.cache.operationId},
        {u"mark"_q,Cache(e.cache.mark)}, {u"complete"_q,e.cache.complete}});
    o.insert(u"retiredStageHash"_q,e.retiredStageHash);
    auto refs = QJsonArray(); for(const auto &r:e.deleted) refs.append(Reference(r));
    o.insert(u"deleted"_q,refs); o.insert(u"noRequestInFlight"_q,e.noRequestInFlight); o.insert(u"noPendingFiles"_q,e.noPendingFiles);
    return o;
}
void Emit(const QString &op,const QJsonObject &input,const QJsonObject &expected) {
    const auto bytes = Canonical(QJsonObject{{u"op"_q,op},{u"input"_q,input},{u"expected"_q,expected}});
    if(bytes.isEmpty() || !Seen.insert(bytes).second) return;
    if(Output.write(bytes+'\n') != bytes.size()+1) std::abort();
}
QJsonObject Result(const SyncCarryJournalResult &r) {
    return {{u"status"_q,Name(r.status)}, {u"canonical"_q,Blob(r.canonical)}};
}
std::optional<SyncLocalState> Reopen(const std::optional<SyncLocalState> &state) {
    if (!state) return std::nullopt;
    const auto parsed = ParseSyncLocalState(SerializeSyncLocalState(*state).canonical);
    return parsed ? std::optional(parsed.state) : state;
}
SyncCarryPlan Reopen(const SyncCarryPlan &plan) {
    return ParseSyncCarryPlan(SyncCarryPlanJson(plan)).value_or(plan);
}
SyncCarryJournal Reopen(const SyncCarryJournal &journal) {
    return ParseSyncCarryJournal(SerializeSyncCarryJournal(journal).canonical).journal;
}
SyncCarryJournalEvent Reopen(SyncCarryJournalEvent event) {
    event.state = Reopen(event.state);
    if (event.plan) event.plan = Reopen(*event.plan);
    return event;
}
SyncCarryJournalResult Advance(const SyncCarryJournal &j,const SyncCarryJournalEvent &e) {
    const auto r = AdvanceSyncCarryJournal(j,e);
    if(SerializeSyncCarryJournal(j)) { auto input = Journal(j); input.insert(u"event"_q,Event(e)); Emit(u"advance"_q,input,Result(AdvanceSyncCarryJournal(Reopen(j),Reopen(e)))); }
    return r;
}
SyncCarryJournalResult Begin(const SyncCarryJournalStart &s) {
    const auto r = BeginSyncCarryJournal(s);
    auto input = Scope(s.account,s.source,s.target);
    input.insert(u"operation"_q,s.operationId); input.insert(u"mode"_q,Name(s.mode));
    input.insert(u"state"_q,State(s.state)); input.insert(u"stateAbsentConfirmed"_q,s.stateAbsentConfirmed);
    input.insert(u"preferenceToken"_q,Blob(s.preferenceToken)); input.insert(u"otherStagesPresent"_q,s.otherStagesPresent);
    input.insert(u"resolution"_q,Resolution(s.resolution)); input.insert(u"eligibility"_q,Eligibility(s.targetEligibility));
    input.insert(u"readerConsentAccepted"_q,s.readerConsentAccepted); input.insert(u"operationConfirmed"_q,s.operationConfirmed);
    input.insert(u"markText"_q,s.markText); input.insert(u"markRequestId"_q,s.markRequestId); input.insert(u"ownRequestId"_q,s.ownRequestId);
    input.insert(u"removeOldMarkAuthorized"_q,s.removeOldMarkAuthorized);
    auto reopened=s; reopened.state=Reopen(s.state);
    Emit(u"begin"_q,input,Result(BeginSyncCarryJournal(reopened))); return r;
}
SyncCarryJournalResult Resume(const SyncCarryJournal &j,const std::optional<SyncLocalState> &s,bool absent,bool durable=false) {
    const auto r = ResumeSyncCarryJournal(j,s,absent,durable);
    if(SerializeSyncCarryJournal(j)) { auto input=Journal(j); input.insert(u"state"_q,State(s));
        input.insert(u"stateAbsentConfirmed"_q,absent); input.insert(u"stateDurableConfirmed"_q,durable); Emit(u"resume"_q,input,Result(ResumeSyncCarryJournal(Reopen(j),Reopen(s),absent,durable))); }
    return r;
}
SyncCarryJournalAction Next(const SyncCarryJournal &j) {
    const auto r=NextSyncCarryJournalAction(j);
    if(SerializeSyncCarryJournal(j)) Emit(u"next"_q,Journal(j),{{u"status"_q,u"Valid"_q},{u"nextAction"_q,Name(r)}});
    return r;
}
QJsonObject Input(const SyncCarryInput &i) {
    auto o=Scope(i.account,i.source,i.target);
    o.insert(u"mode"_q,Name(i.mode)); o.insert(u"access"_q,Name(i.access));
    o.insert(u"eligibility"_q,Eligibility(i.targetEligibility)); o.insert(u"readerConsentAccepted"_q,i.readerConsentAccepted);
    o.insert(u"state"_q,State(i.state)); o.insert(u"preferenceToken"_q,Blob(i.preferenceToken));
    o.insert(u"otherStagesPresent"_q,i.otherStagesPresent);
    o.insert(u"oldInventory"_q,Inventory(i.oldInventory)); o.insert(u"newInventory"_q,Inventory(i.newInventory));
    if(i.ownVersion) {
        auto parents=QJsonArray(); for(const auto &v:i.ownVersion->version.parents) parents.append(v);
        auto lineage=QJsonArray(); for(const auto &v:i.ownVersion->version.lineage) lineage.append(v);
        o.insert(u"ownVersion"_q,QJsonObject{{u"text"_q,Blob(i.ownVersion->text)},{u"key"_q,i.ownVersion->version.key},
            {u"parents"_q,parents},{u"lineage"_q,lineage},{u"platform"_q,i.ownVersion->platform},
            {u"app"_q,i.ownVersion->app},{u"at"_q,QString::number(i.ownVersion->at)}});
    }
    if(i.winner) o.insert(u"winner"_q,Cache(*i.winner));
    o.insert(u"cleanup"_q,Name(i.cleanup)); o.insert(u"cleanupAuthorized"_q,i.cleanupAuthorized); o.insert(u"playlistsInUse"_q,i.playlistsInUse);
    return o;
}
SyncCarryPlanResult Plan(const SyncCarryInput &i) {
    const auto r=PlanCarry(i);
    auto reopened=i; reopened.state=Reopen(i.state);
    const auto wire=PlanCarry(reopened);
    auto expected=QJsonObject{{u"status"_q,Name(wire.status)}};
    if(wire) expected.insert(u"plan"_q,Blob(Canonical(SyncCarryPlanJson(wire.plan))));
    if(wire.reservedState) expected.insert(u"state"_q,State(wire.reservedState));
    if(r.status!=SyncCarryStatus::WrongScope) Emit(u"plan"_q,Input(i),expected);
    return r;
}
QJsonObject Planned(const SyncCarryPlan &p,const SyncCarryInventory &i,const SyncPlaylistCarryReceipt &playlist) {
    auto o=Scope(p.account,p.source,p.target); o.insert(u"plan"_q,Blob(Canonical(SyncCarryPlanJson(p))));
    o.insert(u"destination"_q,Inventory(i));
    if(playlist.status!=SyncPlaylistCarryStatus::NotInUse) o.insert(u"playlist"_q,Playlist(playlist));
    return o;
}
SyncCarryCommit Commit(const SyncCarryPlan &p,const std::optional<SyncLocalState> &s,const SyncCarryInventory &i,const SyncPlaylistCarryReceipt &playlist={}) {
    const auto r=PrepareSyncCarryCommit(p,s,i,playlist);
    if(ParseSyncCarryPlan(SyncCarryPlanJson(p))) {
        auto o=Planned(p,i,playlist); o.insert(u"state"_q,State(s));
        const auto wire=PrepareSyncCarryCommit(Reopen(p),Reopen(s),i,playlist);
        Emit(u"prepareCommit"_q,o,{{u"status"_q,Name(wire.status)},{u"canonical"_q,Blob(wire.canonical)},{u"stateHash"_q,wire.stateHash}});
    }
    return r;
}
bool Deletion(const SyncCarryJournal &j,const SyncCarryMessage &m,const SyncCarryInventory &i,const SyncPlaylistCarryReceipt &p={}) {
    const auto r=CheckSyncCarryDeletion(j,m,i,p);
    if(SerializeSyncCarryJournal(j) && SyncCarryRecordHash(m.bytes)==m.reference.recordHash) {
        auto o=Journal(j); o.insert(u"message"_q,Message(m)); o.insert(u"destination"_q,Inventory(i));
        if(p.status!=SyncPlaylistCarryStatus::NotInUse) o.insert(u"playlist"_q,Playlist(p));
        Emit(u"checkDeletion"_q,o,{{u"status"_q,u"Valid"_q},{u"allowed"_q,r}});
    }
    return r;
}
bool OldMarkDeletion(const SyncCarryJournal &j,const SyncCarryMarkReadback &m,const SyncCarryInventory &i,const SyncPlaylistCarryReceipt &p={}) {
    const auto r=CheckSyncCarryOldMarkDeletion(j,m,i,p);
    if(SerializeSyncCarryJournal(j)) {
        auto o=Journal(j); o.insert(u"mark"_q,Mark(m)); o.insert(u"destination"_q,Inventory(i));
        if(p.status!=SyncPlaylistCarryStatus::NotInUse) o.insert(u"playlist"_q,Playlist(p));
        Emit(u"checkOldMarkDeletion"_q,o,{{u"status"_q,u"Valid"_q},{u"allowed"_q,r}});
    }
    return r;
}

QJsonObject Pending(const SyncCarryPendingResult &r) {
    auto o=QJsonObject{{u"status"_q,Name(r.status)},{u"beforeHash"_q,r.beforeHash},
        {u"stageHash"_q,r.stageHash},{u"stageSeq"_q,QString::number(r.stageSeq)}};
    if(r) o.insert(u"state"_q,State(r.state));
    return o;
}
SyncCarryPendingResult Finish(const AccountKey &a,const SyncLocalState &s,const QByteArray &bytes,const SyncCarryMessage &m) {
    const auto r=FinishPendingStageForCarry(a,s,bytes,m);
    auto input=QJsonObject{{u"account"_q,*AccountKeyText(a.environment,a.userId)},{u"state"_q,State(s)},
        {u"stagedRecord"_q,Blob(bytes)},{u"readback"_q,Message(m)}};
    Emit(u"finishPending"_q,input,Pending(FinishPendingStageForCarry(a,*Reopen(s),bytes,m)));
    return r;
}
SyncCarryPendingResult Abandon(const AccountKey &a,const SyncLocalState &s,const PeerToken &source,SyncChatDirectRead outcome,const QByteArray &bytes,const QByteArray &text) {
    const auto r=AbandonStageForLostChat(a,s,source,outcome,bytes,text);
    auto input=QJsonObject{{u"account"_q,*AccountKeyText(a.environment,a.userId)},{u"source"_q,*PeerTokenText(source)},
        {u"state"_q,State(s)},{u"stagedRecord"_q,Blob(bytes)},{u"retainedLocalText"_q,Blob(text)},{u"oldRead"_q,Name(outcome)}};
    Emit(u"abandonPending"_q,input,Pending(AbandonStageForLostChat(a,*Reopen(s),source,outcome,bytes,text)));
    return r;
}

}
#define BeginSyncCarryJournal Probe::Begin
#define AdvanceSyncCarryJournal Probe::Advance
#define ResumeSyncCarryJournal Probe::Resume
#define NextSyncCarryJournalAction Probe::Next
#define PlanCarry Probe::Plan
#define PrepareSyncCarryCommit Probe::Commit
#define CheckSyncCarryDeletion Probe::Deletion
#define CheckSyncCarryOldMarkDeletion Probe::OldMarkDeletion
#define FinishPendingStageForCarry Probe::Finish
#define AbandonStageForLostChat Probe::Abandon
#define main CoreCarryMain
#include "tests/test_sync_chat_carry.cpp"
#undef main
#undef FinishPendingStageForCarry
#undef AbandonStageForLostChat
#undef PlanCarry
#undef PrepareSyncCarryCommit
#undef CheckSyncCarryDeletion
#undef CheckSyncCarryOldMarkDeletion
#undef BeginSyncCarryJournal
#undef AdvanceSyncCarryJournal
#undef ResumeSyncCarryJournal
#undef NextSyncCarryJournalAction
int main(int argc,char **argv) {
    if(argc!=2) return 2;
    Probe::Output.setFileName(QString::fromLocal8Bit(argv[1]));
    if(!Probe::Output.open(QIODevice::WriteOnly|QIODevice::NewOnly)) return 2;
    PendingContracts();
    CarryContracts();
    JournalContracts();
    CloneAndPlanBinding();
    LegacyCarry();
    ReviewDurability();
    ReviewCleanup();
    ReviewMarkOrdering();
    ReviewDuplicateLedger();
    OldMarkCleanup();
    CrashMatrix();
    SplitStagePersistence();
    FollowAndProtectedSources();
    FillContracts();
    PlaylistHookAndReadback();
    std::printf("%d checks, %d failures\n", Checks, Failures);
    const auto result=Failures ? 1 : 0;
    Probe::Output.close();
    return result;
}
