package dsp.machine;

/** Decoded ZX Spectrum snapshot (SNA or Z80). Ported from dsp-cpp spectrum_snap.cpp. */
public final class SpectrumSnap {
    public int a, f, b, c, d, e, h, l;
    public int a2, f2, b2, c2, d2, e2, h2, l2;
    public int ix, iy, sp, pc;
    public int i, r, im;
    public boolean iff1, iff2;
    public int border = 7;
    public int port7ffd;
    public int port1ffd;
    public boolean trdosPaged;
    public boolean ayUsed;
    public int ayLatch;
    public final int[] ayRegs = new int[16];
    public boolean is128;
    public final byte[] ram48 = new byte[0xc000];
    public final byte[][] banks = new byte[8][0x4000];

    public static boolean fromSna(byte[] data, SpectrumSnap out, StringBuilder error) {
        final int sna48 = 27 + 0xc000;
        final int sna128Min = sna48 + 4;
        if (data == null || data.length < sna48) {
            SpectrumFiles.fail(error, "SNA too small");
            return false;
        }
        out.clear();
        out.i = u8(data[0]);
        out.l2 = u8(data[1]);
        out.h2 = u8(data[2]);
        out.e2 = u8(data[3]);
        out.d2 = u8(data[4]);
        out.c2 = u8(data[5]);
        out.b2 = u8(data[6]);
        out.f2 = u8(data[7]);
        out.a2 = u8(data[8]);
        out.l = u8(data[9]);
        out.h = u8(data[10]);
        out.e = u8(data[11]);
        out.d = u8(data[12]);
        out.c = u8(data[13]);
        out.b = u8(data[14]);
        out.iy = rd16(data, 15);
        out.ix = rd16(data, 17);
        out.iff2 = (data[19] & 4) != 0;
        out.iff1 = out.iff2;
        out.r = u8(data[20]);
        out.f = u8(data[21]);
        out.a = u8(data[22]);
        out.sp = rd16(data, 23);
        out.im = data[25] & 3;
        out.border = data[26] & 7;

        if (data.length >= sna128Min) {
            out.is128 = true;
            out.pc = rd16(data, sna48);
            out.port7ffd = u8(data[sna48 + 2]);
            out.trdosPaged = data[sna48 + 3] != 0;
            int paged = out.port7ffd & 7;
            System.arraycopy(data, 27, out.banks[5], 0, 0x4000);
            System.arraycopy(data, 27 + 0x4000, out.banks[2], 0, 0x4000);
            System.arraycopy(data, 27 + 0x8000, out.banks[paged], 0, 0x4000);
            int off = sna128Min;
            for (int b = 0; b < 8; b++) {
                if (b == 5 || b == 2 || b == paged) {
                    continue;
                }
                if (off + 0x4000 > data.length) {
                    break;
                }
                System.arraycopy(data, off, out.banks[b], 0, 0x4000);
                off += 0x4000;
            }
            System.arraycopy(out.banks[5], 0, out.ram48, 0, 0x4000);
            System.arraycopy(out.banks[2], 0, out.ram48, 0x4000, 0x4000);
            System.arraycopy(out.banks[paged], 0, out.ram48, 0x8000, 0x4000);
        } else {
            out.is128 = false;
            System.arraycopy(data, 27, out.ram48, 0, 0xc000);
            System.arraycopy(out.ram48, 0, out.banks[5], 0, 0x4000);
            System.arraycopy(out.ram48, 0x4000, out.banks[2], 0, 0x4000);
            System.arraycopy(out.ram48, 0x8000, out.banks[0], 0, 0x4000);
            int sp = out.sp;
            if (sp < 0x4000 || sp + 1 > 0xffff) {
                SpectrumFiles.fail(error, "SNA SP out of range");
                return false;
            }
            out.pc = rd16(out.ram48, sp - 0x4000);
            out.sp = (sp + 2) & 0xffff;
        }
        return true;
    }

