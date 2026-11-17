#include <jni.h>
#include "purple/purple_playlists_ops.h"
#include "purple/purple_playlists_local.h"
#include <QtCore/QStringDecoder>
#include <limits>
#include <new>
#include <stdexcept>

namespace {
using namespace Purple;
constexpr auto Prefix = "org/telegram/messenger/purple/PurplePlaylistsView";

const char *LibraryError(PlaylistLibraryError error) {
    switch (error) {
    case PlaylistLibraryError::None: return "None";
    case PlaylistLibraryError::InvalidJson: return "InvalidJson";
    case PlaylistLibraryError::SizeLimit: return "SizeLimit";
    case PlaylistLibraryError::MissingField: return "MissingField";
    case PlaylistLibraryError::UnknownKey: return "UnknownKey";
    case PlaylistLibraryError::FieldType: return "FieldType";
    case PlaylistLibraryError::InvalidFormat: return "InvalidFormat";
    case PlaylistLibraryError::InvalidId: return "InvalidId";
    case PlaylistLibraryError::InvalidDocumentId: return "InvalidDocumentId";
    case PlaylistLibraryError::InvalidPeerToken: return "InvalidPeerToken";
    case PlaylistLibraryError::InvalidUsername: return "InvalidUsername";
    case PlaylistLibraryError::InvalidTitle: return "InvalidTitle";
    case PlaylistLibraryError::InvalidMeta: return "InvalidMeta";
    case PlaylistLibraryError::InvalidStamp: return "InvalidStamp";
    case PlaylistLibraryError::InvalidSince: return "InvalidSince";
    case PlaylistLibraryError::InvalidOrder: return "InvalidOrder";
    case PlaylistLibraryError::InvalidAlt: return "InvalidAlt";
    case PlaylistLibraryError::InvalidPurged: return "InvalidPurged";
    case PlaylistLibraryError::InvalidValue: return "InvalidValue";
    case PlaylistLibraryError::EmptyPlaylist: return "EmptyPlaylist";
    case PlaylistLibraryError::PurgedPlaylistPresent: return "PurgedPlaylistPresent";
    case PlaylistLibraryError::InvalidSelfUserId: return "InvalidSelfUserId";
    }
    throw std::logic_error("Unknown playlist error");
}

const char *LocalError(PlaylistLocalError error) {
    switch (error) {
    case PlaylistLocalError::None: return "None";
    case PlaylistLocalError::InvalidJson: return "InvalidJson";
    case PlaylistLocalError::SizeLimit: return "SizeLimit";
    case PlaylistLocalError::FieldType: return "FieldType";
    case PlaylistLocalError::MissingField: return "MissingField";
    case PlaylistLocalError::InvalidFormat: return "InvalidFormat";
    case PlaylistLocalError::InvalidId: return "InvalidId";
    case PlaylistLocalError::InvalidStamp: return "InvalidStamp";
    case PlaylistLocalError::InvalidRecentUse: return "InvalidRecentUse";
    case PlaylistLocalError::InvalidReason: return "InvalidReason";
    case PlaylistLocalError::InvalidSnapshot: return "InvalidSnapshot";
    case PlaylistLocalError::InvalidPlaylist: return "InvalidPlaylist";
    case PlaylistLocalError::InvalidPreservedJson: return "InvalidPreservedJson";
    case PlaylistLocalError::InvalidSelfUserId: return "InvalidSelfUserId";
    case PlaylistLocalError::InvalidChat: return "InvalidChat";
    case PlaylistLocalError::InvalidMemo: return "InvalidMemo";
    case PlaylistLocalError::InvalidStatus: return "InvalidStatus";
    case PlaylistLocalError::InvalidUnsyncedSince: return "InvalidUnsyncedSince";
    }
    throw std::logic_error("Unknown local playlist error");
}


jsize Size(std::size_t size) {
    if (size > std::size_t(std::numeric_limits<jsize>::max())) throw std::bad_alloc();
    return jsize(size);
}

QByteArray Read(JNIEnv *env, jbyteArray value) {
    if (!value) return {};
    const auto size = env->GetArrayLength(value);
    if (env->ExceptionCheck()) return {};
    auto bytes = QByteArray(size, Qt::Uninitialized);
    if (size) env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

jbyteArray Bytes(JNIEnv *env, const QString &value) {
    const auto bytes = value.toUtf8();
    const auto result = env->NewByteArray(Size(bytes.size()));
    if (!result) return nullptr;
    if (!bytes.isEmpty()) env->SetByteArrayRegion(result, 0, Size(bytes.size()),
        reinterpret_cast<const jbyte*>(bytes.constData()));
    return env->ExceptionCheck() ? nullptr : result;
}

jclass Class(JNIEnv *env, const char *suffix) {
    return env->FindClass((QByteArray(Prefix) + suffix).constData());
}

jobject Source(JNIEnv *env, const PeerToken &peer, int32_t message, const QString &username, uint64_t self) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = Class(env, "$Source");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto ctor = env->GetMethodID(type, "<init>", "([BI[B)V");
    if (!ctor) return env->PopLocalFrame(nullptr);
    const auto token = PeerTokenText(peer, self);
    if (!token) throw std::logic_error("Invalid projected source");
    const auto p = Bytes(env, *token);
    if (!p) return env->PopLocalFrame(nullptr);
    const auto u = Bytes(env, username);
    if (!u) return env->PopLocalFrame(nullptr);
    return env->PopLocalFrame(env->NewObject(type, ctor, p, jint(message), u));
}

jobject Song(JNIEnv *env, uint64_t id, const PlaylistSource &source, uint64_t self) {
    if (env->PushLocalFrame(16) < 0) return nullptr;
    const auto type = Class(env, "$Song");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto ctor = env->GetMethodID(type, "<init>",
        "([B[B[BILorg/telegram/messenger/purple/PurplePlaylistsView$Source;[Lorg/telegram/messenger/purple/PurplePlaylistsView$Source;)V");
    if (!ctor) return env->PopLocalFrame(nullptr);
    const auto sourceType = Class(env, "$Source");
    if (!sourceType) return env->PopLocalFrame(nullptr);
    const auto alt = env->NewObjectArray(Size(source.alt.size()), sourceType, nullptr);
    if (!alt) return env->PopLocalFrame(nullptr);
    for (auto i = std::size_t(0); i != source.alt.size(); ++i) {
        const auto &value = source.alt[i];
        const auto item = Source(env, value.peer, value.msg, value.username, self);
        if (!item) return env->PopLocalFrame(nullptr);
        env->SetObjectArrayElement(alt, Size(i), item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    const auto primary = Source(env, source.peer, source.msg, source.username, self);
    if (!primary) return env->PopLocalFrame(nullptr);
    const auto formatted = FormatPlaylistDocumentId(id);
    if (!formatted) throw std::logic_error("Invalid projected document");
    const auto doc = Bytes(env, *formatted);
    if (!doc) return env->PopLocalFrame(nullptr);
    const auto title = Bytes(env, source.meta.title);
    if (!title) return env->PopLocalFrame(nullptr);
    const auto performer = Bytes(env, source.meta.performer);
    if (!performer) return env->PopLocalFrame(nullptr);
    return env->PopLocalFrame(env->NewObject(type, ctor, doc, title, performer, jint(source.meta.duration), primary, alt));
}

jobject Row(JNIEnv *env, const QString &id, const Playlist &playlist, const PlaylistLocalState &local) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = Class(env, "$Row");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto ctor = env->GetMethodID(type, "<init>", "([B[BZZJI)V");
    if (!ctor) return env->PopLocalFrame(nullptr);
    const auto key = Bytes(env, id);
    if (!key) return env->PopLocalFrame(nullptr);
    const auto title = Bytes(env, playlist.title ? playlist.title->value : QString());
    if (!title) return env->PopLocalFrame(nullptr);
    const auto recent = local.recentUse.find(id);
    return env->PopLocalFrame(env->NewObject(type, ctor, key, title,
        jboolean(playlist.deleted && playlist.deleted->value), jboolean(playlist.isPartial()),
        jlong(recent == local.recentUse.end() ? 0 : recent->second), jint(Size(PlaylistVisibleSongIds(playlist).size()))));
}

jobjectArray Rows(JNIEnv *env, const std::vector<QString> &ids, const PlaylistLibrary &library,
        const PlaylistLocalState &local) {
    const auto type = Class(env, "$Row");
    if (!type) return nullptr;
    const auto result = env->NewObjectArray(Size(ids.size()), type, nullptr);
    env->DeleteLocalRef(type);
    if (!result) return nullptr;
    for (auto i = std::size_t(0); i != ids.size(); ++i) {
        const auto item = Row(env, ids[i], library.playlists.at(ids[i]), local);
        if (!item) return nullptr;
        env->SetObjectArrayElement(result, Size(i), item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}

jobject Detail(JNIEnv *env, const QString &id, const PlaylistLibrary &library, const PlaylistLocalState &local, uint64_t self) {
    const auto found = library.playlists.find(id);
    if (found == library.playlists.end() || library.purged.contains(id)) return nullptr;
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = Class(env, "$Detail");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto ctor = env->GetMethodID(type, "<init>",
        "(Lorg/telegram/messenger/purple/PurplePlaylistsView$Row;[Lorg/telegram/messenger/purple/PurplePlaylistsView$Song;)V");
    if (!ctor) return env->PopLocalFrame(nullptr);
    const auto songType = Class(env, "$Song");
    if (!songType) return env->PopLocalFrame(nullptr);
    const auto ids = PlaylistVisibleSongIds(found->second);
    const auto songs = env->NewObjectArray(Size(ids.size()), songType, nullptr);
    if (!songs) return env->PopLocalFrame(nullptr);
    for (auto i = std::size_t(0); i != ids.size(); ++i) {
        const auto item = Song(env, ids[i], *found->second.songs->at(ids[i]).value.source, self);
        if (!item) return env->PopLocalFrame(nullptr);
        env->SetObjectArrayElement(songs, Size(i), item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    const auto row = Row(env, id, found->second, local);
    if (!row) return env->PopLocalFrame(nullptr);
    return env->PopLocalFrame(env->NewObject(type, ctor, row, songs));
}

const char *SyncResult(PlaylistLocalSyncResult value) {
    switch (value) {
    case PlaylistLocalSyncResult::Idle: return "Idle";
    case PlaylistLocalSyncResult::Synced: return "Synced";
    case PlaylistLocalSyncResult::Offline: return "Offline";
    case PlaylistLocalSyncResult::Wait: return "Wait";
    case PlaylistLocalSyncResult::Problem: return "Problem";
    case PlaylistLocalSyncResult::Failed: return "Failed";
    case PlaylistLocalSyncResult::Oversized: return "Oversized";
    case PlaylistLocalSyncResult::NewerFormat: return "NewerFormat";
    }
    throw std::logic_error("Unknown playlist sync result");
}

jobject Project(JNIEnv *env, jbyteArray libraryBytes, jbyteArray localBytes, jlong self, jbyteArray selectedBytes) {
    const auto libraryRaw = Read(env, libraryBytes);
    if (env->ExceptionCheck()) return nullptr;
    const auto localRaw = Read(env, localBytes);
    if (env->ExceptionCheck()) return nullptr;
    const auto selectedRaw = Read(env, selectedBytes);
    if (env->ExceptionCheck()) return nullptr;
    auto decoder = QStringDecoder(QStringDecoder::Utf8);
    const auto selected = QString(decoder(selectedRaw));
    if (decoder.hasError()) throw std::invalid_argument("Invalid playlist selection UTF8");
    auto library = ParsePlaylistLibrary(libraryRaw, self > 0 ? uint64_t(self) : 0, std::nullopt);
    if (self <= 0) { library.status = PlaylistLibraryStatus::Invalid; library.error = PlaylistLibraryError::InvalidSelfUserId; }
    const auto local = ParsePlaylistLocalState(localRaw, self > 0 ? uint64_t(self) : 0, std::nullopt);
    const auto valid = bool(library) && bool(local);
    const auto status = !library ? (library.status == PlaylistLibraryStatus::NewerFormat ? 1 : 2)
        : !local ? (local.status == PlaylistLocalStatus::NewerFormat ? 1 : 2) : 0;
    const auto error = !library ? LibraryError(library.error) : !local ? LocalError(local.error) : "None";
    const auto type = Class(env, "");
    if (!type) return nullptr;
    const auto ctor = env->GetMethodID(type, "<init>",
        "(IILjava/lang/String;[B[Lorg/telegram/messenger/purple/PurplePlaylistsView$Row;[Lorg/telegram/messenger/purple/PurplePlaylistsView$Row;Lorg/telegram/messenger/purple/PurplePlaylistsView$Detail;ZLjava/lang/String;JII)V");
    if (!ctor) return nullptr;
    const auto errorText = env->NewStringUTF(error);
    if (!errorText) return nullptr;
    const auto selection = Bytes(env, selected);
    if (!selection) return nullptr;
    auto visible = jobjectArray(nullptr), deleted = jobjectArray(nullptr);
    auto detail = jobject(nullptr);
    auto incomplete = 0;
    if (valid) {
        auto activeIds = std::vector<QString>();
        for (const auto &row : ListVisiblePlaylists(library.library, local.state.recentUse)) activeIds.push_back(row.id);
        auto deletedIds = std::vector<QString>();
        for (const auto &[id, playlist] : library.library.playlists) {
            if (library.library.purged.contains(id)) continue;
            if (!playlist.title || !playlist.deleted) ++incomplete;
            if (playlist.deleted && playlist.deleted->value) deletedIds.push_back(id);
        }
        visible = Rows(env, activeIds, library.library, local.state);
        if (!visible) return nullptr;
        deleted = Rows(env, deletedIds, library.library, local.state);
        if (!deleted) return nullptr;
        detail = Detail(env, selected, library.library, local.state, uint64_t(self));
        if (env->ExceptionCheck()) return nullptr;
    }
    const auto sync = env->NewStringUTF(valid ? SyncResult(local.state.status.result) : "Unavailable");
    if (!sync) return nullptr;
    return env->NewObject(type, ctor, jint(status), jint(!library ? 1 : !local ? 2 : 0), errorText, selection,
        visible, deleted, detail, jboolean(valid && local.state.sync), sync,
        jlong(valid ? local.state.unsyncedSince.value_or(0) : 0), jint(incomplete),
        jint(valid ? Size(local.state.hidden.size()) : 0));
}

void Throw(JNIEnv *env, const char *name, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto type = env->FindClass(name)) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsView_projectNative(
        JNIEnv *env, jclass, jbyteArray library, jbyteArray local, jlong self, jbyteArray selected) {
    if (env->PushLocalFrame(32) < 0) return nullptr;
    try {
        const auto result = Project(env, library, local, self, selected);
        return env->PopLocalFrame(result);
    } catch (const std::bad_alloc &) {
        Throw(env, "java/lang/OutOfMemoryError", "Playlist view allocation failed");
    } catch (const std::exception &) {
        Throw(env, "java/lang/IllegalStateException", "Playlist view failed");
    }
    return env->PopLocalFrame(nullptr);
}
