// F10 round video recovery for PurpleRoundRecovery: purple-core's
// RecoverRound (the journal's exact samples, then the repair scan) with the
// AAC measure shim. Compiled into upstream's native library through
// purple.cmake, beside the shim, because the shim needs avcodec. The bridge
// returns the sample list; Java rebuilds the file through upstream's
// MP4Builder. See docs/media-recording-design.md, "Round video".
#include <jni.h>

#include "aac_measure.h"
#include "purple/purple_mp4_repair.h"
#include "purple/purple_recording_file.h"

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {

// Round videos are at most a minute; a file far beyond that is not one.
constexpr std::uint64_t kMaxRoundBytes = 256ULL * 1024 * 1024;

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
	if (result && !values.empty()) {
		env->SetLongArrayRegion(result, 0, jsize(values.size()), values.data());
	}
	return result;
}

[[nodiscard]] jintArray Ints(JNIEnv *env, const std::vector<jint> &values) {
	const auto result = env->NewIntArray(jsize(values.size()));
	if (result && !values.empty()) {
		env->SetIntArrayRegion(result, 0, jsize(values.size()), values.data());
	}
	return result;
}

[[nodiscard]] jint Status(Purple::RoundRecoveryStatus status) {
	switch (status) {
	case Purple::RoundRecoveryStatus::Exact: return 0;
	case Purple::RoundRecoveryStatus::Repaired: return 1;
	case Purple::RoundRecoveryStatus::Partial: return 2;
	case Purple::RoundRecoveryStatus::Unrecoverable: return 3;
	}
	return 3;
}

[[nodiscard]] jint Reason(Purple::RoundUnrecoverable reason) {
	switch (reason) {
	case Purple::RoundUnrecoverable::None: return 0;
	case Purple::RoundUnrecoverable::NoConfig: return 1;
	case Purple::RoundUnrecoverable::UnknownVersion: return 2;
	case Purple::RoundUnrecoverable::NotMp4: return 3;
	case Purple::RoundUnrecoverable::NoVideoTrack: return 4;
	case Purple::RoundUnrecoverable::NoSamples: return 5;
	}
	return 0;
}

} // namespace

