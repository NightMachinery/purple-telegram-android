#include "aac_measure.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/mem.h>
}

#include <algorithm>
#include <cstring>

namespace PurpleAac {

Measurer::Measurer(const std::uint8_t *asc, std::size_t ascSize) {
	if (!asc || ascSize == 0 || ascSize > 64) {
		return;
	}
	const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_AAC);
	if (!codec) {
		return;
	}
	AVCodecContext *context = avcodec_alloc_context3(codec);
	if (!context) {
		return;
	}
	context->extradata = static_cast<std::uint8_t*>(
		av_mallocz(ascSize + AV_INPUT_BUFFER_PADDING_SIZE));
	if (!context->extradata) {
		avcodec_free_context(&context);
		return;
	}
	std::memcpy(context->extradata, asc, ascSize);
	context->extradata_size = int(ascSize);
	if (avcodec_open2(context, codec, nullptr) < 0) {
		avcodec_free_context(&context);
		return;
	}
	frame_ = av_frame_alloc();
	packet_ = av_packet_alloc();
	if (!frame_ || !packet_) {
		av_frame_free(&frame_);
		av_packet_free(&packet_);
		avcodec_free_context(&context);
		return;
	}
	buffer_.assign(kMaxFrame + AV_INPUT_BUFFER_PADDING_SIZE, 0);
	context_ = context;
}

Measurer::~Measurer() {
	av_frame_free(&frame_);
	av_packet_free(&packet_);
	avcodec_free_context(&context_);
}

bool Measurer::decodes(const std::uint8_t *data, std::size_t size) {
	// A private copy with zeroed padding, so the decoder never reads the
	// caller's bytes past `size'.
	std::memcpy(buffer_.data(), data, size);
	std::memset(buffer_.data() + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
	avcodec_flush_buffers(context_);
	av_packet_unref(packet_);
	packet_->data = buffer_.data();
	packet_->size = int(size);
	if (avcodec_send_packet(context_, packet_) < 0) {
		packet_->data = nullptr;
		packet_->size = 0;
		return false;
	}
	packet_->data = nullptr;
	packet_->size = 0;
	const int received = avcodec_receive_frame(context_, frame_);
	const bool whole = received >= 0 && frame_->nb_samples == 1024;
	av_frame_unref(frame_);
	return whole;
}

int Measurer::measure(const std::uint8_t *data, std::size_t available) {
	if (!context_ || !data || available == 0) {
		return 0;
	}
	const std::size_t limit = std::min(available, kMaxFrame);
	if (!decodes(data, limit)) {
		return 0;
	}
	// The shortest prefix that still decodes: everything below the frame's
	// length fails, everything from it on succeeds.
	std::size_t low = 1;
	std::size_t high = limit;
	while (low < high) {
		const std::size_t middle = low + (high - low) / 2;
		if (decodes(data, middle)) {
			high = middle;
		} else {
			low = middle + 1;
		}
	}
	return int(high);
}

} // namespace PurpleAac
