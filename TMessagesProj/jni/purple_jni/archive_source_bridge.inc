std::optional<QJsonObject> ArchiveSourceDispatch(const QString &operation, const QJsonObject &args, const Crypto &crypto) {
    if (operation != u"sourceFacts"_q && operation != u"sourceChoose"_q && operation != u"sourceBind"_q) return {};
    auto account = Account(args); auto security = Security(args,crypto); auto slice = Slice(args,account,security);
    auto key = Key(args.value(u"key"_q).toObject());
    if (key.account != account) throw std::runtime_error("archive source account");
    auto versionId = Text(args,u"version"_q);
    auto version = FindArchiveVersion(slice.state,key,versionId);
    auto message = FindArchiveMessage(slice.state,key);
    if (!message || !version || message->payloadPurged) return QJsonObject{{u"status"_q,u"Invalid"_q}};
    if (operation == u"sourceFacts"_q) {
        auto generation = ArchiveGenerationFor(slice.state,key.chat);
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"version"_q,version->id},{u"revision"_q,version->revision},
            {u"hash"_q,ArchiveContentHash(version->content)}, {u"accountGeneration"_q,QString::number(generation.account)},
            {u"chatGeneration"_q,QString::number(generation.chat)}, {u"partial"_q,version->completeness != ArchiveCompleteness::Complete}};
    }
    auto request = ArchiveCopyRequest();
    request.key = key; request.version = versionId; request.revision = Text(args,u"revision"_q);
    request.contentHash = Text(args,u"hash"_q); request.displayedRepresentation = Text(args,u"representation"_q);
    request.generation = {Unsigned(args.value(u"accountGeneration"_q)),Unsigned(args.value(u"chatGeneration"_q))};
    if (operation == u"sourceChoose"_q) {
        auto extent = args.value(u"extent"_q).toInt(-1); auto target = args.value(u"target"_q).toInt(-1);
        if (extent < 0 || extent > 2 || target < 0 || target > 1) throw std::runtime_error("archive copy scope");
        request.extent = ArchiveCopyExtent(extent); request.target = ArchiveCopyTarget(target);
        auto choice = ChooseCopySource(slice.state,request);
        return QJsonObject{{u"status"_q,choice.validTarget ? u"Ready"_q : u"Invalid"_q},
            {u"kind"_q,int(choice.kind)}, {u"partial"_q,choice.partial}, {u"bytes"_q,Encoded(choice.bytes)}};
    }
    auto input = args.value(u"source"_q).toObject(); auto kind = input.value(u"kind"_q).toInt(-1);
    if (kind < 0 || kind > 1) throw std::runtime_error("archive source kind");
    auto source = ArchiveSource(); source.kind = ArchiveSourceKind(kind); source.bytes = Bytes(input.value(u"bytes"_q));
    if (source.bytes.size() > 1024 * 1024) throw std::runtime_error("archive source bytes");
    source.contentHash = request.contentHash; source.revision = request.revision; source.representation = request.displayedRepresentation;
    source.binding = ArchiveSourceBinding{key,versionId,{},{},{}};
    if (!input.value(u"localMessageId"_q).isNull() && !input.value(u"localMessageId"_q).isUndefined()) source.binding->localMessageId = Signed(input.value(u"localMessageId"_q));
    if (!input.value(u"randomId"_q).isNull() && !input.value(u"randomId"_q).isUndefined()) source.binding->randomId = Signed(input.value(u"randomId"_q));
    auto event = ArchiveEvent(); event.origin = ArchiveOrigin::SendMetadata; event.key = key;
    event.targetVersion = versionId; event.targetRevision = request.revision; event.expectedContentHash = request.contentHash;
    event.observation.representation = key.representation; event.generation = request.generation;
    auto identity = QJsonObject{{u"key"_q,args.value(u"key"_q)}, {u"version"_q,versionId}, {u"revision"_q,request.revision},
        {u"hash"_q,request.contentHash}, {u"representation"_q,request.displayedRepresentation}, {u"source"_q,input},
        {u"accountGeneration"_q,QString::number(request.generation.account)}, {u"chatGeneration"_q,QString::number(request.generation.chat)}};
    event.id = u"source_"_q + QString::fromLatin1(QCryptographicHash::hash(QJsonDocument(identity).toJson(QJsonDocument::Compact),QCryptographicHash::Sha256).toHex());
    event.source = std::move(source);
    if (kind == int(ArchiveSourceKind::ReceivedMarkdownFile) && version != &message->versions.back()) {
        if (version->source && *version->source != *event.source) return QJsonObject{{u"status"_q,u"Invalid"_q}};
        event.origin = ArchiveOrigin::Hydration;
        event.content = version->content;
        event.completeness = version->completeness;
        event.missing = version->missing;
        event.observation = version->observation;
    }
    auto epoch = ArchiveStoreEpoch{account,security.storeId,Unsigned(args.value(u"workGeneration"_q)),false};
    auto plan = PlanArchiveSqlCapture(slice,event,security,&epoch); auto output = Program(plan.program);
    output.insert(u"modelStatus"_q,int(plan.transaction.status));
    return output;
}
