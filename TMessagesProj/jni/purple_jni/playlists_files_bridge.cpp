#include "playlists_files.h"

#include <jni.h>
#include <array>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <utility>

namespace {
using Files = PurpleAndroid::PlaylistFiles;
using Failure = Files::Failure;

void Need(bool condition, const char *status) {
    if (!condition) throw Failure{status};
}

QString Text(JNIEnv *env, jstring value) {
    Need(value != nullptr, "InvalidPath");
    const auto size = env->GetStringLength(value);
    const auto chars = env->GetStringChars(value, nullptr);
    Need(chars != nullptr, "JavaException");
    auto result = QString::fromUtf16(reinterpret_cast<const char16_t*>(chars), size);
    env->ReleaseStringChars(value, chars);
    Need(!env->ExceptionCheck() && !result.contains(QChar(0)), "InvalidPath");
    return result;
}

QByteArray Bytes(JNIEnv *env, jbyteArray value) {
    Need(value != nullptr, "InvalidWrite");
    const auto size = env->GetArrayLength(value);
    auto result = QByteArray(size, Qt::Uninitialized);
    if (size) env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    Need(!env->ExceptionCheck(), "JavaException");
    return result;
}

jmethodID CurrentMethod(JNIEnv *env, jobject value) {
    Need(value != nullptr, "InvalidGuard");
    const auto type = env->GetObjectClass(value);
    Need(type != nullptr, "JavaException");
    const auto method = env->GetMethodID(type, "current", "()Z");
    env->DeleteLocalRef(type);
    Need(method != nullptr, "JavaException");
    return method;
}

bool CallCurrent(JNIEnv *env, jobject value, jmethodID method) {
    if (env->ExceptionCheck()) return false;
    const auto result = env->CallBooleanMethod(value, method);
    return !env->ExceptionCheck() && result == JNI_TRUE;
}

struct GlobalCurrent {
    JavaVM *vm = nullptr;
    jobject value = nullptr;
    jmethodID method = nullptr;
    ~GlobalCurrent() {
        auto env = static_cast<JNIEnv*>(nullptr);
        if (value && vm && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK)
            env->DeleteGlobalRef(value);
    }
    bool current() const {
        auto env = static_cast<JNIEnv*>(nullptr);
        return vm && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK
            && CallCurrent(env, value, method);
    }
};

struct Cached {
    jlong token = 0;
    Files::Snapshot file;
};
constexpr auto kFileKinds = size_t(Files::Name::EditCheckpoint) + 1;

struct Session {
    std::thread::id thread = std::this_thread::get_id();
    std::shared_ptr<GlobalCurrent> guard;
    std::unique_ptr<Files> files;
    std::array<std::optional<Cached>, kFileKinds> cache;
    jlong sequence = 0;
    bool closed = false;
    bool writing = false;
    void check() const {
        Need(std::this_thread::get_id() == thread, "WrongThread");
        Need(!closed, "Closed");
    }
    Cached save(size_t name, Files::Snapshot file) {
        auto &slot = cache[name];
        if (slot && slot->file == file) return *slot;
        Need(sequence < std::numeric_limits<jlong>::max(), "TokenExhausted");
        slot = Cached{ ++sequence, std::move(file) };
        return *slot;
    }
    Files::Snapshot expected(size_t name, jlong token) const {
        const auto &slot = cache[name];
        Need(token > 0 && slot && slot->token == token, "Changed");
        return slot->file;
    }
};

std::mutex RegistryMutex;
std::map<jlong, std::shared_ptr<Session>> Registry;
jlong Next = 0;

std::shared_ptr<Session> Find(jlong handle) {
    const auto lock = std::lock_guard(RegistryMutex);
    const auto i = Registry.find(handle);
    Need(i != Registry.end(), "Closed");
    i->second->check();
    return i->second;
}

size_t Index(jint name) {
    Need(name >= 0 && size_t(name) < kFileKinds, "InvalidName");
    return size_t(name);
}
Files::Name Name(size_t index) {
    switch (index) {
    case 0: return Files::Name::Library;
    case 1: return Files::Name::Local;
    case 2: return Files::Name::Backup;
    case 3: return Files::Name::SyncPublication;
    case 4: return Files::Name::EditCheckpoint;
    }
    throw Failure{"InvalidName"};
}

void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) {
        env->ThrowNew(error, message);
        env->DeleteLocalRef(error);
    }
}

