#include <jni.h>
#include "purple/purple_playlists_ops.h"
#include "purple/purple_playlists_local.h"
#include <QtCore/QStringDecoder>
#include <algorithm>
#include <array>
#include <atomic>
#include <limits>
#include <map>
#include <optional>
#include <stdexcept>
#include <utility>

namespace {
using namespace Purple;
struct Pending {
    PlaylistUndoRecord undo;
    bool changed = false;
    std::optional<uint64_t> dirty;
};
struct Editor {
    uint64_t self = 0;
    PlaylistUndoRecord undo;
    std::optional<Pending> pending;
    std::optional<uint64_t> dirty;
};
thread_local std::map<jlong, Editor> Editors;
std::atomic<jlong> Next{1};

struct Answer {
    PlaylistEditResult edit;
    const char *localError = "None";
    bool stateChanged = false;
    std::optional<QByteArray> library, state;
};

const char *EditError(PlaylistEditError value) {
    switch (value) {
    case PlaylistEditError::None: return "None";
    case PlaylistEditError::InvalidLibrary: return "InvalidLibrary";
    case PlaylistEditError::NewerFormat: return "NewerFormat";
    case PlaylistEditError::InvalidSelfUserId: return "InvalidSelfUserId";
    case PlaylistEditError::InvalidId: return "InvalidId";
    case PlaylistEditError::InvalidEntropy: return "InvalidEntropy";
    case PlaylistEditError::IdCollision: return "IdCollision";
    case PlaylistEditError::PlaylistNotFound: return "PlaylistNotFound";
    case PlaylistEditError::PlaylistPurged: return "PlaylistPurged";
    case PlaylistEditError::PlaylistIncomplete: return "PlaylistIncomplete";
    case PlaylistEditError::PlaylistDeleted: return "PlaylistDeleted";
    case PlaylistEditError::PlaylistNotDeleted: return "PlaylistNotDeleted";
    case PlaylistEditError::InvalidTitle: return "InvalidTitle";
    case PlaylistEditError::InvalidDocumentId: return "InvalidDocumentId";
    case PlaylistEditError::DuplicateDocumentId: return "DuplicateDocumentId";
    case PlaylistEditError::InvalidPeerToken: return "InvalidPeerToken";
    case PlaylistEditError::InvalidUsername: return "InvalidUsername";
    case PlaylistEditError::InvalidMeta: return "InvalidMeta";
    case PlaylistEditError::InvalidValue: return "InvalidValue";
    case PlaylistEditError::InvalidOrder: return "InvalidOrder";
    case PlaylistEditError::InvalidStamp: return "InvalidStamp";
    case PlaylistEditError::StampOverflow: return "StampOverflow";
    case PlaylistEditError::LibraryFull: return "LibraryFull";
    case PlaylistEditError::NothingToUndo: return "NothingToUndo";
    case PlaylistEditError::UndoConflict: return "UndoConflict";
    }
    throw std::logic_error("Unknown playlist error");
}

const char *LocalError(PlaylistLocalError value) {
    switch (value) {
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
    throw std::logic_error("Unknown playlist error");
}


void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) {
        env->ThrowNew(error, message);
        env->DeleteLocalRef(error);
    }
}

Editor &Find(jlong handle) {
    const auto i = Editors.find(handle);
    if (i == Editors.end()) throw std::logic_error("Wrong thread or closed playlist editor");
    return i->second;
}

