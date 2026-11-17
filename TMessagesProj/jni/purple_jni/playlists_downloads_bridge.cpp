#include "purple/purple_types.h"
#include <jni.h>
#include "purple/purple_playlists_download_state.h"
#include "purple/purple_playlists_download_job.h"

#include <QtCore/QJsonDocument>
#include <atomic>
#include <limits>
#include <stdexcept>

namespace {
using namespace Purple;
constexpr auto Client = PlaylistDownloadClient::Android;
thread_local std::map<jlong, PlaylistDownloadJobPolicy> Jobs;
std::atomic<jlong> Next{ 1 };

void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) {
        env->ThrowNew(error, message);
        env->DeleteLocalRef(error);
    }
}

QByteArray Bytes(JNIEnv *env, jbyteArray value) {
    if (!value) throw std::invalid_argument("Missing local state");
    const auto size = env->GetArrayLength(value);
    auto bytes = QByteArray(size, Qt::Uninitialized);
    if (size) env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(bytes.data()));
    return bytes;
}

QString Text(JNIEnv *env, jstring value) {
    if (!value) return {};
    const auto size = env->GetStringLength(value);
    const auto chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    const auto text = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), size);
    env->ReleaseStringChars(value, chars);
    return text;
}

jbyteArray Array(JNIEnv *env, const QByteArray &bytes) {
    if (bytes.size() > std::numeric_limits<jsize>::max()) throw std::bad_alloc();
    const auto array = env->NewByteArray(jsize(bytes.size()));
    if (array && !bytes.isEmpty()) env->SetByteArrayRegion(array, 0, jsize(bytes.size()),
        reinterpret_cast<const jbyte*>(bytes.constData()));
    return array;
}

const char *Status(PlaylistDownloadStatus status) {
    switch (status) {
    case PlaylistDownloadStatus::Ready: return "Ready";
    case PlaylistDownloadStatus::Invalid: return "Invalid";
    case PlaylistDownloadStatus::Newer: return "Newer";
    case PlaylistDownloadStatus::Changed: return "Changed";
    case PlaylistDownloadStatus::Busy: return "Busy";
    case PlaylistDownloadStatus::IoError: return "IoError";
    case PlaylistDownloadStatus::Unsupported: return "Unsupported";
    }
    throw std::logic_error("Unknown download status");
}

jobject Result(JNIEnv *env, PlaylistDownloadStatus status,
        const QByteArray &local = {}, const QByteArray &inventory = {}) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsDownloadsCore$Result");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;[B[B)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    const auto name = env->NewStringUTF(Status(status));
    const auto good = status == PlaylistDownloadStatus::Ready;
    const auto state = good ? Array(env, local) : nullptr;
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    const auto data = good ? Array(env, inventory) : nullptr;
    if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    const auto value = env->NewObject(type, constructor, name, state, data);
    return env->PopLocalFrame(value);
}


PlaylistDownloadJobPolicy &Job(jlong id) {
    const auto i = Jobs.find(id);
    if (i == Jobs.end()) throw std::logic_error("Closed or wrong-thread download job");
    return i->second;
}

