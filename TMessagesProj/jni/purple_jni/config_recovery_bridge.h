#pragma once

#include "purple/purple_account_files.h"
#include "purple/purple_sync_config_flow.h"
#include <QtCore/QJsonDocument>
#include <QtCore/QJsonObject>
#include <QtCore/QJsonArray>
#include <QtCore/QDateTime>
#include <algorithm>

namespace PurpleAndroid {
inline bool RecoveryBytes(const QJsonValue &value, QByteArray &bytes) {
    if (!value.isString()) return false;
    const auto hex = value.toString().toLatin1();
    if (hex.size() > 8 * 1024 * 1024 || hex.size() % 2 != 0) return false;
    bytes = QByteArray::fromHex(hex);
    return bytes.toHex() == hex;
}
inline bool RecoveryPending(const QJsonValue &value, std::map<QString, QByteArray> &files) {
    if (!value.isObject() || value.toObject().size() > 10000) return false;
    const auto object = value.toObject();
    for (auto i = object.begin(); i != object.end(); ++i) {
        auto bytes = QByteArray();
        if (!RecoveryBytes(i.value(), bytes)) return false;
        files.emplace(i.key(), bytes);
    }
    return true;
}
inline std::optional<Purple::SyncAccountRecoveryInput> ReadRecovery(const QJsonObject &json,
        const Purple::SyncSettingsFile &local, const Purple::SyncAccountInventoryResult *cloud) {
    const auto account = Purple::ParseAccountKey(json.value("account").toString());
    const auto owner = Purple::ParseAccountKey(json.value("owner").toString());
    if (!account || !owner || !json.value("signedIn").isBool() || !json.value("discoveryComplete").isBool()
        || !json.value("preferenceToken").isString() || !json.value("stores").isArray()
        || !json.value("preferences").isArray() || json.value("stores").toArray().size() > 100
        || json.value("preferences").toArray().size() > 1000) return std::nullopt;
    auto input = Purple::SyncAccountRecoveryInput();
    input.account = *account; input.startupOwner = *owner; input.layout = Purple::AccountFileLayout::Android;
    input.signedIn = json.value("signedIn").toBool(); input.discoveryComplete = json.value("discoveryComplete").toBool();
    input.preferenceToken = json.value("preferenceToken").toString().toLatin1(); input.local = local;
    for (const auto value : json.value("stores").toArray()) {
        if (!value.isObject()) return std::nullopt;
        const auto item = value.toObject();
        auto store = Purple::SyncRecoveryStore();
        if (!item.value("directoryAccount").isNull()) {
            store.directoryAccount = Purple::ParseAccountKey(item.value("directoryAccount").toString());
            if (!store.directoryAccount) return std::nullopt;
        }
        if (!item.value("statePresent").isBool() || !item.value("pendingPresent").isBool()
            || !item.value("pendingComplete").isBool()) return std::nullopt;
        store.statePresent = item.value("statePresent").toBool(); store.pendingPresent = item.value("pendingPresent").toBool();
        store.pendingComplete = item.value("pendingComplete").toBool();
        if ((store.statePresent && !RecoveryBytes(item.value("state"), store.state))
            || !RecoveryPending(item.value("pending"), store.pendingFiles)) return std::nullopt;
        input.stores.push_back(std::move(store));
    }
    for (const auto value : json.value("preferences").toArray()) {
        const auto item = value.toObject();
        const auto key = Purple::ParseAccountKey(item.value("account").toString());
        if (!key || !item.value("token").isString()) return std::nullopt;
        input.otherPreferences.push_back({*key, item.value("token").toString().toLatin1()});
    }
    if (json.value("cloud").toBool()) {
        if (!cloud || !json.value("storeSpaceComplete").isBool()) return std::nullopt;
        input.cloud = Purple::SyncRecoveryCloud{*account, json.value("storeSpaceComplete").toBool(), *cloud};
    }
    return input;
}
inline Purple::SyncAccountRecoveryInput RecoveryEvidence(Purple::SyncAccountRecoveryInput input) {
    for (auto &store : input.stores) {
        const auto parsed = store.statePresent ? Purple::ParseSyncLocalState(store.state) : Purple::SyncLocalParseResult();
        if (!parsed || !parsed.state.config.pendingSeq) continue;
        const auto old = store.pendingFiles.find(u"config.json"_q);
        const auto canonical = u"config-%1.json"_q.arg(parsed.state.config.pendingSeq);
        if (old != store.pendingFiles.end() && !store.pendingFiles.contains(canonical)) {
            auto bytes = old->second;
            store.pendingFiles.erase(old);
            store.pendingFiles.emplace(canonical, bytes);
        }
    }
    return input;
}
inline QJsonObject RecoveryStoreJson(const Purple::SyncRecoveryStore &store) {
    auto pending = QJsonObject();
    for (const auto &[name, bytes] : store.pendingFiles) pending.insert(name, QString::fromLatin1(bytes.toHex()));
    return {{"directoryAccount", store.directoryAccount ? QJsonValue(*Purple::AccountKeyText(store.directoryAccount->environment, store.directoryAccount->userId)) : QJsonValue()},
        {"statePresent", store.statePresent}, {"state", QString::fromLatin1(store.state.toHex())},
        {"pendingPresent", store.pendingPresent}, {"pendingComplete", store.pendingComplete}, {"pending", pending}};
}
inline QJsonObject RecoveryJournalJson(const Purple::SyncStartAgainPlan &plan, const QString &owner, const QString &date) {
    return {{"version", 1}, {"account", *Purple::AccountKeyText(plan.account.environment, plan.account.userId)},
        {"owner", owner}, {"date", date}, {"preferenceToken", QString::fromLatin1(plan.preferenceToken)},
        {"expected", RecoveryStoreJson(plan.expected)}};
}
inline std::optional<Purple::SyncStartAgainPlan> RecoveryJournalPlan(const QJsonObject &journal,
        const Purple::SyncSettingsFile &local) {
    if (journal.value("version").toInt() != 1 || !journal.value("expected").isObject()) return std::nullopt;
    auto decoder = journal;
    decoder.insert("signedIn", true); decoder.insert("discoveryComplete", true);
    decoder.insert("stores", QJsonArray{journal.value("expected")}); decoder.insert("preferences", QJsonArray());
    const auto input = ReadRecovery(decoder, local, nullptr);
    if (!input || input->stores.size() != 1 || (!input->preferenceToken.isEmpty() && !Purple::IsSyncBindingToken(input->preferenceToken))) return std::nullopt;
    const auto files = Purple::ResolveAccountFiles(input->account, input->startupOwner, Purple::AccountFileLayout::Android);
    const auto date = journal.value("date").toString();
    const auto stamp = QDateTime::fromString(date, u"yyyyMMdd-HHmmss"_q);
    if (!files || !stamp.isValid() || stamp.toString(u"yyyyMMdd-HHmmss"_q) != date) return std::nullopt;
    const auto &expected = input->stores[0];
    if ((input->account == *input->startupOwner && expected.directoryAccount)
        || (input->account != *input->startupOwner && expected.directoryAccount != std::optional(input->account))) return std::nullopt;
    for (const auto &[name, bytes] : expected.pendingFiles) {
        if (name.isEmpty() || name == u"."_q || name == u".."_q || std::any_of(name.begin(), name.end(), [](QChar ch) {
                return ch == u'/' || ch == u'\\' || ch.unicode() < 0x20 || ch.unicode() == 0x7f;
            })) return std::nullopt;
    }
    auto plan = Purple::SyncStartAgainPlan(); plan.status = Purple::SyncStartAgainStatus::Ready;
    plan.account = input->account; plan.expected = expected; plan.preferenceToken = input->preferenceToken;
    plan.archiveDirectory = files->syncDirectory + u"/orphaned-"_q + date; plan.history = files->history;
    if (expected.pendingPresent) plan.moves.push_back({Purple::SyncOrphanMoveKind::PendingDirectory,
        files->syncDirectory + u"/pending"_q, plan.archiveDirectory + u"/pending"_q});
    if (expected.statePresent) plan.moves.push_back({Purple::SyncOrphanMoveKind::StateFile,
        files->syncStore, plan.archiveDirectory + u"/state.json"_q});
    return plan;
}
inline QJsonObject RecoveryJournalResult(const QJsonObject &request, const Purple::SyncSettingsFile &local) {
    const auto plan = RecoveryJournalPlan(request.value("journalPlan").toObject(), local);
    if (!plan) return {{"invalid", true}};
    auto moves = QJsonArray();
    for (const auto &move : plan->moves) moves.push_back(QJsonObject{{"source", move.source}, {"destination", move.destination}, {"kind", int(move.kind)}});
    auto result = QJsonObject{{"recovery", int(Purple::SyncAccountRecoveryStatus::StartAgain)},
        {"start", int(plan->status)}, {"archive", plan->archiveDirectory}, {"moves", moves}, {"offerStartAgain", true}};
    if (!request.value("observation").isObject()) return result;
    const auto json = request.value("observation").toObject();
    const auto account = Purple::ParseAccountKey(json.value("account").toString());
    if (!account || !json.value("complete").isBool() || !json.value("originalStatePresent").isBool()
        || !json.value("originalPendingPresent").isBool() || !json.value("preferenceToken").isString()) return {{"invalid", true}};
    auto observation = Purple::SyncStartAgainObservation(); observation.account = *account;
    observation.complete = json.value("complete").toBool(); observation.originalStatePresent = json.value("originalStatePresent").toBool();
    observation.originalPendingPresent = json.value("originalPendingPresent").toBool();
    observation.preferenceToken = json.value("preferenceToken").toString().toLatin1();
    if (!json.value("archivedState").isNull()) { auto bytes = QByteArray(); if (!RecoveryBytes(json.value("archivedState"), bytes)) return {{"invalid", true}}; observation.archivedState = bytes; }
    if (!json.value("archivedPending").isNull()) { auto files = std::map<QString,QByteArray>(); if (!RecoveryPending(json.value("archivedPending"), files)) return {{"invalid", true}}; observation.archivedPending = files; }
    const auto complete = Purple::CompleteSyncStartAgain(*plan, observation);
    result.insert("completion", int(complete.status)); result.insert("clearUndo", complete.clearAccountUndo);
    result.insert("bindingStatus", int(complete.binding.status)); result.insert("bindingToken", complete.binding.token);
    return result;
}
inline QJsonObject RecoveryResult(const QJsonObject &request, const Purple::SyncAccountRecoveryInput &original) {
    const auto input = RecoveryEvidence(original);
    const auto recovery = Purple::PlanSyncAccountRecovery(input);
    auto result = QJsonObject{{"recovery", int(recovery.status)}, {"token", recovery.restoreToken},
        {"offerStartAgain", recovery.offerStartAgain}, {"store", recovery.store ? QJsonValue(int(*recovery.store)) : QJsonValue()}};
    if (!request.value("start").toBool()) return result;
    auto start = Purple::SyncStartAgainInput();
    start.recovery = input; start.archiveDate = request.value("date").toString();
    start.archiveAvailable = request.value("archiveAvailable").toBool(); start.confirmed = request.value("confirmed").toBool();
    start.operationInFlight = request.value("busy").toBool();
    start.queue = request.value("sendQueued").toBool() ? Purple::SyncConfigSendQueue::HoldsSyncRecord : Purple::SyncConfigSendQueue::Empty;
    auto plan = Purple::PlanSyncStartAgain(start);
    if (recovery.store && *recovery.store < original.stores.size()) plan.expected = original.stores[*recovery.store];
    auto moves = QJsonArray();
    for (const auto &move : plan.moves) moves.push_back(QJsonObject{{"source", move.source}, {"destination", move.destination}, {"kind", int(move.kind)}});
    result.insert("start", int(plan.status)); result.insert("archive", plan.archiveDirectory); result.insert("moves", moves);
    if (plan.status == Purple::SyncStartAgainStatus::Ready) result.insert("journalPlan", RecoveryJournalJson(plan, request.value("owner").toString(), start.archiveDate));
    if (!request.value("observation").isObject()) return result;
    const auto json = request.value("observation").toObject();
    auto observation = Purple::SyncStartAgainObservation();
    const auto account = Purple::ParseAccountKey(json.value("account").toString());
    if (!account || !json.value("complete").isBool() || !json.value("originalStatePresent").isBool()
        || !json.value("originalPendingPresent").isBool() || !json.value("preferenceToken").isString()) return {{"invalid", true}};
    observation.account = *account; observation.complete = json.value("complete").toBool();
    observation.originalStatePresent = json.value("originalStatePresent").toBool();
    observation.originalPendingPresent = json.value("originalPendingPresent").toBool();
    observation.preferenceToken = json.value("preferenceToken").toString().toLatin1();
    if (!json.value("archivedState").isNull()) {
        auto bytes = QByteArray(); if (!RecoveryBytes(json.value("archivedState"), bytes)) return {{"invalid", true}};
        observation.archivedState = bytes;
    }
    if (!json.value("archivedPending").isNull()) {
        auto files = std::map<QString, QByteArray>(); if (!RecoveryPending(json.value("archivedPending"), files)) return {{"invalid", true}};
        observation.archivedPending = files;
    }
    const auto complete = Purple::CompleteSyncStartAgain(plan, observation);
    result.insert("completion", int(complete.status)); result.insert("clearUndo", complete.clearAccountUndo);
    result.insert("bindingStatus", int(complete.binding.status)); result.insert("bindingToken", complete.binding.token);
    return result;
}
}
