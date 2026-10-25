ArchiveViewScope ViewScope(const QJsonObject &args, const AccountKey &account) {
    auto value = args.value(u"scope"_q).toObject(); auto result = ArchiveViewScope();
    result.messages.account = account; result.messages.chat = Chat(value.value(u"chat"_q).toObject());
    auto aliases = value.value(u"aliases"_q);
    if (!aliases.isArray() || aliases.toArray().size() > 31) throw std::runtime_error("archive view aliases");
    for (const auto &alias : aliases.toArray()) result.messages.migrationAliases.push_back(Chat(alias.toObject()));
    if (value.contains(u"topic"_q) && !value.value(u"topic"_q).isNull()) {
        auto topic = value.value(u"topic"_q).toObject(); result.topic = ArchiveTopicScope{Chat(topic.value(u"chat"_q).toObject()),Signed(topic.value(u"id"_q))};
    }
    if (value.contains(u"collection"_q) && !value.value(u"collection"_q).isNull()) {
        auto kind = value.value(u"collection"_q).toInt(-1); if (kind < 0 || kind > 2) throw std::runtime_error("archive view collection");
        result.collection = ArchiveCollection(kind);
    }
    if (value.contains(u"representation"_q) && !value.value(u"representation"_q).isNull()) result.representation = Text(value,u"representation"_q);
    return result;
}
std::optional<QJsonObject> ArchiveQueryDispatch(const QString &operation, const QJsonObject &args, const Crypto &crypto) {
    if (!operation.startsWith(u"view"_q)) return {};
    auto account = Account(args); auto security = Security(args,crypto); auto scope = ViewScope(args,account);
    if (ArchiveSqlDeletedView(scope,1,security).sql.isEmpty()) throw std::runtime_error("archive view scope");
    if (operation == u"viewPlan"_q) {
        auto query = Text(args,u"query"_q); auto statement = ArchiveSqlStatement(); auto id = QString();
        if (query == u"deleted"_q) {
            auto cursor = std::optional<ArchiveTranscriptCursor>();
            if (args.value(u"cursor"_q).isObject()) {
                auto value = args.value(u"cursor"_q).toObject(); cursor = ArchiveTranscriptCursor{Signed(value.value(u"date"_q)),Signed(value.value(u"id"_q)),Text(value,u"token"_q)};
            }
            auto order = args.value(u"order"_q).toInt(-1); if (order < 0 || order > 1) throw std::runtime_error("archive view order");
            statement = ArchiveSqlDeletedView(scope,args.value(u"limit"_q).toInt(-1),security,cursor,ArchiveTranscriptOrder(order));
        } else if (query == u"counts"_q) statement = ArchiveSqlScopeCounts(scope,security);
        else if (query == u"blobs"_q) statement = ArchiveSqlScopeBlobs(scope,args.value(u"limit"_q).toInt(-1),security,Text(args,u"after"_q));
        else if (query == u"epoch"_q) {
            auto chat = Chat(args.value(u"chat"_q).toObject());
            if (!SameArchiveChat(chat,*scope.messages.chat) && std::none_of(scope.messages.migrationAliases.begin(),scope.messages.migrationAliases.end(),[&](const auto &alias){return SameArchiveChat(alias,chat);})) throw std::runtime_error("archive view epoch scope");
            statement = ArchiveSqlReadChatEpoch(account,chat,security); id = ArchiveSqlChatId(chat,account,security);
        } else throw std::runtime_error("archive view query");
        if (statement.sql.isEmpty()) throw std::runtime_error("archive view statement");
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"statement"_q,Statement(statement)},{u"id"_q,id}};
    }
    if (operation == u"viewEpoch"_q) {
        auto stored = Row(args.value(u"row"_q).toObject(),account,security); auto epoch = std::get_if<ArchiveChatGeneration>(&stored.row.value);
        auto chat = Chat(args.value(u"chat"_q).toObject());
        if (!epoch || !SameArchiveChat(epoch->chat,chat) || stored.id != ArchiveSqlChatId(chat,account,security)
            || epoch->generation != Unsigned(args.value(u"generation"_q))) throw std::runtime_error("archive view generation");
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"generation"_q,QString::number(epoch->generation)}};
    }
    if (operation == u"viewBlob"_q) {
        auto stored = Row(args.value(u"row"_q).toObject(),account,security); auto blob = std::get_if<ArchiveBlob>(&stored.row.value);
        if (!blob || blob->state != ArchiveBlobState::Complete || stored.id != ArchiveSqlToken(account,ArchiveIndexKind::Blob,blob->id.toUtf8(),security)
            || Text(args,u"bytes"_q) != QString::number(blob->size)) throw std::runtime_error("archive view blob");
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"bytes"_q,QString::number(blob->size)}};
    }
    if (operation == u"viewEntry"_q) {
        auto shell = Row(args.value(u"shell"_q).toObject(),account,security); auto message = std::get_if<ArchiveMessage>(&shell.row.value);
        auto token = Text(args,u"token"_q); auto id = Signed(args.value(u"messageId"_q)); auto date = Signed(args.value(u"date"_q));
        if (!message || date < 0 || !id || id != message->key.messageId || shell.id != token || ArchiveSqlMessageId(message->key,security) != token) throw std::runtime_error("archive view shell");
        auto headId = Text(args,u"headId"_q); auto head = std::optional<ArchiveStoredRow>();
        const ArchiveVersionSnapshot *snapshot = nullptr;
        if (!headId.isEmpty()) {
            head = Row(args.value(u"head"_q).toObject(),account,security); snapshot = std::get_if<ArchiveVersionSnapshot>(&head->row.value);
            if (!snapshot || head->id != token + '/' + headId || head->id != ArchiveSqlChildId(message->key,ArchiveIndexKind::Version,snapshot->version.id,security)) throw std::runtime_error("archive view head");
        } else if (!args.value(u"head"_q).isNull()) throw std::runtime_error("archive view unexpected head");
        if (!ArchiveDeletedViewMatches(scope,*message,snapshot)) throw std::runtime_error("archive view membership");
        auto encodedShell = EncodeArchiveRow(shell.row); auto encodedHead = head ? EncodeArchiveRow(head->row) : std::optional<QByteArray>();
        if (!encodedShell || (head && !encodedHead)) throw std::runtime_error("archive view row encoding");
        return QJsonObject{{u"status"_q,u"Ready"_q},{u"deletedStatus"_q,int(ArchiveMessageDeletedStatus(*message))},
            {u"shell"_q,Encoded(*encodedShell)},{u"head"_q,encodedHead ? QJsonValue(Encoded(*encodedHead)) : QJsonValue(QJsonValue::Null)}};
    }
    throw std::runtime_error("archive view operation");
}
