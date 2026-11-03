// The AAC measure shim (purple_jni/aac_measure.cpp) against a real AAC stream
// the driver encodes with ffmpeg at test time: each raw frame's length, as the
// stream's ADTS headers state it, must be what the shim measures from the raw
// frames alone (MP4Builder stores AAC without ADTS headers).
//
// usage: aac_measure_cases <stream.adts> [<stream.adts> ...]
#include "aac_measure.h"

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

int checks = 0;
int failures = 0;

void check(bool ok, const std::string &what) {
	++checks;
	if (!ok) {
		++failures;
		std::printf("FAIL %s\n", what.c_str());
	}
}

struct Stream {
	std::vector<std::uint8_t> asc;
	std::vector<std::uint8_t> raw;
	std::vector<std::size_t> lengths;
};

// Splits an ADTS stream into raw frames and builds the AudioSpecificConfig
// MediaCodec would hand MP4Builder for it.
bool ReadAdts(const std::string &path, Stream &stream) {
	std::ifstream file(path, std::ios::binary);
	const std::vector<std::uint8_t> bytes(
		(std::istreambuf_iterator<char>(file)),
		std::istreambuf_iterator<char>());
	std::size_t at = 0;
	while (at + 7 <= bytes.size()) {
		const auto *h = bytes.data() + at;
		if (h[0] != 0xFF || (h[1] & 0xF0) != 0xF0) {
			return false;
		}
		const bool protection = !(h[1] & 0x01);
		const int profile = (h[2] >> 6) & 0x03;
		const int frequency = (h[2] >> 2) & 0x0F;
		const int channels = ((h[2] & 0x01) << 2) | ((h[3] >> 6) & 0x03);
		const std::size_t length = (std::size_t(h[3] & 0x03) << 11)
			| (std::size_t(h[4]) << 3)
			| (std::size_t(h[5]) >> 5);
		const std::size_t header = protection ? 9 : 7;
		if ((h[6] & 0x03) != 0 || length <= header || at + length > bytes.size()) {
			return false;
		}
		if (stream.asc.empty()) {
			const int objectType = profile + 1;
			stream.asc = {
				std::uint8_t((objectType << 3) | (frequency >> 1)),
				std::uint8_t(((frequency & 1) << 7) | (channels << 3)),
			};
		}
		stream.raw.insert(stream.raw.end(), h + header, h + length);
		stream.lengths.push_back(length - header);
		at += length;
	}
	return at == bytes.size() && !stream.lengths.empty();
}

void Cases(const std::string &path) {
	Stream stream;
	check(ReadAdts(path, stream), path + ": the test stream is ADTS");
	if (stream.lengths.empty()) {
		return;
	}
	PurpleAac::Measurer measurer(stream.asc.data(), stream.asc.size());
	check(measurer.valid(), path + ": the decoder opens with the stream's AudioSpecificConfig");
	std::size_t offset = 0;
	int matched = 0;
	for (std::size_t i = 0; i != stream.lengths.size(); ++i) {
		const auto length = stream.lengths[i];
		const auto rest = stream.raw.size() - offset;
		const int measured = measurer.measure(stream.raw.data() + offset, rest);
		if (measured == int(length)) {
			++matched;
		} else {
			check(false, path + ": frame " + std::to_string(i) + " measures "
				+ std::to_string(measured) + ", ADTS says " + std::to_string(length));
		}
		// Exactly the frame is enough, one byte less is not.
		check(measurer.measure(stream.raw.data() + offset, length) == int(length),
			path + ": frame " + std::to_string(i) + " alone measures its length");
		check(measurer.measure(stream.raw.data() + offset, length - 1) <= 0,
			path + ": frame " + std::to_string(i) + " cut by one byte does not measure");
		offset += length;
	}
	check(matched == int(stream.lengths.size()), path + ": every frame measures its ADTS length");
	check(stream.lengths.size() >= 40, path + ": the stream has enough frames to mean something");

	// Bad input never measures.
	const std::uint8_t bogus[] = { 0xFF, 0xFF };
	PurpleAac::Measurer broken(bogus, 0);
	check(!broken.valid(), "an empty AudioSpecificConfig does not open");
	check(broken.measure(stream.raw.data(), stream.raw.size()) == 0, "a decoder that did not open measures nothing");
	check(measurer.measure(nullptr, 100) == 0, "no data measures nothing");
	check(measurer.measure(stream.raw.data(), 0) == 0, "zero bytes measure nothing");
	std::vector<std::uint8_t> zeros(4096, 0);
	check(measurer.measure(zeros.data(), zeros.size()) <= 0 || measurer.measure(zeros.data(), zeros.size()) < 4096,
		"zeros never measure as a frame that spans them");
}

} // namespace

int main(int argc, char **argv) {
	if (argc < 2) {
		std::fprintf(stderr, "usage: %s <stream.adts>...\n", argv[0]);
		return 2;
	}
	for (int i = 1; i < argc; ++i) {
		Cases(argv[i]);
	}
	std::printf("%d checks, %d failures\n", checks, failures);
	std::printf("AAC_MEASURE_CHECKS=%d\n", checks);
	return failures ? 1 : 0;
}