void ThrowFailure(JNIEnv *env, const Failure &failure) {
    if (env->ExceptionCheck()) return;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsPhysical$Failure");
    if (!type) return;
    const auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;Z)V");
    if (!constructor) { env->DeleteLocalRef(type); return; }
    const auto message = env->NewStringUTF(failure.status);
    if (!message) { env->DeleteLocalRef(type); return; }
    const auto error = static_cast<jthrowable>(env->NewObject(type, constructor, message,
        failure.mayHaveChanged ? JNI_TRUE : JNI_FALSE));
    env->DeleteLocalRef(message);
    env->DeleteLocalRef(type);
    if (error) { env->Throw(error); env->DeleteLocalRef(error); }
}

template <typename Result, typename Action>
Result Perform(JNIEnv *env, Result fallback, Action action) {
    try { return action(); }
    catch (const Failure &failure) { ThrowFailure(env, failure); }
    catch (const std::bad_alloc &) { Throw(env, "java/lang/OutOfMemoryError", "Playlist file allocation failed"); }
    catch (...) { Throw(env, "java/io/IOException", "Playlist file operation failed"); }
    return fallback;
}

jobject Reply(JNIEnv *env, const Cached &snapshot) {
    if (env->PushLocalFrame(4) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsPhysical$NativeSnapshot");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>", "(J[B)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    auto bytes = jbyteArray(nullptr);
    if (snapshot.file.bytes) {
        Need(snapshot.file.bytes->size() <= std::numeric_limits<jsize>::max(), "AllocationLimit");
        const auto size = jsize(snapshot.file.bytes->size());
        bytes = env->NewByteArray(size);
        if (!bytes) return env->PopLocalFrame(nullptr);
        if (size) env->SetByteArrayRegion(bytes, 0, size,
            reinterpret_cast<const jbyte*>(snapshot.file.bytes->constData()));
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    const auto result = env->NewObject(type, constructor, snapshot.token, bytes);
    return env->PopLocalFrame(result);
}

jobject Publish(JNIEnv *env, jlong handle, jint name, jlong token,
        jbyteArray bytes, jobject current, bool confirmation) {
    return Perform(env, jobject(nullptr), [&] {
        const auto session = Find(handle);
        const auto index = Index(name);
        Need(!session->writing, "Busy");
        Need(confirmation || index != 3 || (bytes && env->GetArrayLength(bytes) <= 4 * (10 * 1024 * 1024 + 4096 + 4) + 44), "PublicationLimit");
        Need(confirmation || index != 4
            || (bytes && env->GetArrayLength(bytes) <= Files::kEditCheckpointBytes), "EditCheckpointLimit");
        const auto expected = session->expected(index, token);
        const auto method = CurrentMethod(env, current);
        const auto guard = [&] { return !session->closed && CallCurrent(env, current, method); };
        session->writing = true;
        struct Reset { bool &value; ~Reset() { value = false; } } reset{session->writing};
        try {
            auto actual = confirmation ? session->files->confirm(Name(index), expected, guard)
                : session->files->replace(Name(index), expected, Bytes(env, bytes), guard);
            session->check();
            return Reply(env, session->save(index, std::move(actual)));
        } catch (...) {
            session->cache[index].reset();
            throw;
        }
    });
}

QString MediaText(JNIEnv *env, jstring value, jsize limit) {
    Need(value != nullptr && env->GetStringLength(value) <= limit, "InvalidProof");
    return Text(env, value);
}

Files::MediaFile MediaProof(JNIEnv *env, jstring identity, jlong size, jstring sha256) {
    Need(size > 0, "InvalidSize");
    const auto id = MediaText(env, identity, 64).toLatin1();
    const auto hash = MediaText(env, sha256, 64).toLatin1();
    Need(!id.isEmpty() && hash.size() == 64, "InvalidProof");
    return { id, uint64_t(size), hash };
}

jobject MediaReply(JNIEnv *env, const Files::MediaFile &file) {
    Need(file.size <= uint64_t(std::numeric_limits<jlong>::max()), "InvalidSize");
    if (env->PushLocalFrame(4) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsPhysical$MediaFile");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>", "(Ljava/lang/String;JLjava/lang/String;)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    const auto identity = env->NewStringUTF(file.identity.constData());
    if (!identity) return env->PopLocalFrame(nullptr);
    const auto hash = env->NewStringUTF(file.sha256.constData());
    if (!hash) return env->PopLocalFrame(nullptr);
    return env->PopLocalFrame(env->NewObject(type, constructor, identity, jlong(file.size), hash));
}

template <typename Result, typename Action>
Result Media(JNIEnv *env, jlong handle, jobject current, Result fallback, Action action) {
    return Perform(env, fallback, [&] {
        const auto session = Find(handle);
        Need(!session->writing, "Busy");
        session->writing = true;
        struct Reset { bool &value; ~Reset() { value = false; } } reset{session->writing};
        const auto method = CurrentMethod(env, current);
        const auto guard = [&] { return !session->closed && CallCurrent(env, current, method); };
        return action(*session->files, guard);
    });
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_openNative(
        JNIEnv *env, jclass, jstring anchor, jstring relative, jobject current, jboolean createParents) {
    return Perform(env, jlong(0), [&] {
        const auto session = std::make_shared<Session>();
        const auto guard = std::make_shared<GlobalCurrent>();
        Need(env->GetJavaVM(&guard->vm) == JNI_OK, "JavaException");
        guard->method = CurrentMethod(env, current);
        guard->value = env->NewGlobalRef(current);
        Need(guard->value != nullptr, "JavaException");
        session->guard = guard;
        const auto weak = std::weak_ptr<Session>(session);
        session->files = Files::Open(Text(env, anchor), Text(env, relative), [weak] {
            const auto alive = weak.lock();
            return alive && !alive->closed && alive->guard->current();
        }, createParents == JNI_TRUE);
        const auto lock = std::lock_guard(RegistryMutex);
        Need(Next < std::numeric_limits<jlong>::max(), "HandleExhausted");
        const auto handle = ++Next;
        Registry.emplace(handle, session);
        return handle;
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_currentNative(JNIEnv *env, jclass, jlong handle) {
    return Perform(env, jboolean(JNI_FALSE), [&] {
        return jboolean(Find(handle)->files->current() ? JNI_TRUE : JNI_FALSE);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_readNative(JNIEnv *env, jclass, jlong handle, jint name) {
    return Perform(env, jobject(nullptr), [&] {
        const auto session = Find(handle);
        const auto index = Index(name);
        return Reply(env, session->save(index, session->files->read(Name(index))));
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_matchesNative(
        JNIEnv *env, jclass, jlong handle, jint name, jlong token) {
    return Perform(env, jboolean(JNI_FALSE), [&] {
        const auto session = Find(handle);
        const auto index = Index(name);
        const auto expected = session->expected(index, token);
        return jboolean(session->files->read(Name(index)) == expected ? JNI_TRUE : JNI_FALSE);
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_confirmNative(
        JNIEnv *env, jclass, jlong handle, jint name, jlong token, jobject current) {
    return Publish(env, handle, name, token, nullptr, current, true);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_replaceNative(
        JNIEnv *env, jclass, jlong handle, jint name, jlong token, jbyteArray bytes, jobject current) {
    return Publish(env, handle, name, token, bytes, current, false);
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_closeNative(JNIEnv *env, jclass, jlong handle) {
    Perform(env, false, [&] {
        const auto session = Find(handle);
        Need(!session->writing, "Busy");
        session->closed = true;
        const auto lock = std::lock_guard(RegistryMutex);
        Registry.erase(handle);
        return true;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_beginMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jlong size, jstring identity, jobject current) {
    return Media(env, handle, current, jobject(nullptr), [&](Files &files, const Files::Current &guard) {
        Need(size > 0, "InvalidSize");
        return MediaReply(env, files.beginMedia(MediaText(env, token, 32), uint64_t(size),
            MediaText(env, identity, 64).toLatin1(), guard));
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_appendMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jstring identity,
        jlong offset, jbyteArray bytes, jobject current) {
    return Media(env, handle, current, jlong(0), [&](Files &files, const Files::Current &guard) {
        Need(offset >= 0 && bytes != nullptr, "InvalidChunk");
        const auto size = env->GetArrayLength(bytes);
        Need(size > 0 && size <= 262144, "InvalidChunk");
        return jlong(files.appendMedia(MediaText(env, token, 32), MediaText(env, identity, 64).toLatin1(),
            uint64_t(offset), Bytes(env, bytes), guard));
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_sealMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jstring identity, jobject current) {
    return Media(env, handle, current, jobject(nullptr), [&](Files &files, const Files::Current &guard) {
        return MediaReply(env, files.sealMedia(MediaText(env, token, 32),
            MediaText(env, identity, 64).toLatin1(), guard));
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_publishMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jlong document, jstring extension,
        jstring identity, jlong size, jstring sha256, jobject current) {
    return Media(env, handle, current, jobject(nullptr), [&](Files &files, const Files::Current &guard) {
        Need(document > 0, "InvalidDocument");
        return MediaReply(env, files.publishMedia(MediaText(env, token, 32), uint64_t(document),
            MediaText(env, extension, 12), MediaProof(env, identity, size, sha256), guard));
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_inspectMediaNative(
        JNIEnv *env, jclass, jlong handle, jlong document, jstring extension,
        jstring identity, jlong size, jstring sha256, jobject current) {
    return Media(env, handle, current, jobject(nullptr), [&](Files &files, const Files::Current &guard) {
        Need(document > 0, "InvalidDocument");
        return MediaReply(env, files.inspectMedia(uint64_t(document), MediaText(env, extension, 12),
            MediaProof(env, identity, size, sha256), guard));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_removeMediaNative(
        JNIEnv *env, jclass, jlong handle, jlong document, jstring extension,
        jstring identity, jlong size, jstring sha256, jobject current) {
    Media(env, handle, current, false, [&](Files &files, const Files::Current &guard) {
        Need(document > 0, "InvalidDocument");
        files.removeMedia(uint64_t(document), MediaText(env, extension, 12),
            MediaProof(env, identity, size, sha256), guard);
        return true;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_discardMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jstring identity, jobject current) {
    Media(env, handle, current, false, [&](Files &files, const Files::Current &guard) {
        files.discardMedia(MediaText(env, token, 32), MediaText(env, identity, 64).toLatin1(), guard);
        return true;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_closeMediaNative(
        JNIEnv *env, jclass, jlong handle, jstring token) {
    Perform(env, false, [&] {
        const auto session = Find(handle);
        Need(!session->writing, "Busy");
        session->files->closeMedia(MediaText(env, token, 32));
        return true;
    });
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_appendMediaOverlapNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jstring identity,
        jlong offset, jbyteArray bytes, jobject current) {
    return Media(env, handle, current, jlong(0), [&](Files &files, const Files::Current &guard) {
        Need(offset >= 0 && bytes != nullptr, "InvalidChunk");
        const auto size = env->GetArrayLength(bytes);
        Need(size > 0 && size <= 262144, "InvalidChunk");
        return jlong(files.appendMediaOverlap(MediaText(env, token, 32), MediaText(env, identity, 64).toLatin1(),
            uint64_t(offset), Bytes(env, bytes), guard));
    });
}


extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_confirmMediaAbsentNative(
        JNIEnv *env, jclass, jlong handle, jlong document, jstring extension, jobject current) {
    Media(env, handle, current, false, [&](Files &files, const Files::Current &guard) {
        Need(document > 0, "InvalidDocument");
        files.confirmMediaAbsent(uint64_t(document), MediaText(env, extension, 12), guard);
        return true;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsPhysical_confirmMediaPartAbsentNative(
        JNIEnv *env, jclass, jlong handle, jstring token, jobject current) {
    Media(env, handle, current, false, [&](Files &files, const Files::Current &guard) {
        files.confirmMediaPartAbsent(MediaText(env, token, 32), guard);
        return true;
    });
}
