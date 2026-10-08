package dsp.machine;

import dsp.core.Key;

/** Shared ZX Spectrum ULA tables and palette, from dsp-cpp spectrum_*.cpp. */
public final class SpectrumHw {
    private SpectrumHw() {}

    public static final int SCREEN_WIDTH = 352;
    public static final int SCREEN_HEIGHT = 280;

    public static final int[] PALETTE = {
            argb(0x000000), argb(0xC00000), argb(0x0000C0), argb(0xC000C0),
            argb(0x00C000), argb(0xC0C000), argb(0x00C0C0), argb(0xC0C0C0),
            argb(0x000000), argb(0xFF0000), argb(0x0000FF), argb(0xFF00FF),
            argb(0x00FF00), argb(0xFFFF00), argb(0x00FFFF), argb(0xFFFFFF)
    };

    public static final int[] CMEMORY = {
            6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0,
            6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0,
            6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0,
            6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0, 6, 5, 4, 3, 2, 1, 0, 0
    };

    public static final int[] SCR_TABLE = {
            0, 256, 512, 768, 1024, 1280, 1536, 1792, 32, 288, 544, 800, 1056, 1312, 1568, 1824,
            64, 320, 576, 832, 1088, 1344, 1600, 1856, 96, 352, 608, 864, 1120, 1376, 1632, 1888,
            128, 384, 640, 896, 1152, 1408, 1664, 1920, 160, 416, 672, 928, 1184, 1440, 1696, 1952,
            192, 448, 704, 960, 1216, 1472, 1728, 1984, 224, 480, 736, 992, 1248, 1504, 1760, 2016,
            2048, 2304, 2560, 2816, 3072, 3328, 3584, 3840, 2080, 2336, 2592, 2848, 3104, 3360, 3616, 3872,
            2112, 2368, 2624, 2880, 3136, 3392, 3648, 3904, 2144, 2400, 2656, 2912, 3168, 3424, 3680, 3936,
            2176, 2432, 2688, 2944, 3200, 3456, 3712, 3968, 2208, 2464, 2720, 2976, 3232, 3488, 3744, 4000,
            2240, 2496, 2752, 3008, 3264, 3520, 3776, 4032, 2272, 2528, 2784, 3040, 3296, 3552, 3808, 4064,
            4096, 4352, 4608, 4864, 5120, 5376, 5632, 5888, 4128, 4384, 4640, 4896, 5152, 5408, 5664, 5920,
            4160, 4416, 4672, 4928, 5184, 5440, 5696, 5952, 4192, 4448, 4704, 4960, 5216, 5472, 5728, 5984,
            4224, 4480, 4736, 4992, 5248, 5504, 5760, 6016, 4256, 4512, 4768, 5024, 5280, 5536, 5792, 6048,
            4288, 4544, 4800, 5056, 5312, 5568, 5824, 6080, 4320, 4576, 4832, 5088, 5344, 5600, 5856, 6112
    };

    public static final Key[][] MATRIX = {
            {Key.LEFT_SHIFT, Key.Z, Key.X, Key.C, Key.V},
            {Key.A, Key.S, Key.D, Key.F, Key.G},
            {Key.Q, Key.W, Key.E, Key.R, Key.T},
            {Key.NUM1, Key.NUM2, Key.NUM3, Key.NUM4, Key.NUM5},
            {Key.NUM0, Key.NUM9, Key.NUM8, Key.NUM7, Key.NUM6},
            {Key.P, Key.O, Key.I, Key.U, Key.Y},
            {Key.ENTER, Key.L, Key.K, Key.J, Key.H},
            {Key.SPACE, Key.RIGHT_CTRL, Key.M, Key.N, Key.B}
    };

    public static int argb(int bgr) {
        int blue = (bgr >> 16) & 0xff;
        int green = (bgr >> 8) & 0xff;
        int red = bgr & 0xff;
        return 0xff000000 | (red << 16) | (green << 8) | blue;
    }

    public static int ulaplusDecode(int value) {
        value &= 0xff;
        int b = 0x21 * (value & 1) + 0x47 * (value & 1) + 0x97 * ((value >> 1) & 1);
        int r = 0x21 * ((value >> 2) & 1) + 0x47 * ((value >> 3) & 1) + 0x97 * ((value >> 4) & 1);
        int g = 0x21 * ((value >> 5) & 1) + 0x47 * ((value >> 6) & 1) + 0x97 * ((value >> 7) & 1);
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }

    public static int paperPixel(int attrib, int pixels, int bit, boolean flash, int[] palette) {
        int ink = attrib & 7;
        int paper = (attrib >> 3) & 7;
        if ((attrib & 0x40) != 0) {
            ink += 8;
            paper += 8;
        }
        if ((attrib & 0x80) != 0 && flash) {
            int tmp = ink;
            ink = paper;
            paper = tmp;
        }
        return ((pixels << bit) & 0x80) != 0 ? palette[ink] : palette[paper];
    }
}
