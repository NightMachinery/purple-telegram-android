extern "C" JNIEXPORT jstring JNICALL
Java_org_telegram_messenger_purple_PurpleMediaReply_policyNative(
        JNIEnv *env, jclass, jlong handle) {
    const auto lock = std::lock_guard(mutex);
    const auto i = layers.find(handle);
    return i == layers.end() ? nullptr : Json(env, Purple::MediaReplySettingsJson(i->second.settings));
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
