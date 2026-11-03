// F10's calls into purple-core for PurpleRecordingCore: the Ogg finaliser
// and page walker (voice recovery and the orphan scan), and the Recording bin
// expiry rule. See docs/media-recording-design.md, F10, and purple-core's
// docs/recording-formats.md. Nothing here keeps state, so no lock is needed.
#include <jni.h>

#include "purple/purple_ogg_repair.h"
#include "purple/purple_recording.h"
#include "purple/purple_recording_file.h"

#include <cstdint>
#include <string>
#include <vector>

namespace {

[[nodiscard]] std::string Utf8(JNIEnv *env, jbyteArray bytes) {
	if (!bytes) {
		return {};
	}
	const auto size = env->GetArrayLength(bytes);
	std::string result(std::size_t(size), '\0');
	if (size > 0) {
		env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(result.data()));
	}
	return result;
}

[[nodiscard]] jlongArray Longs(JNIEnv *env, const std::vector<jlong> &values) {
	const auto result = env->NewLongArray(jsize(values.size()));
	if (result) {
		env->SetLongArrayRegion(result, 0, jsize(values.size()), values.data());
	}
	return result;
}

} // namespace

// FinaliseOggFile: [status (0 clean, 1 finalised, 2 unrecoverable), duration
// in ms (-1 unknown), bytes dropped, end-of-stream set (0/1), I/O error (0/1)].
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_finaliseOggNative(
		JNIEnv *env,
		jclass,
		jbyteArray path) {
	const auto finish = Purple::FinaliseOggFile(Utf8(env, path));
	const jlong status = finish.status == Purple::OggFinishStatus::Clean
		? 0
		: finish.status == Purple::OggFinishStatus::Finalised
		? 1
		: 2;
	return Longs(env, {
		status,
		jlong(finish.scan.durationMs),
		jlong(finish.droppedBytes),
		finish.endOfStreamSet ? 1 : 0,
		finish.ioError ? 1 : 0,
	});
}

// ScanOgg over the whole file: [read (0/1), pages, end of stream (0/1),
// good bytes, total bytes, duration in ms, has audio (0/1), opus (0/1)].
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_scanOggNative(
		JNIEnv *env,
		jclass,
		jbyteArray path) {
	std::vector<std::uint8_t> bytes;
	if (!Purple::RecordingFile::ReadAll(Utf8(env, path), bytes)) {
		return Longs(env, { 0, 0, 0, 0, 0, -1, 0, 0 });
	}
	const auto scan = Purple::ScanOgg(bytes.data(), bytes.size());
	return Longs(env, {
		1,
		jlong(scan.pages.size()),
		scan.endOfStream ? 1 : 0,
		jlong(scan.goodBytes),
		jlong(scan.totalBytes),
		jlong(scan.durationMs),
		scan.hasAudio() ? 1 : 0,
		scan.opus ? 1 : 0,
	});
}

// BinItemExpired: whether an item binned at `binnedAt' (Unix seconds) is
// expired at `now' under a keep time of `keepSeconds' (0 never clears).
extern "C" JNIEXPORT jboolean JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_binItemExpiredNative(
		JNIEnv *,
		jclass,
		jlong binnedAt,
		jlong now,
		jint keepSeconds) {
	return Purple::BinItemExpired(binnedAt, now, int(keepSeconds))
		? JNI_TRUE
		: JNI_FALSE;
}