    public static boolean fromZ80(byte[] data, SpectrumSnap out, StringBuilder error) {
        if (data == null || data.length < 30) {
            SpectrumFiles.fail(error, "Z80 snapshot too small");
            return false;
        }
        out.clear();
        applyRegsFromZ80Header(out, data);
        int pcV1 = rd16(data, 6);
        boolean compressedV1 = (data[12] != (byte) 0xff) && ((data[12] & 0x20) != 0);
        if (pcV1 != 0) {
            out.pc = pcV1;
            out.is128 = false;
            if (!compressedV1) {
                if (data.length - 30 < 0xc000) {
                    SpectrumFiles.fail(error, "Z80 v1 uncompressed RAM short");
                    return false;
                }
                System.arraycopy(data, 30, out.ram48, 0, 0xc000);
            } else if (!decompress(data, 30, data.length - 30, out.ram48, 0, 0xc000, true, error)) {
                return false;
            }
            System.arraycopy(out.ram48, 0, out.banks[5], 0, 0x4000);
            System.arraycopy(out.ram48, 0x4000, out.banks[2], 0, 0x4000);
            System.arraycopy(out.ram48, 0x8000, out.banks[0], 0, 0x4000);
            return true;
        }
        if (data.length < 32) {
            SpectrumFiles.fail(error, "Z80 v2/v3 truncated header");
            return false;
        }
        int extLen = rd16(data, 30);
        if (data.length < 32 + extLen) {
            SpectrumFiles.fail(error, "Z80 extended header truncated");
            return false;
        }
        out.pc = rd16(data, 32);
        int hw = u8(data[34]);
        boolean v3 = extLen >= 54;
        out.is128 = v3 ? (hw == 4 || hw == 5 || hw == 6) : (hw == 3 || hw == 4);
        if (extLen >= 6) {
            out.port7ffd = u8(data[35]);
        }
        if (extLen >= 8) {
            out.ayUsed = (data[37] & 0x04) != 0;
        }
        if (extLen >= 9) {
            out.ayLatch = u8(data[38]);
        }
        if (extLen >= 25) {
            for (int i = 0; i < 16; i++) {
                out.ayRegs[i] = u8(data[39 + i]);
            }
        }
        if (extLen >= 55) {
            out.port1ffd = u8(data[32 + 54]);
        }
        int off = 32 + extLen;
        while (off + 3 <= data.length) {
            int blkLen = rd16(data, off);
            int page = u8(data[off + 2]);
            off += 3;
            byte[] block = new byte[0x4000];
            if (blkLen == 0xffff) {
                if (off + 0x4000 > data.length) {
                    SpectrumFiles.fail(error, "Z80 uncompressed page truncated");
                    return false;
                }
                System.arraycopy(data, off, block, 0, 0x4000);
                off += 0x4000;
            } else {
                if (off + blkLen > data.length) {
                    SpectrumFiles.fail(error, "Z80 compressed page truncated");
                    return false;
                }
                if (!decompress(data, off, blkLen, block, 0, 0x4000, false, error)) {
                    return false;
                }
                off += blkLen;
            }
            if (out.is128) {
                if (page >= 3 && page <= 10) {
                    System.arraycopy(block, 0, out.banks[page - 3], 0, 0x4000);
                }
            } else {
                map48kPage(out, page, block);
            }
        }
        if (!out.is128) {
            System.arraycopy(out.ram48, 0, out.banks[5], 0, 0x4000);
            System.arraycopy(out.ram48, 0x4000, out.banks[2], 0, 0x4000);
            System.arraycopy(out.ram48, 0x8000, out.banks[0], 0, 0x4000);
        } else {
            System.arraycopy(out.banks[5], 0, out.ram48, 0, 0x4000);
            System.arraycopy(out.banks[2], 0, out.ram48, 0x4000, 0x4000);
            System.arraycopy(out.banks[0], 0, out.ram48, 0x8000, 0x4000);
        }
        return true;
    }

    public static boolean fromBytes(byte[] data, String extHint, SpectrumSnap out, StringBuilder error) {
        String ext = extHint == null ? "" : extHint.toLowerCase();
        if (ext.contains("sna")) {
            return fromSna(data, out, error);
        }
        if (ext.contains("z80")) {
            return fromZ80(data, out, error);
        }
        if (data.length == 49179 || data.length == 131103 || data.length == 147487) {
            if (fromSna(data, out, error)) {
                return true;
            }
        }
        return fromZ80(data, out, error);
    }

