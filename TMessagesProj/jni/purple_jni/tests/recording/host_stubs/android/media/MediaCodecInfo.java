package android.media;

/** A host stand-in for the CodecProfileLevel constants upstream's Track compares against. */
public final class MediaCodecInfo {
    public static final class CodecProfileLevel {
        public static final int AVCProfileBaseline = 0x01;
        public static final int AVCProfileMain = 0x02;
        public static final int AVCProfileExtended = 0x04;
        public static final int AVCProfileHigh = 0x08;
        public static final int AVCProfileHigh10 = 0x10;
        public static final int AVCProfileHigh422 = 0x20;
        public static final int AVCProfileHigh444 = 0x40;
        public static final int AVCLevel1 = 0x01;
        public static final int AVCLevel1b = 0x02;
        public static final int AVCLevel11 = 0x04;
        public static final int AVCLevel12 = 0x08;
        public static final int AVCLevel13 = 0x10;
        public static final int AVCLevel2 = 0x20;
        public static final int AVCLevel21 = 0x40;
        public static final int AVCLevel22 = 0x80;
        public static final int AVCLevel3 = 0x100;
        public static final int AVCLevel31 = 0x200;
        public static final int AVCLevel32 = 0x400;
        public static final int AVCLevel4 = 0x800;
        public static final int AVCLevel41 = 0x1000;
        public static final int AVCLevel42 = 0x2000;
        public static final int AVCLevel5 = 0x4000;
        public static final int AVCLevel51 = 0x8000;
        public static final int AVCLevel52 = 0x10000;
    }
}
