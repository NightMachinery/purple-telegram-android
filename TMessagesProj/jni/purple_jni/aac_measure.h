// The AAC measure shim for round video repair (F10, docs/media-recording-design.md
// "Round video: repair"). purple-core's scanner cannot tell where a raw AAC
// frame ends, because MP4Builder writes AAC without ADTS headers; this shim
// asks FFmpeg's AAC decoder. It is compiled into upstream's native library
// through purple.cmake, because that library links avcodec and libpurplecore
// does not.
#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

struct AVCodecContext;
struct AVFrame;
struct AVPacket;

namespace PurpleAac {

// The largest raw AAC frame the shim measures, as purple-core's
// kRoundMaxAacFrame.
inline constexpr std::size_t kMaxFrame = 8192;

// One decoder opened with one AudioSpecificConfig. Not thread safe: a
// recovery makes its own.
class Measurer {
public:
	Measurer(const std::uint8_t *asc, std::size_t ascSize);
	~Measurer();
	Measurer(const Measurer &) = delete;
	Measurer &operator=(const Measurer &) = delete;

	// Whether the decoder opened with the configuration.
	[[nodiscard]] bool valid() const {
		return context_ != nullptr;
	}

	// The bytes the raw AAC frame (1024 samples) at the start of `data'
	// takes, or 0 when no whole frame decodes there. Reads at most
	// min(available, kMaxFrame) bytes of `data'.
	//
	// FFmpeg's public decode API does not report the bytes a packet's first
	// frame consumed, so the length is found as the shortest prefix that
	// decodes to a whole frame: a raw_data_block cut before its END element
	// does not decode, and one with bytes after it decodes the same frame and
	// leaves the rest. The prefix is found by bisection, each try from a
	// flushed decoder, about 13 decodes per frame.
	[[nodiscard]] int measure(const std::uint8_t *data, std::size_t available);

private:
	[[nodiscard]] bool decodes(const std::uint8_t *data, std::size_t size);

	AVCodecContext *context_ = nullptr;
	AVFrame *frame_ = nullptr;
	AVPacket *packet_ = nullptr;
	std::vector<std::uint8_t> buffer_;
};

} // namespace PurpleAac
