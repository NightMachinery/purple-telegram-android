#include <jni.h>
#include "purple/purple_playlists.h"
#include <limits>
#include <new>
#include <optional>
#include <stdexcept>

namespace {
using namespace Purple;

enum class Operation { Canonicalize, Join, Contains };

struct Answer {
    PlaylistLibraryStatus status = PlaylistLibraryStatus::Invalid;
    PlaylistLibraryError error = PlaylistLibraryError::InvalidJson;
    const char *input = "Local";
    std::optional<QByteArray> canonical;
    std::optional<bool> contains;
};

const char *StatusName(PlaylistLibraryStatus status) {
    switch (status) {
    case PlaylistLibraryStatus::Valid: return "Valid";
    case PlaylistLibraryStatus::NewerFormat: return "NewerFormat";
    case PlaylistLibraryStatus::Invalid: return "Invalid";
    }
    throw std::logic_error("Unknown playlist status");
}

const char *ErrorName(PlaylistLibraryError error) {
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

std::optional<QByteArray> Read(JNIEnv *env, jbyteArray input) {
    if (!input) return std::nullopt;
    const auto size = env->GetArrayLength(input);
    if (env->ExceptionCheck()) return std::nullopt;
    auto result = QByteArray(size, Qt::Uninitialized);
    if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) return std::nullopt;
    return result;
}

Answer Evaluate(JNIEnv *env, Operation operation, jbyteArray local, jbyteArray remote, jlong selfUserId) {
    auto answer = Answer();
    if (selfUserId <= 0) {
        answer.error = PlaylistLibraryError::InvalidSelfUserId;
        answer.input = "None";
        return answer;
    }
    const auto localBytes = Read(env, local);
    if (!localBytes) return answer;
    const auto parsedLocal = ParsePlaylistLibrary(*localBytes, uint64_t(selfUserId), std::nullopt);
    answer.status = parsedLocal.status;
    answer.error = parsedLocal.error;
    if (!parsedLocal) return answer;
    if (operation == Operation::Canonicalize) {
        const auto written = SerializePlaylistLibrary(parsedLocal.library, uint64_t(selfUserId));
        answer.status = written.status;
        answer.error = written.error;
        if (written) { answer.input = "None"; answer.canonical = written.canonical; }
        return answer;
    }
    answer.input = "Remote";
    const auto remoteBytes = Read(env, remote);
    if (!remoteBytes) {
        answer.status = PlaylistLibraryStatus::Invalid;
        answer.error = PlaylistLibraryError::InvalidJson;
        return answer;
    }
    const auto parsedRemote = ParsePlaylistLibrary(*remoteBytes, uint64_t(selfUserId), std::nullopt);
    answer.status = parsedRemote.status;
    answer.error = parsedRemote.error;
    if (!parsedRemote) return answer;
    answer.input = "None";
    if (operation == Operation::Contains) {
        const auto contained = PlaylistLibraryContains(parsedLocal.library, parsedRemote.library, uint64_t(selfUserId));
        answer.status = contained.status;
        answer.error = contained.error;
        if (contained.valid()) answer.contains = contained.contains;
        return answer;
    }
    const auto joined = JoinPlaylistLibraries(parsedLocal.library, parsedRemote.library, uint64_t(selfUserId));
    answer.status = joined.status;
    answer.error = joined.error;
    if (joined) {
        const auto written = SerializePlaylistLibrary(joined.library, uint64_t(selfUserId));
        answer.status = written.status;
        answer.error = written.error;
        if (written) answer.canonical = written.canonical;
    }
    return answer;
}

jobject Result(JNIEnv *env, const Answer &answer) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsCore$Result");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[BI)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    const auto status = env->NewStringUTF(StatusName(answer.status));
    if (!status) return env->PopLocalFrame(nullptr);
    const auto error = env->NewStringUTF(ErrorName(answer.error));
    if (!error) return env->PopLocalFrame(nullptr);
    const auto input = env->NewStringUTF(answer.input);
    if (!input) return env->PopLocalFrame(nullptr);
    auto canonical = jbyteArray(nullptr);
    if (answer.canonical) {
        if (answer.canonical->size() > std::numeric_limits<jsize>::max()) {
            env->PopLocalFrame(nullptr);
            throw std::bad_alloc();
        }
        canonical = env->NewByteArray(jsize(answer.canonical->size()));
        if (!canonical) return env->PopLocalFrame(nullptr);
        if (!answer.canonical->isEmpty()) env->SetByteArrayRegion(canonical, 0, jsize(answer.canonical->size()),
            reinterpret_cast<const jbyte*>(answer.canonical->constData()));
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    const auto result = env->NewObject(type, constructor, status, error, input, canonical,
        jint(answer.contains ? (*answer.contains ? 1 : 0) : -1));
    return env->PopLocalFrame(result);
}

void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) {
        env->ThrowNew(error, message);
        env->DeleteLocalRef(error);
    }
}

jobject Perform(JNIEnv *env, Operation operation, jbyteArray local, jbyteArray remote, jlong selfUserId) {
    try {
        const auto answer = Evaluate(env, operation, local, remote, selfUserId);
        return env->ExceptionCheck() ? nullptr : Result(env, answer);
    } catch (const std::bad_alloc &) {
        Throw(env, "java/lang/OutOfMemoryError", "Playlist bridge allocation failed");
    } catch (const std::exception &) {
        Throw(env, "java/lang/IllegalStateException", "Playlist bridge failed");
    }
    return nullptr;
}

}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsCore_canonicalizeNative(
        JNIEnv *env, jclass, jbyteArray local, jlong selfUserId) {
    return Perform(env, Operation::Canonicalize, local, nullptr, selfUserId);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsCore_joinNative(
        JNIEnv *env, jclass, jbyteArray local, jbyteArray remote, jlong selfUserId) {
    return Perform(env, Operation::Join, local, remote, selfUserId);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsCore_containsNative(
        JNIEnv *env, jclass, jbyteArray outer, jbyteArray inner, jlong selfUserId) {
    return Perform(env, Operation::Contains, outer, inner, selfUserId);
}
