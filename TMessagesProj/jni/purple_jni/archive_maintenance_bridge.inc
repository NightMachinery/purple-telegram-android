struct MaintenanceRecord {
    ArchiveState header;
    std::optional<ArchiveSqlPurge> purge;
};
ArchiveStoredRow MaintenanceRow(const QJsonObject &value, const AccountKey &account,
        const ArchiveSqlSecurity &security, ArchiveRecordKind expected) {
    auto id = Text(value,u"id"_q); auto frame = Bytes(value.value(u"frame"_q));
    if (value.value(u"kind"_q).toInt(-1) != int(expected) || frame.size() > 65536 || id.size() > 4096) throw std::runtime_error("archive maintenance row bounds");
    auto opened = OpenArchiveEnvelope({account,security.storeId,expected,id.toUtf8()},security.keyId,frame,security.crypto);
    auto decoded = opened ? DecodeArchiveRow(opened.bytes) : ArchiveRowDecodeResult();
    if (!decoded.row || decoded.status != ArchiveCodecStatus::Valid || ArchiveRowKind(*decoded.row) != expected
        || !decoded.row->preserved.isEmpty()) throw std::runtime_error("archive maintenance row authentication");
    return {id,0,*decoded.row};
}
ArchivePurgeScope MaintenanceScope(const QJsonObject &value, const AccountKey &account) {
    auto result = ArchivePurgeScope(); result.account = account;
    auto aliases = value.value(u"aliases"_q);
    if (!aliases.isArray() || aliases.toArray().size() > 31) throw std::runtime_error("archive maintenance aliases");
    if (!value.value(u"chat"_q).isNull()) result.chat = Chat(value.value(u"chat"_q).toObject());
    for (const auto &alias : aliases.toArray()) result.migrationAliases.push_back(Chat(alias.toObject()));
    if (!result.chat && !result.migrationAliases.empty()) throw std::runtime_error("archive maintenance account scope");
    return result;
}
QJsonObject MaintenanceScopeJson(const ArchivePurgeScope &scope) {
    auto chat = [](const ArchiveChatKey &value) { return QJsonObject{{u"kind"_q,int(value.kind)},{u"id"_q,QString::number(value.id)}}; };
    auto aliases = QJsonArray(); for (const auto &value : scope.migrationAliases) aliases.push_back(chat(value));
    return {{u"chat"_q,scope.chat ? QJsonValue(chat(*scope.chat)) : QJsonValue(QJsonValue::Null)},{u"aliases"_q,aliases}};
}
QString MaintenanceScopeId(const ArchiveSqlPurge &purge, const ArchiveSqlSecurity &security) {
    auto input = QByteArray::number(int(purge.kind)) + '/';
    if (purge.chat) {
        input += ArchiveSqlChatId(*purge.chat,purge.account,security).toUtf8();
        for (const auto &alias : purge.migrationAliases) input += '/' + ArchiveSqlChatId(alias,purge.account,security).toUtf8();
    }
    return ArchiveSqlToken(purge.account,ArchiveIndexKind::Maintenance,QByteArray("scope/") + input,security);
}
MaintenanceRecord MaintenanceInspect(const QJsonObject &args, const AccountKey &account,
        const ArchiveSqlSecurity &security) {
    auto stored = MaintenanceRow(args.value(u"header"_q).toObject(),account,security,ArchiveRecordKind::Account);
    auto header = std::get_if<ArchiveState>(&stored.row.value); auto metadata = args.value(u"metadata"_q).toObject();
    if (!header || stored.id != u"account"_q || stored.row.body || header->account != account || !IsArchiveState(*header)
        || !header->messages.empty() || !header->blobs.empty() || !header->jobs.empty() || !header->leases.empty()
        || !header->chatGenerations.empty() || header->revision != Unsigned(metadata.value(u"revision"_q))
        || header->accountGeneration != Unsigned(metadata.value(u"generation"_q))) throw std::runtime_error("archive maintenance header");
    auto result = MaintenanceRecord{*header,{}}; auto id = Text(metadata,u"maintenance"_q);
    if (id.isEmpty()) {
        if (!args.value(u"journal"_q).isNull()) throw std::runtime_error("archive unexpected maintenance journal");
        return result;
    }
    if (!IsArchiveHash(id)) throw std::runtime_error("archive maintenance identity");
    auto journal = args.value(u"journal"_q).toObject();
    auto opened = OpenArchiveEnvelope({account,security.storeId,ArchiveRecordKind::Maintenance,id.toUtf8()},security.keyId,Bytes(journal.value(u"frame"_q)),security.crypto);
    auto purge = opened ? DecodeArchiveSqlPurge(opened.bytes) : std::nullopt;
    if (!purge || purge->account != account || purge->storeId != security.storeId || purge->revision != header->revision
        || purge->accountGeneration != header->accountGeneration || Text(journal,u"id"_q) != id
        || ArchiveSqlToken(account,ArchiveIndexKind::Maintenance,purge->operationId.toUtf8(),security) != id
        || journal.value(u"kind"_q).toInt(-1) != int(purge->kind) || journal.value(u"phase"_q).toInt(-1) != int(purge->phase)
        || Text(journal,u"cursor"_q) != purge->cursor || Text(journal,u"scope"_q) != MaintenanceScopeId(*purge,security)) throw std::runtime_error("archive maintenance journal projection");
    result.purge = *purge; return result;
}
QJsonObject MaintenanceConfirmation(const ArchivePurgeConfirmation &value) {
    return {{u"account"_q,*AccountKeyText(value.epoch.account.environment,value.epoch.account.userId)},
        {u"store"_q,value.epoch.storeId},{u"generation"_q,QString::number(value.epoch.generation)},
        {u"revision"_q,QString::number(value.request.expectedRevision)},{u"accountGeneration"_q,QString::number(value.accountGeneration)},
        {u"kind"_q,int(value.request.kind)},{u"scope"_q,MaintenanceScopeJson(value.request.scope)},{u"token"_q,value.token}};
}
ArchivePurgeKind MaintenanceKind(const QJsonObject &args) {
    auto kind = args.value(u"kind"_q).toInt(-1); if (kind < 0 || kind > 1) throw std::runtime_error("archive purge kind"); return ArchivePurgeKind(kind);
}
ArchivePurgeConfirmation MaintenanceConfirmation(const QJsonObject &value) {
    auto account = Account(value);
    return {{account,Text(value,u"store"_q),Unsigned(value.value(u"generation"_q)),false},
        {MaintenanceKind(value),MaintenanceScope(value.value(u"scope"_q).toObject(),account),Unsigned(value.value(u"revision"_q))},
        Unsigned(value.value(u"accountGeneration"_q)),Text(value,u"token"_q)};
}
QJsonObject MaintenanceProgram(const ArchiveSqlMaintenancePlan &plan) {
    auto result = Program(plan.program); auto jobs = QJsonArray(), readers = QJsonArray();
    for (const auto &id : plan.releaseStagedJobs) jobs.push_back(id);
    for (const auto &id : plan.revokeReaders) readers.push_back(id);
    result.insert(u"processedRows"_q,int(plan.processedRows)); result.insert(u"complete"_q,plan.complete);
    result.insert(u"releaseJobs"_q,jobs); result.insert(u"revokeReaders"_q,readers);
    result.insert(u"phase"_q,int(plan.next.phase)); result.insert(u"cursor"_q,plan.next.cursor); return result;
}
std::optional<QJsonObject> ArchiveMaintenanceDispatch(const QString &operation, const QJsonObject &args, const Crypto &crypto) {
    if (!operation.startsWith(u"maintenance"_q)) return {};
    auto account = Account(args); auto security = Security(args,crypto);
    if (operation == u"maintenanceQuery"_q) {
        auto query = Text(args,u"query"_q); auto statement = ArchiveSqlStatement(); auto id = QString();
        if (query == u"journal"_q) {
            statement = {u"SELECT operation_id,kind,scope_token,phase,cursor,envelope FROM maintenance WHERE singleton=1"_q,{},ArchiveSqlExpectation::Rows}; statement.rows.maximum = 1;
        } else if (query == u"counts"_q) statement = ArchiveSqlScopeCounts({MaintenanceScope(args.value(u"scope"_q).toObject(),account),{},{},{}},security);
        else if (query == u"epoch"_q) {
            auto chat = Chat(args.value(u"chat"_q).toObject()); id = ArchiveSqlChatId(chat,account,security); statement = ArchiveSqlReadChatEpoch(account,chat,security);
        } else if (query == u"copies"_q) statement = ArchiveSqlFindOwnedMedia(account,Text(args,u"identity"_q),Text(args,u"mediaKind"_q),security);
        else if (query == u"garbage"_q) statement = ArchiveSqlGarbagePage(1);
        else if (query == u"cancelledJobs"_q) {
            statement = {u"SELECT job_id,envelope FROM jobs WHERE state=3 AND job_id>? ORDER BY job_id LIMIT 1"_q,{Text(args,u"after"_q)},ArchiveSqlExpectation::Rows}; statement.rows.maximum = 1;
        } else if (query == u"pendingBlob"_q) {
            id = Text(args,u"token"_q); if (!IsArchiveHash(id)) throw std::runtime_error("archive GC blob token");
            statement = {u"SELECT envelope FROM blobs WHERE blob_id=? AND state=1 AND NOT EXISTS(SELECT 1 FROM media_refs WHERE blob_id=?) AND NOT EXISTS(SELECT 1 FROM leases WHERE blob_id=?) AND EXISTS(SELECT 1 FROM archive_meta WHERE singleton=1 AND maintenance_id='')"_q,{id,id,id},ArchiveSqlExpectation::OneRow};
        } else {
            auto record = MaintenanceInspect(args,account,security); if (!record.purge) throw std::runtime_error("archive purge journal absent");
            if (query == u"page"_q) statement = ArchiveSqlPurgePage(*record.purge,128,security);
            else if (query == u"head"_q || query == u"headToken"_q) {
                auto row = MaintenanceRow(args.value(u"message"_q).toObject(),account,security,ArchiveRecordKind::Message);
                auto message = std::get_if<ArchiveMessage>(&row.row.value);
                if (!message || message->key.account != account || ArchiveSqlMessageId(message->key,security) != row.id) throw std::runtime_error("archive purge message identity");
                if (query == u"head"_q) statement = ArchiveSqlPurgeHead(*record.purge,message->key,security);
                else statement = {u"SELECT head_version FROM messages WHERE message_key=?"_q,{row.id},ArchiveSqlExpectation::OneRow};
            } else throw std::runtime_error("archive maintenance query");
        }
        if (statement.sql.isEmpty()) throw std::runtime_error("archive maintenance statement refused");
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"statement"_q,Statement(statement)},{u"id"_q,id}};
    }
    if (operation == u"maintenanceRow"_q) {
        auto value = args.value(u"row"_q).toObject(); auto kind = value.value(u"kind"_q).toInt(-1);
        if (kind < 1 || kind > 8) throw std::runtime_error("archive maintenance row kind");
        auto row = MaintenanceRow(value,account,security,ArchiveRecordKind(kind)); auto encoded = EncodeArchiveRow(row.row);
        if (!encoded) throw std::runtime_error("archive maintenance row encoding");
        auto refs = QJsonArray();
        if (const auto snapshot = std::get_if<ArchiveVersionSnapshot>(&row.row.value)) {
            for (const auto &ref : snapshot->version.media) if (ref.state != ArchiveMediaState::Complete) {
                auto descriptor = std::find_if(snapshot->version.content.media.begin(),snapshot->version.content.media.end(),[&](const auto &entry){return entry.identity == ref.identity;});
                if (descriptor == snapshot->version.content.media.end()) throw std::runtime_error("archive purge media descriptor");
                refs.push_back(QJsonObject{{u"identity"_q,ref.identity},{u"kind"_q,descriptor->kind}});
            }
        }
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"row"_q,Encoded(*encoded)},{u"refs"_q,refs}};
    }
    if (operation == u"maintenanceCancelled"_q) {
        auto row = MaintenanceRow(args.value(u"row"_q).toObject(),account,security,ArchiveRecordKind::Job);
        auto job = std::get_if<ArchiveMediaJob>(&row.row.value);
        if (!job || job->key.account != account || job->state != ArchiveJobState::Cancelled
            || ArchiveSqlToken(account,ArchiveIndexKind::Job,job->id.toUtf8(),security) != row.id) throw std::runtime_error("archive cancelled job proof");
        auto id = QString();
        if (job->expectedSize) {
            auto epoch = ArchiveStoreEpoch{account,security.storeId,Unsigned(args.value(u"workGeneration"_q)),false};
            auto binding = ArchiveStagingBinding{{epoch,job->key,job->version,job->revision,job->contentHash,job->mediaIdentity,job->generation},job->id,*job->expectedSize,
                job->expectedHash.isEmpty() ? std::nullopt : std::optional(job->expectedHash)};
            id = ArchiveStagingBindingId(binding,security); if (!IsArchiveHash(id)) throw std::runtime_error("archive cancelled staging identity");
        }
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"staging"_q,id}};
    }
    auto record = MaintenanceInspect(args,account,security);
    if (operation == u"maintenanceInspect"_q) {
        auto output = QJsonObject{{u"status"_q,u"Ready"_q},{u"pending"_q,record.purge.has_value()},
            {u"revision"_q,QString::number(record.header.revision)},{u"generation"_q,QString::number(record.header.accountGeneration)}};
        if (record.purge) { output.insert(u"phase"_q,int(record.purge->phase)); output.insert(u"cursor"_q,record.purge->cursor); output.insert(u"operation"_q,record.purge->operationId); output.insert(u"kind"_q,int(record.purge->kind)); }
        return output;
    }
    if (operation == u"maintenancePrepare"_q || operation == u"maintenanceBegin"_q) {
        if (record.purge) throw std::runtime_error("archive maintenance already active");
        auto scope = MaintenanceScope(args.value(u"scope"_q).toObject(),account); auto kind = MaintenanceKind(args);
        auto epoch = ArchiveStoreEpoch{account,security.storeId,Unsigned(args.value(u"workGeneration"_q)),false};
        if (operation == u"maintenancePrepare"_q) {
            auto confirmation = MakeArchivePurgeConfirmation(record.header,epoch,kind,scope);
            return confirmation ? QJsonObject{{u"status"_q,u"Ready"_q},{u"confirmation"_q,MaintenanceConfirmation(*confirmation)}} : QJsonObject{{u"status"_q,u"Invalid"_q}};
        }
        auto request = ConfirmArchivePurge(record.header,epoch,kind,scope,MaintenanceConfirmation(args.value(u"confirmation"_q).toObject()));
        if (!request) throw std::runtime_error("archive confirmation stale");
        auto epochs = std::vector<ArchiveChatGeneration>(); auto input = args.value(u"epochs"_q);
        if (!input.isArray() || input.toArray().size() > 32) throw std::runtime_error("archive purge epochs");
        for (const auto &entry : input.toArray()) {
            auto value = entry.toObject(); auto chat = Chat(value.value(u"chat"_q).toObject()); auto generation = uint64_t(0);
            if (!value.value(u"row"_q).isNull()) {
                auto row = MaintenanceRow(value.value(u"row"_q).toObject(),account,security,ArchiveRecordKind::Generation);
                auto observed = std::get_if<ArchiveChatGeneration>(&row.row.value);
                if (!observed || !SameArchiveChat(observed->chat,chat) || ArchiveSqlChatId(chat,account,security) != row.id
                    || observed->generation != Unsigned(value.value(u"generation"_q))) throw std::runtime_error("archive purge generation proof");
                generation = observed->generation;
            }
            epochs.push_back({chat,generation,{}});
        }
        return MaintenanceProgram(BeginArchiveSqlPurge(record.header,*request,epochs,Text(args,u"operationId"_q),security));
    }
    if (operation == u"maintenanceContinue"_q) {
        if (!record.purge) throw std::runtime_error("archive purge absent");
        auto values = args.value(u"rows"_q); if (!values.isArray() || values.toArray().size() > 128) throw std::runtime_error("archive purge page bound");
        auto kind = record.purge->phase == ArchivePurgePhase::Versions ? ArchiveRecordKind::Version : record.purge->phase == ArchivePurgePhase::Messages ? ArchiveRecordKind::Message
            : record.purge->phase == ArchivePurgePhase::Jobs ? ArchiveRecordKind::Job : ArchiveRecordKind::Lease;
        auto rows = std::vector<ArchiveSqlPurgeRow>();
        for (const auto &entry : values.toArray()) {
            auto value = entry.toObject(); auto target = value.value(u"row"_q).toObject();
            auto input = ArchiveSqlPurgeRow{MaintenanceRow(target,account,security,kind),Bytes(target.value(u"frame"_q)),{},{},{}};
            if (value.value(u"head"_q).isObject()) {
                auto head = value.value(u"head"_q).toObject(); auto stored = MaintenanceRow(head,account,security,ArchiveRecordKind::Version);
                input.head = std::get<ArchiveVersionSnapshot>(stored.row.value); input.headFrame = Bytes(head.value(u"frame"_q));
            }
            auto copies = value.value(u"copies"_q); if (!copies.isArray() || copies.toArray().size() > 128) throw std::runtime_error("archive owned-copy bound");
            for (const auto &item : copies.toArray()) {
                auto copy = item.toObject(); auto owner = copy.value(u"owner"_q).toObject(), blob = copy.value(u"blob"_q).toObject();
                auto owned = MaintenanceRow(owner,account,security,ArchiveRecordKind::Version); auto content = MaintenanceRow(blob,account,security,ArchiveRecordKind::Blob);
                auto version = std::get_if<ArchiveVersionSnapshot>(&owned.row.value); auto bytes = std::get_if<ArchiveBlob>(&content.row.value);
                if (!version || !bytes || version->key.account != account || bytes->state != ArchiveBlobState::Complete
                    || ArchiveSqlChildId(version->key,ArchiveIndexKind::Version,version->version.id,security) != owned.id
                    || ArchiveSqlToken(account,ArchiveIndexKind::Blob,bytes->id.toUtf8(),security) != content.id) throw std::runtime_error("archive copy owner/blob proof");
                input.copies.push_back({Text(copy,u"identity"_q),Text(copy,u"kind"_q),bytes->id,owned.id,Bytes(owner.value(u"frame"_q)),Bytes(blob.value(u"frame"_q)),Signed(copy.value(u"position"_q))});
            }
            rows.push_back(std::move(input));
        }
        auto plan = ContinueArchiveSqlPurge(*record.purge,rows,security);
        if (plan.program && (plan.processedRows > rows.size() || (!rows.empty() && !plan.processedRows))) throw std::runtime_error("archive purge committed prefix");
        return MaintenanceProgram(plan);
    }
    if (operation == u"maintenanceGarbage"_q || operation == u"maintenanceGarbageComplete"_q) {
        if (record.purge) throw std::runtime_error("archive GC during purge");
        auto value = args.value(u"blob"_q).toObject(); auto stored = MaintenanceRow(value,account,security,ArchiveRecordKind::Blob);
        auto blob = std::get_if<ArchiveBlob>(&stored.row.value);
        if (!blob || stored.row.body || ArchiveSqlToken(account,ArchiveIndexKind::Blob,blob->id.toUtf8(),security) != stored.id) throw std::runtime_error("archive GC blob identity");
        auto plan = operation == u"maintenanceGarbage"_q ? PlanArchiveSqlGarbage(record.header,{*blob},security) : CompleteArchiveSqlGarbage(record.header,{*blob},security);
        auto result = Program(plan.program); auto removed = QJsonArray(); for (const auto &id : plan.deleteOwnedBlobs) removed.push_back(id);
        result.insert(u"deleteBlobs"_q,removed); result.insert(u"cursor"_q,plan.cursor); return result;
    }
    throw std::runtime_error("archive maintenance operation");
}
