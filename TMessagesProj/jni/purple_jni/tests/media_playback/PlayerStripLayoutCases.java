package org.telegram.ui;

public final class PlayerStripLayoutCases {
    private static int checks;
    private static void check(boolean value, String name) {
        checks++;
        if (!value) throw new AssertionError(name);
    }
    private static boolean overlap(int a, int aw, int b, int bw) {
        return aw > 0 && bw > 0 && a < b + bw && b < a + aw;
    }
    public static void main(String[] args) {
        check(PurplePlayerStripLayout.HEIGHT_DP == 48, "48dp targets fit vertically");
        for (int width : new int[]{240, 280, 320, 360, 480, 800}) {
            for (boolean rtl : new boolean[]{false, true}) {
                for (boolean play : new boolean[]{false, true}) {
                    for (boolean close : new boolean[]{false, true}) {
                        for (boolean speed : new boolean[]{false, true}) {
                            for (int side : new int[]{0, 64}) {
                                PurplePlayerStripLayout b = new PurplePlayerStripLayout(width, 48, 36, side, rtl, play, close, speed);
                                check(b.titleLeft >= 0 && b.titleWidth >= 0 && b.titleLeft + b.titleWidth <= width, "title bounded");
                                int[] x = {b.backLeft, b.forwardLeft, b.playLeft, b.closeLeft, b.speedLeft};
                                int[] w = {48, 48, play ? 48 : 0, close ? 36 : 0, speed ? 36 : 0};
                                for (int i = 0; i < x.length; i++) {
                                    if (w[i] == 0) continue;
                                    check(x[i] >= 0 && x[i] + w[i] <= width, "control bounded");
                                    check(!overlap(b.titleLeft, b.titleWidth, x[i], w[i]), "title clear of controls");
                                    for (int k = 0; k < i; k++) check(!overlap(x[i], w[i], x[k], w[k]), "controls do not overlap");
                                }
                                PurplePlayerStripLayout mirrored = new PurplePlayerStripLayout(width, 48, 36, side, !rtl, play, close, speed);
                                check(b.backLeft + mirrored.backLeft + 48 == width, "back mirrors");
                                check(b.forwardLeft + mirrored.forwardLeft + 48 == width, "forward mirrors");
                                check(b.titleWidth == mirrored.titleWidth && b.titleLeft + mirrored.titleLeft + b.titleWidth == width, "title mirrors");
                            }
                        }
                    }
                }
            }
        }
        System.out.println("MEDIA_STRIP_LAYOUT_CHECKS=" + checks);
    }
}