template <typename Function>
auto Guard(JNIEnv *env, Function run) -> decltype(run()) {
    try { return run(); }
    catch (const std::bad_alloc &) {
        Throw(env, "java/lang/OutOfMemoryError", "Download bridge allocation failed");
    } catch (const std::exception &) {
        Throw(env, "java/lang/IllegalStateException", "Download bridge refused operation");
    }
    return {};
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_transitionNative(
        JNIEnv *env, jclass, jbyteArray bytes, jlong self, jint operation,
        jstring playlistValue, jstring tokenValue, jlong document, jstring extensionValue,
        jlong size, jstring identityValue, jstring hashValue) {
    return Guard(env, [&]() -> jobject {
        if (self <= 0 || !bytes) return Result(env, PlaylistDownloadStatus::Invalid);
        const auto data = Bytes(env, bytes);
        if (env->ExceptionCheck()) return nullptr;
        auto parsed = ParsePlaylistLocalState(data, uint64_t(self), std::nullopt);
        if (!parsed) return Result(env, parsed.status == PlaylistLocalStatus::NewerFormat
            ? PlaylistDownloadStatus::Newer : PlaylistDownloadStatus::Invalid);
        auto read = ReadPlaylistDownloadState(parsed.state, Client);
        if (!read) return Result(env, read.status);
        const auto playlist = Text(env, playlistValue);
        const auto token = Text(env, tokenValue);
        const auto extension = Text(env, extensionValue);
        const auto identity = Text(env, identityValue);
        const auto hash = Text(env, hashValue);
        if (env->ExceptionCheck()) return nullptr;
        if (identity != QString::fromLatin1(identity.toLatin1())
                || hash != QString::fromLatin1(hash.toLatin1())) return Result(env, PlaylistDownloadStatus::Invalid);
        const auto status = ApplyPlaylistDownloadChange(read.state, PlaylistDownloadChange(operation), playlist, token, {
            playlist, uint64_t(document), extension, uint64_t(size), identity.toLatin1(), hash.toLatin1(), {} });
        if (status != PlaylistDownloadStatus::Ready) return Result(env, status);
        auto projected = parsed.state;
        const auto saved = SetPlaylistDownloadState(projected, read.state, Client);
        if (saved != PlaylistDownloadStatus::Ready) return Result(env, saved);
        const auto local = SerializePlaylistLocalState(operation == 0 ? parsed.state : projected, uint64_t(self));
        if (!local) return Result(env, PlaylistDownloadStatus::Invalid);
        const auto inventory = projected.preserved.value(u"android_playlist_downloads"_q).toObject();
        return Result(env, PlaylistDownloadStatus::Ready, local.json,
            QJsonDocument(inventory).toJson(QJsonDocument::Compact));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_openNative(JNIEnv *env, jclass, jint count) {
    return Guard(env, [&]() -> jlong {
        if (count < 0 || count > 1000000) throw std::invalid_argument("Invalid download row count");
        const auto id = Next.fetch_add(1);
        if (id <= 0) throw std::logic_error("Download handle exhausted");
        Jobs.emplace(id, std::size_t(count));
        return id;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_admitNative(JNIEnv *env, jclass, jlong id) {
    return Guard(env, [&]() -> jlongArray {
        const auto tickets = Job(id).admit();
        auto values = std::vector<jlong>();
        for (const auto &ticket : tickets) {
            values.push_back(jlong(ticket.generation));
            values.push_back(jlong(ticket.index));
        }
        const auto array = env->NewLongArray(jsize(values.size()));
        if (array && !values.empty()) env->SetLongArrayRegion(array, 0, jsize(values.size()), values.data());
        return array;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_finishNative(
        JNIEnv *env, jclass, jlong id, jlong generation, jint index, jint phase) {
    return Guard(env, [&]() -> jboolean {
        if (generation <= 0 || index < 0 || phase < 2 || phase > 5) return JNI_FALSE;
        return Job(id).finish({ uint64_t(generation), std::size_t(index) },
            PlaylistDownloadPhase(phase)) ? JNI_TRUE : JNI_FALSE;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_controlNative(
        JNIEnv *env, jclass, jlong id, jint operation) {
    return Guard(env, [&]() -> jboolean {
        auto &job = Job(id);
        if (operation == 0) return job.retry() ? JNI_TRUE : JNI_FALSE;
        if (operation == 1) job.cancel();
        else if (operation == 2) Jobs.erase(id);
        else throw std::invalid_argument("Invalid job command");
        return JNI_TRUE;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_progressNative(JNIEnv *env, jclass, jlong id) {
    return Guard(env, [&]() -> jlongArray {
        const auto p = Job(id).progress();
        const jlong values[] = { jlong(p.total), jlong(p.downloaded), jlong(p.unavailable),
            jlong(p.failed), jlong(p.busy), jlong(p.active), p.terminal, p.cancelled };
        const auto array = env->NewLongArray(8);
        if (array) env->SetLongArrayRegion(array, 0, 8, values);
        return array;
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_recordsNative(
        JNIEnv *env, jclass, jbyteArray bytes, jlong self) {
    return Guard(env, [&]() -> jobjectArray {
        if (self <= 0 || !bytes) throw std::invalid_argument("Invalid inventory account");
        const auto data = Bytes(env, bytes);
        if (env->ExceptionCheck()) return nullptr;
        const auto local = ParsePlaylistLocalState(data, uint64_t(self), std::nullopt);
        if (!local) throw std::invalid_argument("Invalid local state");
        const auto parsed = ReadPlaylistDownloadState(local.state, Client);
        if (!parsed) throw std::invalid_argument("Invalid inventory");
        const auto count = parsed.state.pending.size() + parsed.state.files.size();
        if (count > size_t(std::numeric_limits<jsize>::max())) throw std::bad_alloc();
        if (env->PushLocalFrame(8) < 0) return nullptr;
        const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsDownloadsCore$Record");
        if (!type) return static_cast<jobjectArray>(env->PopLocalFrame(nullptr));
        const auto ctor = env->GetMethodID(type, "<init>", "(ZLjava/lang/String;Ljava/lang/String;JLjava/lang/String;JLjava/lang/String;Ljava/lang/String;)V");
        if (!ctor) return static_cast<jobjectArray>(env->PopLocalFrame(nullptr));
        const auto array = env->NewObjectArray(jsize(count), type, nullptr);
        if (!array) return static_cast<jobjectArray>(env->PopLocalFrame(nullptr));
        auto index = jsize(0);
        const auto add = [&](bool complete, const QString &playlist, const QString &token,
                uint64_t document, const QString &extension, uint64_t size,
                const QByteArray &identity, const QByteArray &digest) {
            if (document > uint64_t(std::numeric_limits<jlong>::max())
                || size > uint64_t(std::numeric_limits<jlong>::max()))
                throw std::invalid_argument("Unsupported media identity");
            if (env->PushLocalFrame(8) < 0) return false;
            const auto p = env->NewStringUTF(playlist.toUtf8().constData());
            const auto t = p ? env->NewStringUTF(token.toUtf8().constData()) : nullptr;
            const auto e = t ? env->NewStringUTF(extension.toUtf8().constData()) : nullptr;
            const auto id = e ? env->NewStringUTF(identity.constData()) : nullptr;
            const auto hash = id ? env->NewStringUTF(digest.constData()) : nullptr;
            const auto value = hash ? env->NewObject(type, ctor, jboolean(complete), p, t,
                jlong(document), e, jlong(size), id, hash) : nullptr;
            if (value && !env->ExceptionCheck()) env->SetObjectArrayElement(array, index++, value);
            env->PopLocalFrame(nullptr);
            return !env->ExceptionCheck();
        };
        for (const auto &[token, v] : parsed.state.pending) {
            if (!add(false, v.playlistId, token, v.documentId, v.extension, v.size, v.identity, v.sha256))
                return static_cast<jobjectArray>(env->PopLocalFrame(nullptr));
        }
        for (const auto &[document, v] : parsed.state.files) {
            if (!add(true, {}, {}, document, v.extension, v.size, v.identity, v.sha256))
                return static_cast<jobjectArray>(env->PopLocalFrame(nullptr));
        }
        return static_cast<jobjectArray>(env->PopLocalFrame(array));
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_removalNative(
        JNIEnv *env, jclass, jbyteArray libraryBytes, jbyteArray localBytes, jlong self, jstring selected) {
    return Guard(env, [&]() -> jobject {
        auto status = PlaylistDownloadStatus::Invalid;
        auto present = false;
        auto pending = false;
        auto cleanupSelected = false;
        auto cleanupPrune = false;
        auto protectedFiles = jint(0);
        auto candidates = std::vector<jlong>();
        auto intents = std::vector<QString>();
        if (self > 0 && libraryBytes && localBytes) {
            const auto library = ParsePlaylistLibrary(Bytes(env, libraryBytes), uint64_t(self), std::nullopt);
            const auto local = ParsePlaylistLocalState(Bytes(env, localBytes), uint64_t(self), std::nullopt);
            if (env->ExceptionCheck()) return nullptr;
            if (library && local) {
                const auto downloads = ReadPlaylistDownloadState(local.state, Client);
                status = downloads.status;
                present = local.state.preserved.contains(u"android_playlist_downloads"_q);
                if (downloads) {
                    pending = !downloads.state.pending.empty();
                    const auto playlist = selected ? std::optional(Text(env, selected)) : std::nullopt;
                    if (env->ExceptionCheck()) return nullptr;
                    const auto cleanup = ReadPlaylistDownloadCleanup(downloads.state);
                    cleanupSelected = playlist && cleanup.playlists.contains(*playlist) && !library.library.purged.contains(*playlist);
                    cleanupPrune = cleanup.prune || !PlaylistDownloadObsoleteCleanupTargets(library.library, downloads.state).empty();
                    if (playlist && (library.library.playlists.find(*playlist) == end(library.library.playlists)
                            || library.library.playlists.at(*playlist).isPartial() || library.library.purged.contains(*playlist))) {
                        status = PlaylistDownloadStatus::Invalid;
                    }
                    for (const auto &[id, value] : library.library.playlists) {
                        if (!library.library.purged.contains(id) && !value.songs) status = PlaylistDownloadStatus::Busy;
                    }
                    if (status == PlaylistDownloadStatus::Ready) {
                        intents = PlaylistDownloadRemovalIntents(library.library, downloads.state, playlist);
                        for (const auto id : PlaylistDownloadRemovalCandidates(library.library, downloads.state, playlist)) {
                            if (id > uint64_t(std::numeric_limits<jlong>::max())) throw std::invalid_argument("Unsupported document");
                            candidates.push_back(jlong(id));
                        }
                        if (playlist) {
                            const auto protectedIds = PlaylistDownloadReferences(library.library, *playlist);
                            for (const auto &[id, song] : *library.library.playlists.at(*playlist).songs) {
                                if (!song.value.isRemoved() && protectedIds.contains(id) && downloads.state.files.contains(id)) ++protectedFiles;
                            }
                        }
                    }
                }
            } else if (library.status == PlaylistLibraryStatus::NewerFormat || local.status == PlaylistLocalStatus::NewerFormat) {
                status = PlaylistDownloadStatus::Newer;
            }
        }
        if (env->PushLocalFrame(8) < 0) return nullptr;
        const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsDownloadsCore$RemovalPlan");
        if (!type) return env->PopLocalFrame(nullptr);
        const auto ctor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;ZZI[J[Ljava/lang/String;ZZ)V");
        if (!ctor) return env->PopLocalFrame(nullptr);
        const auto name = env->NewStringUTF(Status(status));
        const auto values = env->NewLongArray(jsize(candidates.size()));
        if (values && !candidates.empty()) env->SetLongArrayRegion(values, 0, jsize(candidates.size()), candidates.data());
        if (env->ExceptionCheck() || !name || !values) return env->PopLocalFrame(nullptr);
        const auto textType = env->FindClass("java/lang/String");
        if (!textType) return env->PopLocalFrame(nullptr);
        const auto tokens = env->NewObjectArray(jsize(intents.size()), textType, nullptr);
        if (!tokens) return env->PopLocalFrame(nullptr);
        for (auto i = size_t(0); i != intents.size(); ++i) {
            const auto token = env->NewString(reinterpret_cast<const jchar*>(intents[i].utf16()), jsize(intents[i].size()));
            if (!token) return env->PopLocalFrame(nullptr);
            env->SetObjectArrayElement(tokens, jsize(i), token);
            env->DeleteLocalRef(token);
            if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
        }
        const auto result = env->NewObject(type, ctor, name, jboolean(present), jboolean(pending), protectedFiles, values, tokens, jboolean(cleanupSelected), jboolean(cleanupPrune));
        return env->PopLocalFrame(result);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsDownloadsCore_normalizeCleanupNative(
        JNIEnv *env, jclass, jbyteArray libraryBytes, jbyteArray localBytes, jlong self) {
    return Guard(env, [&]() -> jobject {
        if (self <= 0 || !libraryBytes || !localBytes) return Result(env, PlaylistDownloadStatus::Invalid);
        const auto library = ParsePlaylistLibrary(Bytes(env, libraryBytes), uint64_t(self), std::nullopt);
        auto local = ParsePlaylistLocalState(Bytes(env, localBytes), uint64_t(self), std::nullopt);
        if (env->ExceptionCheck()) return nullptr;
        if (!library || !local) return Result(env,
            library.status == PlaylistLibraryStatus::NewerFormat || local.status == PlaylistLocalStatus::NewerFormat
                ? PlaylistDownloadStatus::Newer : PlaylistDownloadStatus::Invalid);
        auto downloads = ReadPlaylistDownloadState(local.state, Client);
        if (!downloads) return Result(env, downloads.status);
        for (const auto &[id, playlist] : library.library.playlists) {
            if (!library.library.purged.contains(id) && !playlist.songs) return Result(env, PlaylistDownloadStatus::Busy);
        }
        if (!PlaylistDownloadRemovalIntents(library.library, downloads.state, std::nullopt).empty()
                || !PlaylistDownloadRemovalCandidates(library.library, downloads.state, std::nullopt).empty())
            return Result(env, PlaylistDownloadStatus::Busy);
        for (const auto &id : PlaylistDownloadObsoleteCleanupTargets(library.library, downloads.state)) {
            const auto cleared = ApplyPlaylistDownloadChange(downloads.state, PlaylistDownloadChange::ClearRemoval, id, {}, {});
            if (cleared != PlaylistDownloadStatus::Ready) return Result(env, cleared);
        }
        const auto cleared = ApplyPlaylistDownloadChange(downloads.state, PlaylistDownloadChange::ClearPrune, {}, {}, {});
        if (cleared != PlaylistDownloadStatus::Ready) return Result(env, cleared);
        const auto saved = SetPlaylistDownloadState(local.state, downloads.state, Client);
        if (saved != PlaylistDownloadStatus::Ready) return Result(env, saved);
        const auto bytes = SerializePlaylistLocalState(local.state, uint64_t(self));
        if (!bytes) return Result(env, PlaylistDownloadStatus::Invalid);
        return Result(env, PlaylistDownloadStatus::Ready, bytes.json,
            QJsonDocument(local.state.preserved.value(u"android_playlist_downloads"_q).toObject()).toJson(QJsonDocument::Compact));
    });
}
