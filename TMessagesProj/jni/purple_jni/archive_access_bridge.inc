QJsonObject ArchiveLeaseProgram(const ArchiveTransaction &transaction, const ArchiveSqlSlice &slice, const ArchiveSqlSecurity &security) {
    auto result = Program(TranslateArchiveSql(transaction, slice, security));
    auto before = transaction.expected;
    auto after = transaction.next;
    const auto from = before.revision;
    const auto to = after.revision;
    before.leases.clear(); after.leases.clear();
    before.revision = 0; after.revision = 0;
    const auto leaseOnly = bool(transaction) && from != std::numeric_limits<uint64_t>::max()
        && to == from + 1 && before == after;
    result.insert(u"leaseAdvance"_q, QJsonObject{{u"from"_q, QString::number(from)},
        {u"to"_q, QString::number(to)}, {u"leaseOnly"_q, leaseOnly}});
    return result;
}

std::optional<QJsonObject> ArchiveAccessDispatch(const QString &operation, const QJsonObject &args, const Crypto &crypto) {
    if (!operation.startsWith(u"access"_q)) return {};
    auto account = Account(args); auto security = Security(args, crypto);
    if (operation == u"accessLeases"_q) return QJsonObject{{u"status"_q,u"Ready"_q},
        {u"statement"_q,Statement(ArchiveSqlReadPage(ArchiveRecordKind::Lease,128,args.value(u"after"_q).toString()))}};
    if (operation == u"accessLeaseRow"_q) return QJsonObject{{u"status"_q,u"Ready"_q},
        {u"statement"_q,Statement(ArchiveSqlReadRecord(account,ArchiveRecordKind::Lease,Text(args,u"lease"_q),security))}};
    auto slice = Slice(args,account,security); auto key = Key(args.value(u"key"_q).toObject());
    if (key.account != account) throw std::runtime_error("archive access account");
    auto leaseId = Text(args,u"lease"_q);
    if (operation == u"accessRelease"_q) return ArchiveLeaseProgram(PlanArchiveReaderRelease(slice.state,leaseId),slice,security);
    auto versionId = Text(args,u"version"_q), mediaId = Text(args,u"media"_q);
    auto version = FindArchiveVersion(slice.state,key,versionId);
    if (!version) throw std::runtime_error("archive access version missing");
    auto ref = std::find_if(version->media.begin(),version->media.end(),[&](const auto &value){ return value.identity == mediaId; });
    if (ref == version->media.end() || ref->state != ArchiveMediaState::Complete) throw std::runtime_error("archive access media incomplete");
    auto blob = std::find_if(slice.state.blobs.begin(),slice.state.blobs.end(),[&](const auto &value){ return value.id == ref->blobId; });
    if (blob == slice.state.blobs.end() || blob->state != ArchiveBlobState::Complete) throw std::runtime_error("archive access blob incomplete");
    auto generation = ArchiveGenerationFor(slice.state,key.chat);
    if (args.contains(u"expected"_q)) {
        auto expected = args.value(u"expected"_q).toObject();
        if (Text(expected,u"revision"_q) != version->revision || Text(expected,u"hash"_q) != ArchiveContentHash(version->content)
            || expected.value(u"origin"_q).toInt(-1) != int(version->origin) || Text(expected,u"blob"_q) != blob->id
            || Unsigned(expected.value(u"bytes"_q)) != blob->size
            || Unsigned(expected.value(u"accountGeneration"_q)) != generation.account
            || Unsigned(expected.value(u"chatGeneration"_q)) != generation.chat) throw std::runtime_error("archive access source changed");
    }
    if (operation == u"accessAcquire"_q) {
        auto lease = ArchiveReaderLease{leaseId,key,versionId,blob->id,generation,false,{}};
        return ArchiveLeaseProgram(PlanArchiveReaderAcquire(slice.state,lease),slice,security);
    }
    if (operation == u"accessCheck"_q) {
        auto lease = std::find_if(slice.state.leases.begin(),slice.state.leases.end(),[&](const auto &value){ return value.id == leaseId; });
        if (lease == slice.state.leases.end() || lease->revoked || !SameArchiveMessage(lease->key,key)
            || lease->version != versionId || lease->blobId != blob->id || lease->generation != generation)
            throw std::runtime_error("archive access lease revoked");
    } else if (operation != u"accessInfo"_q) throw std::runtime_error("archive access operation");
    return QJsonObject{{u"status"_q,u"Ready"_q},{u"revision"_q,version->revision},{u"hash"_q,ArchiveContentHash(version->content)},
        {u"origin"_q,int(version->origin)},{u"blob"_q,blob->id},{u"bytes"_q,QString::number(blob->size)},
        {u"accountGeneration"_q,QString::number(generation.account)},{u"chatGeneration"_q,QString::number(generation.chat)}};
}
