extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_policyNative(
        JNIEnv *env, jclass, jlong handle) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    if (i == layers.end()) return nullptr;
    const auto &settings = i->second.settings;
    auto enabled = false;
    const auto inspect = [&](Purple::PeerToken peer, Purple::ChatKind kind) {
        const auto value = Purple::ResolveMediaReply(settings, peer, kind);
        enabled = enabled || value.voice != Purple::MediaReplyMode::Off || value.round != Purple::MediaReplyMode::Off;
    };
    inspect({Purple::PeerTokenKind::User, 0}, Purple::ChatKind::Private);
    inspect({Purple::PeerTokenKind::Chat, 0}, Purple::ChatKind::Group);
    inspect({Purple::PeerTokenKind::Channel, 0}, Purple::ChatKind::Channel);
    for (const auto &set : settings.mediaReply.sets) for (const auto &peer : set.peers) {
        if (peer.kind == Purple::PeerTokenKind::User) inspect(peer, Purple::ChatKind::Private);
        else if (peer.kind == Purple::PeerTokenKind::Chat) inspect(peer, Purple::ChatKind::Group);
        else { inspect(peer, Purple::ChatKind::Group); inspect(peer, Purple::ChatKind::Channel); }
    }
    auto result = Purple::MediaReplySettingsJson(settings);
    result.insert("enabled", enabled);
    auto defaults = result.value("defaults").toArray();
    const auto kinds = std::array{Purple::ChatKind::Private, Purple::ChatKind::Group, Purple::ChatKind::Channel};
    const auto peers = std::array{Purple::PeerToken{Purple::PeerTokenKind::User, 0},
        Purple::PeerToken{Purple::PeerTokenKind::Chat, 0}, Purple::PeerToken{Purple::PeerTokenKind::Channel, 0}};
    for (auto index = 0; index != 3; ++index) {
        const auto value = Purple::ResolveMediaReply(settings, peers[index], kinds[index]);
        auto row = defaults[index].toObject();
        row.insert("effective_voice", int(value.voice)); row.insert("effective_round", int(value.round));
        row.insert("effective_follow", value.roundFollow); defaults[index] = row;
    }
    result.insert("defaults", defaults);
    return Json(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_planNative(
        JNIEnv *env, jclass, jlong handle, jstring account, jstring peer,
        jint kind, jint id, jint topic, jlong date, jint media, jboolean own,
        jboolean ack, jlong cutoff, jlong revision) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    const auto target = Purple::ParsePeerToken(Text(env, peer));
    if (i == layers.end() || !target || kind < 0 || kind > 3 || media < 1 || media > 2) return nullptr;
    const auto message = Purple::MediaReplyMessage{
        Text(env, account), *target, Purple::ChatKind(kind), id, topic, date,
        Purple::MediaReplyMedia(media), ack ? Purple::MediaReplyOrigin::OutgoingAck
            : Purple::MediaReplyOrigin::LiveNew, bool(own), true, false };
    const auto action = Purple::PlanMediaReply(i->second.settings, message, message.account, cutoff, revision);
    if (!action) return nullptr;
    const auto journal = Purple::MediaReplyJournal{message.account, cutoff, uint64_t(revision), {*action}};
    auto object = QJsonDocument::fromJson(Purple::EncodeMediaReplyJournal(journal)).object();
    object.insert("randomId", QString::number(action->randomId));
    return Json(env, object);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_reserveNative(
        JNIEnv *env, jclass, jstring previous, jstring candidate) {
    const auto bytes = Text(env, candidate).toUtf8();
    const auto account = QJsonDocument::fromJson(bytes).object().value("account").toString();
    const auto proposed = Purple::DecodeMediaReplyJournal(bytes, account);
    if (!proposed || proposed->reserved.size() != 1) return nullptr;
    auto journal = Purple::MediaReplyJournal{account, proposed->cutoff, proposed->revision, {}};
    if (previous) {
        const auto decoded = Purple::DecodeMediaReplyJournal(Text(env, previous).toUtf8(), account);
        if (!decoded) return nullptr;
        journal.reserved = decoded->reserved;
    }
    if (!Purple::ReserveMediaReply(journal, proposed->reserved.front())) return nullptr;
    return Json(env, QJsonDocument::fromJson(Purple::EncodeMediaReplyJournal(journal)).object());
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleCore_mediaReplySpliceNative(
        JNIEnv *env, jclass, jbyteArray settings, jstring row, jstring key, jstring value) {
    const auto json = QJsonDocument::fromJson(Text(env, value).toUtf8());
    if (!json.isArray() || json.array().size() != 1) return nullptr;
    const auto result = Purple::SetMediaReplyValue(Bytes(env, settings), u"settings.toml"_q,
        Text(env, row), Text(env, key), json.array().first());
    return Json(env, {{"text", result.text}, {"changed", result.changed}, {"error", result.error}});
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_resolvedNative(
        JNIEnv *env, jclass, jlong handle, jstring peer, jint kind) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    const auto token = Purple::ParsePeerToken(Text(env, peer));
    if (i == layers.end() || !token || kind < 0 || kind > 3) return nullptr;
    const auto resolved = Purple::ResolveMediaReply(i->second.settings, *token, Purple::ChatKind(kind));
    return Json(env, {{"voice", int(resolved.voice)}, {"round", int(resolved.round)},
        {"roundFollow", resolved.roundFollow}, {"set", resolved.set}});
}

extern "C" JNIEXPORT jint JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_journalCountNative(
        JNIEnv *env, jclass, jstring journal, jstring account) {
    if (!journal) return 0;
    const auto value = Purple::DecodeMediaReplyJournal(Text(env, journal).toUtf8(), Text(env, account));
    return value ? int(value->reserved.size()) : -1;
}