QByteArray Read(JNIEnv *env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Missing playlist bytes");
    const auto count = env->GetArrayLength(value);
    if (env->ExceptionCheck()) throw std::runtime_error("JNI array failed");
    auto result = QByteArray(count, Qt::Uninitialized);
    if (count) env->GetByteArrayRegion(value, 0, count, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("JNI read failed");
    return result;
}

QString Text(const QByteArray &value) {
    auto decoder = QStringDecoder(QStringDecoder::Utf8);
    const auto result = QString(decoder(value));
    if (decoder.hasError()) throw std::invalid_argument("Invalid playlist UTF8");
    return result;
}

std::vector<QString> Texts(JNIEnv *env, jobjectArray values) {
    if (!values) throw std::invalid_argument("Missing playlist array");
    const auto count = env->GetArrayLength(values);
    if (env->ExceptionCheck()) throw std::runtime_error("JNI array failed");
    auto result = std::vector<QString>();
    result.reserve(count);
    for (auto i = jsize(0); i != count; ++i) {
        const auto entry = static_cast<jbyteArray>(env->GetObjectArrayElement(values, i));
        if (env->ExceptionCheck()) throw std::runtime_error("JNI array failed");
        try {
            result.push_back(Text(Read(env, entry)));
        } catch (...) {
            if (entry) env->DeleteLocalRef(entry);
            throw;
        }
        env->DeleteLocalRef(entry);
    }
    return result;
}

PlaylistEditResult Apply(JNIEnv *env, Editor &editor, const PlaylistLibrary &library, int kind,
        const QString &id, const QString &title, const QByteArray &entropy,
        jbyteArray source, jbyteArray sourceId, jobjectArray ids, jobjectArray songs,
        jintArray numbers, uint64_t now) {
    const auto self = editor.self;
    switch (kind) {
    case 0: return CreatePlaylist(library, title, entropy, now, self);
    case 1: {
        const auto parsed = ParsePlaylistLibrary(Read(env, source), self, std::nullopt);
        if (!parsed) return { .error = parsed.status == PlaylistLibraryStatus::NewerFormat
            ? PlaylistEditError::NewerFormat : PlaylistEditError::InvalidLibrary };
        const auto sourceKey = Text(Read(env, sourceId));
        if (!IsPlaylistId(sourceKey)) return { .error = PlaylistEditError::InvalidId };
        if (parsed.library.purged.contains(sourceKey)) return { .error = PlaylistEditError::PlaylistPurged };
        const auto i = parsed.library.playlists.find(sourceKey);
        if (i == parsed.library.playlists.end()) return { .error = PlaylistEditError::PlaylistNotFound };
        return CopyPlaylist(library, i->second, entropy, now, self);
    }
    case 2: return RenamePlaylist(library, id, title, now, self);
    case 3: return DeletePlaylist(library, id, now, self);
    case 4: return RestorePlaylist(library, id, now, self);
    case 5: return DeleteForeverPlaylist(library, id, self);
    case 6: {
        const auto texts = Texts(env, songs);
        if (texts.size() % 5 || !numbers) throw std::invalid_argument("Invalid playlist song columns");
        const auto count = env->GetArrayLength(numbers);
        if (env->ExceptionCheck()) throw std::runtime_error("JNI array failed");
        if (std::size_t(count) != texts.size() / 5 * 2) throw std::invalid_argument("Invalid playlist song columns");
        auto values = std::vector<jint>(count);
        if (count) env->GetIntArrayRegion(numbers, 0, count, values.data());
        if (env->ExceptionCheck()) throw std::runtime_error("JNI array failed");
        auto input = std::vector<PlaylistAddSongInput>();
        input.reserve(texts.size() / 5);
        for (auto i = std::size_t(0); i != texts.size() / 5; ++i) {
            const auto doc = ParsePlaylistDocumentId(texts[i * 5]);
            if (!doc) return { .error = PlaylistEditError::InvalidDocumentId };
            const auto peer = ParsePeerToken(texts[i * 5 + 1], self);
            if (!peer) return { .error = PlaylistEditError::InvalidPeerToken };
            input.push_back({ *doc, *peer, values[i * 2], texts[i * 5 + 2],
                { texts[i * 5 + 3], texts[i * 5 + 4], values[i * 2 + 1] } });
        }
        return AddPlaylistSongs(library, id, input, now, self);
    }
    case 7:
    case 8: {
        const auto texts = Texts(env, ids);
        auto documents = std::vector<uint64_t>();
        documents.reserve(texts.size());
        for (const auto &text : texts) {
            const auto doc = ParsePlaylistDocumentId(text);
            if (!doc) return { .error = PlaylistEditError::InvalidDocumentId };
            documents.push_back(*doc);
        }
        return kind == 7 ? RemovePlaylistSongs(library, id, documents, now, self)
            : ReorderPlaylistSongs(library, id, documents, now, self);
    }
    case 9: return UndoPlaylistOperation(library, editor.undo, now, self);
    default: throw std::invalid_argument("Invalid playlist command");
    }
}

Answer Evaluate(JNIEnv *env, Editor &editor, jbyteArray libraryBytes, jbyteArray stateBytes,
        int kind, jbyteArray id, jbyteArray title, jbyteArray entropy, jbyteArray source,
        jbyteArray sourceId, jobjectArray ids, jobjectArray songs, jintArray numbers, jlong now) {
    editor.pending.reset();
    auto answer = Answer();
    if (now <= 0 || uint64_t(now) > kMaximumPlaylistStamp) {
        answer.edit.error = PlaylistEditError::InvalidStamp;
        return answer;
    }
    const auto parsed = ParsePlaylistLibrary(Read(env, libraryBytes), editor.self, std::nullopt);
    if (!parsed) {
        answer.edit.error = parsed.status == PlaylistLibraryStatus::NewerFormat
            ? PlaylistEditError::NewerFormat : PlaylistEditError::InvalidLibrary;
        return answer;
    }
    const auto local = ParsePlaylistLocalState(Read(env, stateBytes), editor.self, std::nullopt);
    if (!local) {
        answer.localError = local.status == PlaylistLocalStatus::NewerFormat ? "NewerFormat" : LocalError(local.error);
        return answer;
    }
    answer.edit = Apply(env, editor, parsed.library, kind, Text(Read(env, id)), Text(Read(env, title)),
        Read(env, entropy), source, sourceId, ids, songs, numbers, uint64_t(now));
    if (!answer.edit) return answer;
    auto state = local.state;
    auto dirty = editor.dirty;
    if (answer.edit.changed) dirty = dirty ? std::min(*dirty, uint64_t(now)) : uint64_t(now);
    if (dirty) state.unsyncedSince = state.unsyncedSince ? std::min(*state.unsyncedSince, *dirty) : *dirty;
    if (kind == 6 || (answer.edit.changed && (kind == 0 || kind == 1))) {
        auto &recent = state.recentUse[answer.edit.playlistId];
        recent = std::max(recent, uint64_t(now));
    }
    const auto encoded = SerializePlaylistLibrary(answer.edit.library, editor.self);
    const auto saved = SerializePlaylistLocalState(state, editor.self);
    if (!encoded) { answer.edit.error = PlaylistEditError::InvalidLibrary; return answer; }
    if (!saved) { answer.localError = LocalError(saved.error); return answer; }
    answer.library = encoded.canonical;
    answer.state = saved.json;
    answer.stateChanged = state != local.state;
    editor.pending = Pending{ std::move(answer.edit.undo), answer.edit.changed, dirty };
    return answer;
}

jbyteArray Bytes(JNIEnv *env, const QByteArray &bytes) {
    if (bytes.size() > std::numeric_limits<jsize>::max()) throw std::bad_alloc();
    const auto result = env->NewByteArray(jsize(bytes.size()));
    if (!result) return nullptr;
    if (!bytes.isEmpty()) env->SetByteArrayRegion(result, 0, jsize(bytes.size()),
        reinterpret_cast<const jbyte*>(bytes.constData()));
    return env->ExceptionCheck() ? nullptr : result;
}

jobject Result(JNIEnv *env, const Answer &answer) {
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsEdits$Result");
    if (!type) return nullptr;
    const auto ctor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Ljava/lang/String;[BZZ[B[B[I)V");
    if (!ctor) return nullptr;
    const auto error = env->NewStringUTF(EditError(answer.edit.error));
    if (!error) return nullptr;
    const auto local = env->NewStringUTF(answer.localError);
    if (!local) return nullptr;
    const auto id = Bytes(env, answer.edit.playlistId.toUtf8());
    if (!id) return nullptr;
    auto library = jbyteArray(nullptr), state = jbyteArray(nullptr);
    if (answer.library && answer.state) {
        library = Bytes(env, *answer.library);
        if (!library) return nullptr;
        state = Bytes(env, *answer.state);
        if (!state) return nullptr;
    }
    const auto counts = env->NewIntArray(4);
    if (!counts) return nullptr;
    const auto values = std::array<jint, 4>{ jint(answer.edit.addedCount), jint(answer.edit.relinkedCount),
        jint(answer.edit.removedCount), jint(answer.edit.unchangedCount) };
    env->SetIntArrayRegion(counts, 0, 4, values.data());
    if (env->ExceptionCheck()) return nullptr;
    return env->NewObject(type, ctor, error, local, id, jboolean(answer.edit.changed),
        jboolean(answer.stateChanged), library, state, counts);
}

template <typename F> void Guard(JNIEnv *env, F &&action) {
    try { action(); }
    catch (const std::bad_alloc &) { Throw(env, "java/lang/OutOfMemoryError", "Playlist edit allocation failed"); }
    catch (const std::invalid_argument &) { Throw(env, "java/lang/IllegalArgumentException", "Invalid playlist edit transport"); }
    catch (const std::exception &) { Throw(env, "java/lang/IllegalStateException", "Playlist editor unavailable"); }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsEdits_openNative(JNIEnv *env, jclass, jlong self) {
    auto result = jlong(0);
    Guard(env, [&] {
        if (self <= 0) throw std::invalid_argument("Invalid playlist self");
        auto next = Next.load();
        do {
            if (next <= 0 || next == std::numeric_limits<jlong>::max()) throw std::logic_error("Editor handles exhausted");
        } while (!Next.compare_exchange_weak(next, next + 1));
        Editors.emplace(next, Editor{ .self = uint64_t(self) });
        result = next;
    });
    return result;
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsEdits_prepareNative(JNIEnv *env, jclass, jlong handle,
        jbyteArray library, jbyteArray state, jint kind, jbyteArray id, jbyteArray title, jbyteArray entropy,
        jbyteArray source, jbyteArray sourceId, jobjectArray ids, jobjectArray songs, jintArray numbers, jlong now) {
    auto result = jobject(nullptr);
    if (env->PushLocalFrame(16) < 0) return nullptr;
    Guard(env, [&] {
        auto &editor = Find(handle);
        try {
            const auto answer = Evaluate(env, editor, library, state, kind, id, title, entropy, source,
                sourceId, ids, songs, numbers, now);
            result = Result(env, answer);
            if (!result || env->ExceptionCheck()) editor.pending.reset();
        } catch (...) { editor.pending.reset(); throw; }
    });
    return env->PopLocalFrame(result);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsEdits_finishNative(JNIEnv *env, jclass, jlong handle,
        jboolean libraryConfirmed, jboolean stateConfirmed, jboolean uncertain) {
    auto result = JNI_FALSE;
    Guard(env, [&] {
        auto &editor = Find(handle);
        if (!editor.pending) throw std::logic_error("No prepared playlist edit");
        auto pending = std::move(*editor.pending);
        editor.pending.reset();
        if (uncertain) editor.undo = {};
        else if (libraryConfirmed && pending.changed) editor.undo = std::move(pending.undo);
        if (libraryConfirmed) editor.dirty = stateConfirmed ? std::nullopt : pending.dirty;
        result = editor.undo.hasUndo() ? JNI_TRUE : JNI_FALSE;
    });
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsEdits_forgetNative(JNIEnv *env, jclass, jlong handle) {
    Guard(env, [&] { Find(handle).undo = {}; });
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsEdits_closeNative(JNIEnv *env, jclass, jlong handle) {
    Guard(env, [&] { Find(handle); Editors.erase(handle); });
}