// Recovers the round at `media' with its journal at `journal' (may be
// missing). Returns {long[] summary, long[] samples, String[] mimes,
// int[] tracks, byte[][] csd, int[] config}:
//   summary: status (0 exact, 1 repaired, 2 partial, 3 unrecoverable),
//     reason, exact samples, repaired samples, journal samples dropped,
//     unparsed bytes, encoder offset (us), media read (0/1);
//   samples: five values each: track, sync (0/1), size, offset, pts (us);
//   tracks: seven values each: kind (1 video, 2 audio), timescale, width,
//     height, sample rate, channels, csd count;
//   csd: every track's buffers in track order;
//   config: split mdat (0/1), width, height, rotation.
// Null only when Java objects cannot be made.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_telegram_messenger_purple_PurpleRoundRecovery_recoverNative(
		JNIEnv *env,
		jclass,
		jbyteArray mediaPath,
		jbyteArray journalPath) {
	std::vector<std::uint8_t> media;
	std::vector<std::uint8_t> journal;
	const bool read = Purple::RecordingFile::ReadAll(Utf8(env, mediaPath), media, kMaxRoundBytes);
	if (journalPath) {
		if (!Purple::RecordingFile::ReadAll(Utf8(env, journalPath), journal, kMaxRoundBytes)) {
			journal.clear();
		}
	}

	std::unique_ptr<PurpleAac::Measurer> measurer;
	std::vector<std::uint8_t> measurerConfig;
	const Purple::AacFrameMeasure measure = [&](
			const Purple::RoundTrack &audio,
			const std::uint8_t *data,
			std::size_t available) -> int {
		if (audio.csd.empty()) {
			return 0;
		}
		if (!measurer || measurerConfig != audio.csd[0]) {
			measurerConfig = audio.csd[0];
			measurer = std::make_unique<PurpleAac::Measurer>(measurerConfig.data(), measurerConfig.size());
		}
		return measurer->valid() ? measurer->measure(data, available) : 0;
	};
	const auto recovery = read
		? Purple::RecoverRound(media.data(), media.size(), journal.data(), journal.size(), measure)
		: Purple::RoundRecovery();

	std::vector<jlong> samples;
	samples.reserve(recovery.samples.size() * 5);
	for (const auto &sample : recovery.samples) {
		samples.push_back(sample.track);
		samples.push_back(sample.sync ? 1 : 0);
		samples.push_back(jlong(sample.size));
		samples.push_back(jlong(sample.offset));
		samples.push_back(jlong(sample.ptsUs));
	}
	std::vector<jint> tracks;
	std::vector<const std::vector<std::uint8_t>*> csd;
	const auto stringClass = env->FindClass("java/lang/String");
	const auto bytesClass = env->FindClass("[B");
	const auto objectClass = env->FindClass("java/lang/Object");
	if (!stringClass || !bytesClass || !objectClass) {
		return nullptr;
	}
	const auto mimes = env->NewObjectArray(jsize(recovery.config.tracks.size()), stringClass, nullptr);
	if (!mimes) {
		return nullptr;
	}
	for (std::size_t i = 0; i != recovery.config.tracks.size(); ++i) {
		const auto &track = recovery.config.tracks[i];
		tracks.push_back(track.kind == Purple::RoundTrackKind::Audio ? 2 : 1);
		tracks.push_back(jint(track.timescale));
		tracks.push_back(jint(track.width));
		tracks.push_back(jint(track.height));
		tracks.push_back(jint(track.sampleRate));
		tracks.push_back(jint(track.channels));
		tracks.push_back(jint(track.csd.size()));
		for (const auto &buffer : track.csd) {
			csd.push_back(&buffer);
		}
		// MIME types are ASCII ("video/avc", "audio/mp4a-latm"); anything
		// else is dropped rather than handed to NewStringUTF.
		std::string mime;
		for (const auto c : track.mime) {
			if (c > 0x20 && c < 0x7f) {
				mime.push_back(c);
			}
		}
		const auto value = env->NewStringUTF(mime.c_str());
		env->SetObjectArrayElement(mimes, jsize(i), value);
		env->DeleteLocalRef(value);
	}
	const auto buffers = env->NewObjectArray(jsize(csd.size()), bytesClass, nullptr);
	if (!buffers) {
		return nullptr;
	}
	for (std::size_t i = 0; i != csd.size(); ++i) {
		const auto &buffer = *csd[i];
		const auto value = env->NewByteArray(jsize(buffer.size()));
		if (!value) {
			return nullptr;
		}
		if (!buffer.empty()) {
			env->SetByteArrayRegion(value, 0, jsize(buffer.size()), reinterpret_cast<const jbyte*>(buffer.data()));
		}
		env->SetObjectArrayElement(buffers, jsize(i), value);
		env->DeleteLocalRef(value);
	}
	const auto result = env->NewObjectArray(6, objectClass, nullptr);
	if (!result) {
		return nullptr;
	}
	env->SetObjectArrayElement(result, 0, Longs(env, {
		Status(recovery.status),
		Reason(recovery.reason),
		jlong(recovery.exactSamples),
		jlong(recovery.repairedSamples),
		jlong(recovery.journalDropped),
		jlong(recovery.unparsedBytes),
		jlong(recovery.encoderOffsetUs),
		read ? 1 : 0,
	}));
	env->SetObjectArrayElement(result, 1, Longs(env, samples));
	env->SetObjectArrayElement(result, 2, mimes);
	env->SetObjectArrayElement(result, 3, Ints(env, tracks));
	env->SetObjectArrayElement(result, 4, buffers);
	env->SetObjectArrayElement(result, 5, Ints(env, {
		recovery.config.splitMdat ? 1 : 0,
		jint(recovery.config.width),
		jint(recovery.config.height),
		jint(recovery.config.rotation),
	}));
	return result;
}
