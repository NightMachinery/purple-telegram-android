package org.telegram.ui;

/** Physical bounds for the player strip; units are pixels (dp in host cases). */
public final class PurplePlayerStripLayout {
    public static final int HEIGHT_DP = 48;
    public final int titleLeft, titleWidth, backLeft, forwardLeft, speedLeft, closeLeft, playLeft;

    public PurplePlayerStripLayout(int width, int touch, int stock, int side, boolean rtl,
            boolean play, boolean close, boolean speed) {
        int leading = play ? touch : 0;
        int trailing = Math.min(side, Math.max(0, width - leading - 2 * touch
                - (close ? stock : 0) - (speed ? stock : 0)));
        playLeft = left(0, touch, width, rtl);
        closeLeft = left(width - trailing - stock, stock, width, rtl);
        if (close) trailing += stock;
        speedLeft = left(width - trailing - stock, stock, width, rtl);
        if (speed) trailing += stock;
        forwardLeft = left(width - trailing - touch, touch, width, rtl);
        trailing += touch;
        backLeft = left(width - trailing - touch, touch, width, rtl);
        trailing += touch;
        titleWidth = Math.max(0, width - leading - trailing);
        titleLeft = rtl ? trailing : leading;
    }

    private static int left(int start, int size, int width, boolean rtl) {
        return rtl ? width - start - size : start;
    }
}