    public void applyCpu(dsp.cpu.Z80 cpu) {
        cpu.a = a;
        cpu.f = f;
        cpu.b = b;
        cpu.c = c;
        cpu.d = d;
        cpu.e = e;
        cpu.h = h;
        cpu.l = l;
        cpu.a2 = a2;
        cpu.f2 = f2;
        cpu.b2 = b2;
        cpu.c2 = c2;
        cpu.d2 = d2;
        cpu.e2 = e2;
        cpu.h2 = h2;
        cpu.l2 = l2;
        cpu.ix = ix;
        cpu.iy = iy;
        cpu.sp = sp;
        cpu.i = i;
        cpu.r = r;
        cpu.im = im;
        cpu.iff1 = iff1;
        cpu.iff2 = iff2;
        cpu.setPc(pc);
        cpu.halted = false;
        cpu.setIrq(dsp.cpu.IrqLine.CLEAR);
    }

    private void clear() {
        a = f = b = c = d = e = h = l = 0;
        a2 = f2 = b2 = c2 = d2 = e2 = h2 = l2 = 0;
        ix = iy = sp = pc = 0;
        i = r = im = 0;
        iff1 = iff2 = false;
        border = 7;
        port7ffd = port1ffd = ayLatch = 0;
        trdosPaged = ayUsed = is128 = false;
        java.util.Arrays.fill(ayRegs, 0);
        java.util.Arrays.fill(ram48, (byte) 0);
        for (byte[] bank : banks) {
            java.util.Arrays.fill(bank, (byte) 0);
        }
    }

    private static void applyRegsFromZ80Header(SpectrumSnap out, byte[] h) {
        out.a = u8(h[0]);
        out.f = u8(h[1]);
        out.c = u8(h[2]);
        out.b = u8(h[3]);
        out.l = u8(h[4]);
        out.h = u8(h[5]);
        out.sp = rd16(h, 8);
        out.i = u8(h[10]);
        out.r = (h[11] & 0x7f) | ((h[12] & 1) << 7);
        int flags = u8(h[12]);
        if (flags == 0xff) {
            flags = 1;
        }
        out.border = (flags >> 1) & 7;
        out.e = u8(h[13]);
        out.d = u8(h[14]);
        out.c2 = u8(h[15]);
        out.b2 = u8(h[16]);
        out.e2 = u8(h[17]);
        out.d2 = u8(h[18]);
        out.l2 = u8(h[19]);
        out.h2 = u8(h[20]);
        out.a2 = u8(h[21]);
        out.f2 = u8(h[22]);
        out.iy = rd16(h, 23);
        out.ix = rd16(h, 25);
        out.iff1 = h[27] != 0;
        out.iff2 = h[28] != 0;
        out.im = h[29] & 3;
    }

    private static boolean decompress(byte[] src, int srcOff, int srcLen, byte[] dst, int dstOff,
                                      int dstLen, boolean expectEnd, StringBuilder error) {
        int si = 0;
        int di = 0;
        while (si < srcLen && di < dstLen) {
            if (expectEnd && si + 4 <= srcLen && u8(src[srcOff + si]) == 0x00
                    && u8(src[srcOff + si + 1]) == 0xed && u8(src[srcOff + si + 2]) == 0xed
                    && u8(src[srcOff + si + 3]) == 0x00) {
                break;
            }
            if (si + 4 <= srcLen && u8(src[srcOff + si]) == 0xed && u8(src[srcOff + si + 1]) == 0xed) {
                int count = u8(src[srcOff + si + 2]);
                byte value = src[srcOff + si + 3];
                si += 4;
                for (int n = 0; n < count && di < dstLen; n++) {
                    dst[dstOff + di++] = value;
                }
                continue;
            }
            dst[dstOff + di++] = src[srcOff + si++];
        }
        if (di != dstLen) {
            SpectrumFiles.fail(error, "Z80 block decompressed to wrong size");
            return false;
        }
        return true;
    }

    private static void map48kPage(SpectrumSnap out, int page, byte[] block) {
        int base;
        switch (page) {
            case 8:
                base = 0x4000;
                break;
            case 4:
                base = 0x8000;
                break;
            case 5:
                base = 0xc000;
                break;
            default:
                return;
        }
        System.arraycopy(block, 0, out.ram48, base - 0x4000, 0x4000);
        int bank = base == 0x4000 ? 5 : (base == 0x8000 ? 2 : 0);
        System.arraycopy(block, 0, out.banks[bank], 0, 0x4000);
    }

    static int u8(byte value) {
        return value & 0xff;
    }

    static int rd16(byte[] data, int off) {
        return u8(data[off]) | (u8(data[off + 1]) << 8);
    }
}
