// F10's calls into purple-core for PurpleRecordingCore: the Ogg finaliser
// and page walker (voice recovery and the orphan scan), and the Recording bin
// expiry rule. See docs/media-recording-design.md, F10, and purple-core's
// docs/recording-formats.md. Nothing here keeps state, so no lock is needed.
#include <jni.h>

#include "purple/purple_ogg_repair.h"
#include "purple/purple_recording.h"
#include "purple/purple_recording_file.h"
#include "purple/purple_round_journal.h"

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

[[nodiscard]] jbyteArray Bytes(JNIEnv *env, const std::vector<std::uint8_t> &bytes) {
	const auto result = env->NewByteArray(jsize(bytes.size()));
	if (result && !bytes.empty()) {
		env->SetByteArrayRegion(result, 0, jsize(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
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

// The round video journal (F10 round): the encoders PurpleRoundJournal appends
// from the muxer's thread, so the byte layout is purple-core's alone. Each
// returns the record's bytes, or an empty array for input the core refuses.

// The file's start: the magic, the version and the header record. Tracks
// follow as the muxer learns them.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_journalStartNative(
		JNIEnv *env,
		jclass,
		jboolean splitMdat,
		jint rotation,
		jint width,
		jint height) {
	Purple::RoundConfig config;
	config.splitMdat = splitMdat == JNI_TRUE;
	config.rotation = std::uint16_t(rotation == 90 || rotation == 180 || rotation == 270 ? rotation : 0);
	config.width = std::uint32_t(width < 0 ? 0 : width);
	config.height = std::uint32_t(height < 0 ? 0 : height);
	return Bytes(env, Purple::EncodeRoundJournalStart(config));
}

// A track record. `csd' holds the codec configuration buffers in order.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_journalTrackNative(
		JNIEnv *env,
		jclass,
		jint index,
		jboolean audio,
		jbyteArray mime,
		jint timescale,
		jint first,
		jint second,
		jobjectArray csd) {
	if (index < 0 || index > 255) {
		return Bytes(env, {});
	}
	Purple::RoundTrack track;
	track.kind = audio == JNI_TRUE ? Purple::RoundTrackKind::Audio : Purple::RoundTrackKind::Video;
	track.mime = Utf8(env, mime);
	track.timescale = std::uint32_t(timescale < 0 ? 0 : timescale);
	if (audio == JNI_TRUE) {
		track.sampleRate = std::uint32_t(first < 0 ? 0 : first);
		track.channels = std::uint32_t(second < 0 ? 0 : second);
	} else {
		track.width = std::uint32_t(first < 0 ? 0 : first);
		track.height = std::uint32_t(second < 0 ? 0 : second);
	}
	const auto count = csd ? env->GetArrayLength(csd) : 0;
	for (jsize i = 0; i < count && i < 4; ++i) {
		const auto buffer = static_cast<jbyteArray>(env->GetObjectArrayElement(csd, i));
		if (!buffer) {
			continue;
		}
		const auto bytes = Utf8(env, buffer);
		track.csd.emplace_back(bytes.begin(), bytes.end());
		env->DeleteLocalRef(buffer);
	}
	return Bytes(env, Purple::EncodeRoundJournalTrack(std::uint8_t(index), track));
}

// A batch record. `samples' holds five values per sample: track, sync (0/1),
// size, file offset, presentation time in microseconds.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_journalBatchNative(
		JNIEnv *env,
		jclass,
		jlongArray samples,
		jint count) {
	const auto length = samples ? env->GetArrayLength(samples) : 0;
	if (count <= 0 || jlong(count) * 5 > length) {
		return Bytes(env, {});
	}
	std::vector<jlong> values(std::size_t(count) * 5);
	env->GetLongArrayRegion(samples, 0, jsize(values.size()), values.data());
	std::vector<Purple::RoundSample> batch;
	batch.reserve(std::size_t(count));
	for (jint i = 0; i < count; ++i) {
		const auto *v = values.data() + std::size_t(i) * 5;
		if (v[0] < 0 || v[0] > 255 || v[2] < 0 || v[2] > 0xFFFFFFFFLL || v[3] < 0) {
			return Bytes(env, {});
		}
		Purple::RoundSample sample;
		sample.track = std::uint8_t(v[0]);
		sample.sync = v[1] != 0;
		sample.size = std::uint32_t(v[2]);
		sample.offset = std::uint64_t(v[3]);
		sample.ptsUs = std::int64_t(v[4]);
		batch.push_back(sample);
	}
	return Bytes(env, Purple::EncodeRoundJournalBatch(batch));
}

// ReadRoundJournal over a journal's bytes, for checks: [status (0 missing,
// 1 unknown version, 2 no config, 3 ok), tracks, batches, samples, good bytes,
// damaged (0/1), split mdat (0/1)].
extern "C" JNIEXPORT jlongArray JNICALL
Java_org_telegram_messenger_purple_PurpleRecordingCore_journalReadNative(
		JNIEnv *env,
		jclass,
		jbyteArray journal) {
	const auto bytes = Utf8(env, journal);
	const auto read = Purple::ReadRoundJournal(
		reinterpret_cast<const std::uint8_t*>(bytes.data()),
		bytes.size());
	const jlong status = read.status == Purple::RoundJournalStatus::Missing
		? 0
		: read.status == Purple::RoundJournalStatus::UnknownVersion
		? 1
		: read.status == Purple::RoundJournalStatus::NoConfig
		? 2
		: 3;
	return Longs(env, {
		status,
		jlong(read.config.tracks.size()),
		jlong(read.batches),
		jlong(read.samples.size()),
		jlong(read.goodBytes),
		read.damaged ? 1 : 0,
		read.config.splitMdat ? 1 : 0,
	});
}
