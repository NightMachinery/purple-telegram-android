package org.telegram.messenger.purple;

public final class PurpleRoundBubbleGeometry {
    public static int clamp(int value, int maximum) { return Math.max(0, Math.min(value, Math.max(0, maximum))); }
    public static int corner(int x, int y, int width, int height, int size) {
        return (x + size / 2 >= width / 2 ? 1 : 0) | (y + size / 2 >= height / 2 ? 2 : 0);
    }
    public static int x(int corner, int width, int size, int inset) {
        return clamp((corner & 1) != 0 ? width - size - inset : inset, width - size);
    }
    public static int y(int corner, int height, int size, int inset) {
        return clamp((corner & 2) != 0 ? height - size - inset : inset, height - size);
    }
    private PurpleRoundBubbleGeometry() { }
}
