#include <jni.h>
#include "purple/purple_playlists_local.h"
#include <limits>
#include <algorithm>
#include <new>
#include <optional>
#include <stdexcept>
#include <utility>

namespace {
using namespace Purple;

enum class Operation { Canonicalize, Defaults, SetSync, Checkpoint };

const char *StatusName(PlaylistLocalStatus status) {
    switch (status) {
    case PlaylistLocalStatus::Valid: return "Valid";
    case PlaylistLocalStatus::NewerFormat: return "NewerFormat";
    case PlaylistLocalStatus::Invalid: return "Invalid";
    }
    throw std::logic_error("Unknown local playlist status");
}

const char *ErrorName(PlaylistLocalError error) {
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

const char *PlaylistErrorName(PlaylistLibraryError error) {
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
    throw std::logic_error("Unknown embedded playlist error");
}

PlaylistLocalWriteResult Evaluate(JNIEnv *env, Operation operation, jbyteArray local,
        jboolean enabled, jlong selfUserId, jlong now = 0, jint outcome = -1) {
    if (selfUserId <= 0) return { .error = PlaylistLocalError::InvalidSelfUserId };
    auto state = DefaultPlaylistLocalState();
    if (operation != Operation::Defaults) {
        if (!local) return {};
        const auto length = env->GetArrayLength(local);
        if (env->ExceptionCheck()) return {};
        auto bytes = QByteArray(length, Qt::Uninitialized);
        if (length) env->GetByteArrayRegion(local, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
        if (env->ExceptionCheck()) return {};
        auto parsed = ParsePlaylistLocalState(bytes, uint64_t(selfUserId), std::nullopt);
        if (!parsed) return { .status = parsed.status, .error = parsed.error,
            .playlistError = parsed.playlistError };
        state = std::move(parsed.state);
    }
    if (operation == Operation::SetSync) state.sync = enabled != JNI_FALSE;
    if (operation == Operation::Checkpoint) {
        if (now < jlong(kMinimumPlaylistStamp) || now > jlong(kMaximumPlaylistStamp))
            return { .error = PlaylistLocalError::InvalidStamp };
        if (outcome < -1 || outcome > 7) return { .error = PlaylistLocalError::InvalidStatus };
        state.status.result = outcome >= 0 ? PlaylistLocalSyncResult(outcome)
            : enabled ? PlaylistLocalSyncResult::Synced : PlaylistLocalSyncResult::Problem;
        state.status.lastStepAtMs = state.status.lastStepAtMs
            ? std::max(*state.status.lastStepAtMs, uint64_t(now)) : uint64_t(now);
        if (state.status.result == PlaylistLocalSyncResult::Synced) state.unsyncedSince.reset();
        else state.unsyncedSince = state.unsyncedSince
            ? std::min(*state.unsyncedSince, uint64_t(now)) : uint64_t(now);
    }
    return SerializePlaylistLocalState(state, uint64_t(selfUserId));
}

jobject Result(JNIEnv *env, const PlaylistLocalWriteResult &answer) {
    if (env->PushLocalFrame(8) < 0) return nullptr;
    const auto type = env->FindClass("org/telegram/messenger/purple/PurplePlaylistsLocalCore$Result");
    if (!type) return env->PopLocalFrame(nullptr);
    const auto constructor = env->GetMethodID(type, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[B)V");
    if (!constructor) return env->PopLocalFrame(nullptr);
    const auto status = env->NewStringUTF(StatusName(answer.status));
    if (!status) return env->PopLocalFrame(nullptr);
    const auto error = env->NewStringUTF(ErrorName(answer.error));
    if (!error) return env->PopLocalFrame(nullptr);
    const auto playlistError = env->NewStringUTF(PlaylistErrorName(answer.playlistError));
    if (!playlistError) return env->PopLocalFrame(nullptr);
    auto canonical = jbyteArray(nullptr);
    if (answer) {
        if (answer.json.size() > std::numeric_limits<jsize>::max()) {
            env->PopLocalFrame(nullptr);
            throw std::bad_alloc();
        }
        canonical = env->NewByteArray(jsize(answer.json.size()));
        if (!canonical) return env->PopLocalFrame(nullptr);
        if (!answer.json.isEmpty()) env->SetByteArrayRegion(canonical, 0, jsize(answer.json.size()),
            reinterpret_cast<const jbyte*>(answer.json.constData()));
        if (env->ExceptionCheck()) return env->PopLocalFrame(nullptr);
    }
    const auto result = env->NewObject(type, constructor, status, error, playlistError, canonical);
    return env->PopLocalFrame(result);
}

void Throw(JNIEnv *env, const char *type, const char *message) {
    if (env->ExceptionCheck()) return;
    if (const auto error = env->FindClass(type)) {
        env->ThrowNew(error, message);
        env->DeleteLocalRef(error);
    }
}

jobject Perform(JNIEnv *env, Operation operation, jbyteArray local, jboolean enabled, jlong selfUserId, jlong now = 0, jint outcome = -1) {
    try {
        const auto answer = Evaluate(env, operation, local, enabled, selfUserId, now, outcome);
        return env->ExceptionCheck() ? nullptr : Result(env, answer);
    } catch (const std::bad_alloc &) {
        Throw(env, "java/lang/OutOfMemoryError", "Local playlist bridge allocation failed");
    } catch (const std::exception &) {
        Throw(env, "java/lang/IllegalStateException", "Local playlist bridge failed");
    }
    return nullptr;
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsLocalCore_canonicalizeNative(
        JNIEnv *env, jclass, jbyteArray local, jlong selfUserId) {
    return Perform(env, Operation::Canonicalize, local, JNI_FALSE, selfUserId);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsLocalCore_defaultsNative(
        JNIEnv *env, jclass, jlong selfUserId) {
    return Perform(env, Operation::Defaults, nullptr, JNI_FALSE, selfUserId);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsLocalCore_setSyncNative(
        JNIEnv *env, jclass, jbyteArray local, jboolean enabled, jlong selfUserId) {
    return Perform(env, Operation::SetSync, local, enabled, selfUserId);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsLocalCore_checkpointNative(
        JNIEnv *env, jclass, jbyteArray local, jboolean synced, jlong now, jlong selfUserId) {
    return Perform(env, Operation::Checkpoint, local, synced, selfUserId, now);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_telegram_messenger_purple_PurplePlaylistsLocalCore_outcomeNative(
        JNIEnv *env, jclass, jbyteArray local, jint outcome, jlong now, jlong selfUserId) {
    return Perform(env, Operation::Checkpoint, local, JNI_FALSE, selfUserId, now, outcome);
}
