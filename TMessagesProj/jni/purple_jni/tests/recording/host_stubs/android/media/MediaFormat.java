package android.media;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A host stand-in for the parts of android.media.MediaFormat that upstream's
 * Track and Purple's round journal and recovery use; android.jar's own class
 * only throws on the host. Like Android, getInteger of a missing key throws.
 */
public final class MediaFormat {
    public static final String KEY_MIME = "mime";
    public static final String KEY_WIDTH = "width";
    public static final String KEY_HEIGHT = "height";
    public static final String KEY_SAMPLE_RATE = "sample-rate";
    public static final String KEY_CHANNEL_COUNT = "channel-count";
    public static final String KEY_BIT_RATE = "bitrate";
    public static final String KEY_MAX_INPUT_SIZE = "max-input-size";

    private final Map<String, Object> values = new HashMap<>();

    public static MediaFormat createVideoFormat(String mime, int width, int height) {
        final MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setInteger(KEY_WIDTH, width);
        format.setInteger(KEY_HEIGHT, height);
        return format;
    }

    public static MediaFormat createAudioFormat(String mime, int sampleRate, int channelCount) {
        final MediaFormat format = new MediaFormat();
        format.setString(KEY_MIME, mime);
        format.setInteger(KEY_SAMPLE_RATE, sampleRate);
        format.setInteger(KEY_CHANNEL_COUNT, channelCount);
        return format;
    }

    public boolean containsKey(String name) {
        return values.containsKey(name);
    }

    public int getInteger(String name) {
        return (Integer) values.get(name);
    }

    public int getInteger(String name, int defaultValue) {
        final Object value = values.get(name);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    public long getLong(String name) {
        return (Long) values.get(name);
    }

    public String getString(String name) {
        return (String) values.get(name);
    }

    public ByteBuffer getByteBuffer(String name) {
        return (ByteBuffer) values.get(name);
    }

    public void setInteger(String name, int value) {
        values.put(name, value);
    }

    public void setLong(String name, long value) {
        values.put(name, value);
    }

    public void setString(String name, String value) {
        values.put(name, value);
    }

    public void setByteBuffer(String name, ByteBuffer bytes) {
        values.put(name, bytes);
    }
}
