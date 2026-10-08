package dsp.drivers.computers;

import dsp.core.Machine;
import dsp.core.MachineInputs;
import dsp.cpu.IrqLine;
import dsp.cpu.Z80;
import dsp.machine.SpectrumFiles;
import dsp.machine.SpectrumHw;
import dsp.machine.SpectrumKeyboard;
import dsp.machine.SpectrumRzx;
import dsp.machine.SpectrumSnap;
import dsp.machine.TapeTzx;

import java.util.Arrays;
import java.util.List;

/** ZX Spectrum 48K / 16K, ported from dsp-cpp {@code drivers/computers/spectrum.cpp}. */
public final class Spectrum48k extends Machine {
    public static final int SCREEN_WIDTH = SpectrumHw.SCREEN_WIDTH;
    public static final int SCREEN_HEIGHT = SpectrumHw.SCREEN_HEIGHT;
    public static final int CLOCK = 3_500_000;
    public static final int TSTATES_PER_LINE = 224;
    public static final int LINES_PER_FRAME = 312;
    public static final int TSTATES_PER_FRAME = TSTATES_PER_LINE * LINES_PER_FRAME;
    public static final double FPS = (double) CLOCK / (double) TSTATES_PER_FRAME;
    public static final int SAMPLE_RATE = 44100;

    public enum Model { SPEC_48K, SPEC_16K }

    private final Model model;
    private final Z80 cpu = new Z80(CLOCK);
    private final TapeTzx tape = new TapeTzx();
    private final SpectrumRzx rzx = new SpectrumRzx();
    private final SpectrumKeyboard keyboard = new SpectrumKeyboard();
    private final byte[] mem = new byte[0x10000];
    private final byte[] rom = new byte[0x4000];
    private final int[] framebuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private final int[] palette = Arrays.copyOf(SpectrumHw.PALETTE, 16);
    private final int[] paletteExt = new int[80];
    private int border = 7;
    private final byte[][] borderBuf = new byte[LINES_PER_FRAME][TSTATES_PER_LINE];
    private int speaker;
    private int ear;
    private boolean flash;
    private int flashCount;
    private final boolean ulaplusEnabled = true;
    private boolean ulaplusActive;
    private int ulaplusMode;
    private int ulaplusLastReg;
    private final int[] ulaplusPal = new int[64];
    private int line;
    private int tInLine;
    private int frameT;
    private int instrTFlushed;
    private final byte[] contention = new byte[71000];
    private final byte[] latchPix = new byte[32];
    private final byte[] latchAttr = new byte[32];
    private int latchMask;
    private final java.util.ArrayList<Short> audio = new java.util.ArrayList<>();
    private long audioAcc;
    private short beeperLevel;

    public Spectrum48k() {
        this(Model.SPEC_48K);
    }

    public Spectrum48k(Model model) {
        this.model = model;
        for (int i = 0; i < 16; i++) {
            paletteExt[i] = palette[i];
        }
        Arrays.fill(paletteExt, 16, 80, 0xff000000);
    }

    @Override
    public boolean init(String romPath, StringBuilder error) {
        byte[] data = SpectrumFiles.tryNamed(romPath, "spectrum.rom", "48.rom", "48k.rom", "zx48.rom",
                "Spectrum.rom");
        if (data == null || data.length < 0x4000) {
            data = SpectrumFiles.readAll(romPath);
        }
        if (data == null || data.length < 0x4000) {
            SpectrumFiles.fail(error, "spectrum.rom (16 KB) not found in " + romPath);
            return false;
        }
        System.arraycopy(data, 0, rom, 0, 0x4000);
        cpu.setMemoryHandlers(this::memRead, this::memWrite);
        cpu.setIoHandlers(this::ioIn, this::ioOut);
        cpu.setCycleHandler(this::onInsnCycles);
        cpu.setM1Handler(() -> {
            if (rzx.playing()) {
                rzx.onM1();
            }
        });
        buildContention();
        reset();
        return true;
    }

    @Override
    public void reset() {
        Arrays.fill(mem, (byte) 0);
        System.arraycopy(rom, 0, mem, 0, 0x4000);
        cpu.reset();
        border = 7;
        for (byte[] row : borderBuf) {
            Arrays.fill(row, (byte) 7);
        }
        speaker = ear = 0;
        keyboard.releaseAll();
        flash = false;
        flashCount = 0;
        line = tInLine = frameT = instrTFlushed = 0;
        audio.clear();
        audioAcc = 0;
        beeperLevel = 0;
        tape.stop();
        ulaplusActive = false;
        ulaplusMode = 0;
        ulaplusLastReg = 0;
        Arrays.fill(ulaplusPal, 0);
        System.arraycopy(palette, 0, paletteExt, 0, 16);
        Arrays.fill(paletteExt, 16, 80, 0xff000000);
        Arrays.fill(framebuffer, borderColour());
    }

    @Override
    public void setDipSwitch(int bank, int value) {}

    @Override
    public void setInputs(MachineInputs inputs) {
        keyboard.apply(inputs);
    }

    @Override
    public void runFrame() {
        if (rzx.playing()) {
            runRzxFrame();
            return;
        }
        beginFrame();
        cpu.setIrq(IrqLine.HOLD);
        while (frameT < TSTATES_PER_FRAME) {
            int left = TSTATES_PER_FRAME - frameT;
            int ask = left > 64 ? Math.min(TSTATES_PER_LINE, left - 32) : 1;
            if (cpu.run(Math.max(1, ask)) <= 0) {
                break;
            }
            if (frameT >= 32) {
                cpu.setIrq(IrqLine.CLEAR);
            }
        }
        finishFrame();
    }

    @Override
    public int[] framebuffer() {
        return framebuffer;
    }

    @Override
    public int screenWidth() {
        return SCREEN_WIDTH;
    }

    @Override
    public int screenHeight() {
        return SCREEN_HEIGHT;
    }

    @Override
    public double framesPerSecond() {
        return FPS;
    }

    @Override
    public void drainAudio(List<Short> out) {
        out.addAll(audio);
        audio.clear();
    }

    @Override
    public int sampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public String title() {
        return model == Model.SPEC_16K ? "ZX Spectrum 16K" : "ZX Spectrum 48K";
    }

    @Override
    public boolean usesKeyboard() {
        return true;
    }

    @Override
    public boolean loadMedia(String path, StringBuilder error) {
        if (SpectrumFiles.endsWith(path, ".tzx") || SpectrumFiles.endsWith(path, ".tap")
                || SpectrumFiles.endsWith(path, ".cdt")) {
            if (!tape.loadFile(path, error)) {
                return false;
            }
            tape.play(true);
            return true;
        }
        if (SpectrumFiles.endsWith(path, ".sna") || SpectrumFiles.endsWith(path, ".z80")) {
            return loadSnap(path, error);
        }
        if (SpectrumFiles.endsWith(path, ".rzx")) {
            return loadRzx(path, error);
        }
        SpectrumFiles.fail(error, "unsupported media (use .tzx / .tap / .sna / .z80 / .rzx): " + path);
        return false;
    }

    @Override
    public void tapeTogglePlay() {
        if (!tape.isLoaded()) {
            return;
        }
        if (tape.isPlaying()) {
            tape.pause();
        } else {
            tape.play(false);
        }
    }

    @Override
    public boolean tapeLoaded() {
        return tape.isLoaded();
    }

    private void buildContention() {
        Arrays.fill(contention, (byte) 0);
        int f = 14335;
        for (int h = 0; h < 192; h++) {
            for (int i = 0; i < 128; i++) {
                if (f + i < contention.length) {
                    contention[f + i] = (byte) SpectrumHw.CMEMORY[i];
                }
            }
            f += TSTATES_PER_LINE;
        }
    }

    private int borderIndex() {
        if (ulaplusActive && ulaplusEnabled) {
            return 16 + (border & 7);
        }
        return border & 7;
    }

    private int borderColour() {
        int idx = borderIndex();
        if (idx < 16) {
            return palette[idx];
        }
        return paletteExt[idx < 80 ? idx : 16];
    }

    private int colourAt(int idx) {
        if (idx < 16) {
            return palette[idx];
        }
        if (idx < 80) {
            return paletteExt[idx];
        }
        return palette[0];
    }

    private void contend(int extra) {
        if (extra > 0) {
            onCycles(extra + 2);
        }
    }

    private int ulaTime() {
        int tin = cpu.tInInstruction();
        int delta = tin - instrTFlushed;
        if (delta > 0) {
            onCycles(delta);
            instrTFlushed += delta;
        }
        return frameT;
    }

    private void onInsnCycles(int cycles) {
        int rem = cycles - instrTFlushed;
        instrTFlushed = 0;
        if (rem > 0) {
            onCycles(rem);
        }
    }

    private int memRead(int addr) {
        addr &= 0xffff;
        if (model == Model.SPEC_16K) {
            addr &= 0x7fff;
        }
        int t = ulaTime();
        if ((addr & 0xc000) == 0x4000 && t >= 0 && t < contention.length) {
            int extra = contention[t] & 0xff;
            if (extra != 0) {
                contend(extra);
            }
        }
        return mem[addr] & 0xff;
    }

    private void memWrite(int addr, int value) {
        addr &= 0xffff;
        if (model == Model.SPEC_16K) {
            addr &= 0x7fff;
        }
        if (addr < 0x4000) {
            return;
        }
        int t = ulaTime();
        if ((addr & 0xc000) == 0x4000 && t >= 0 && t < contention.length) {
            int extra = contention[t] & 0xff;
            if (extra != 0) {
                contend(extra);
            }
        }
        mem[addr] = (byte) value;
    }

    private void applyPortContention(int port) {
        int pos = ulaTime();
        int extra = 0;
        if ((port & 0xc000) == 0x4000) {
            if ((port & 1) != 0) {
                int t = pos;
                for (int i = 0; i < 4; i++) {
                    int d = delayAt(t);
                    extra += d;
                    t += d + 1;
                }
            } else {
                int d0 = delayAt(pos);
                extra += d0;
                extra += delayAt(pos + d0 + 1);
            }
        } else if ((port & 1) == 0) {
            extra = delayAt(pos + 1);
        }
        if (extra > 0) {
            contend(extra);
        }
    }

    private int delayAt(int p) {
        if (p >= 0 && p < contention.length) {
            return contention[p] & 0xff;
        }
        return 0;
    }

    private int ioIn(int port) {
        port &= 0xffff;
        applyPortContention(port);
        if (rzx.playing()) {
            return rzx.nextIn();
        }
        int result = 0xff;
        if ((port & 1) == 0) {
            int keys = keyboard.ulaKeys(port);
            result = (keys & 0x1f) | 0xa0 | ear | speaker;
            result = (result & 0xbf) | ear | speaker;
        }
        if ((port & 0x20) == 0) {
            result = keyboard.joy;
        }
        if (port == 0xff3b && ulaplusEnabled) {
            if (ulaplusMode == 0) {
                result = ulaplusPal[ulaplusLastReg & 63];
            } else if (ulaplusMode == 1) {
                result = ulaplusActive ? 1 : 0;
            }
        }
        if ((port & 1) != 0 && line >= 64 && line <= 255) {
            int y = line - 64;
            int x = (tInLine - 24) / 4;
            if (x >= 0 && x < 32 && y >= 0 && y < 192) {
                result = mem[0x5800 + ((y >> 3) << 5) + x] & 0xff;
            }
        }
        return result & 0xff;
    }

    private void ioOut(int port, int value) {
        port &= 0xffff;
        value &= 0xff;
        applyPortContention(port);
        if ((port & 1) == 0) {
            border = value & 7;
            speaker = (value & 0x10) != 0 ? 0x40 : 0x00;
            beeperLevel = (value & 0x10) != 0 ? (short) 8192 : (short) -8192;
        }
        if (port == 0xbf3b && ulaplusEnabled) {
            ulaplusMode = value >> 6;
            if (ulaplusMode == 0) {
                ulaplusLastReg = value & 0x3f;
            }
        }
        if (port == 0xff3b && ulaplusEnabled) {
            if (ulaplusMode == 0) {
                ulaplusSetEntry(ulaplusLastReg & 63, value);
            } else if (ulaplusMode == 1) {
                ulaplusActive = (value & 1) != 0;
            }
        }
    }

    private void ulaplusSetEntry(int index, int value) {
        if (index >= 64) {
            return;
        }
        ulaplusPal[index] = value & 0xff;
        paletteExt[16 + index] = SpectrumHw.ulaplusDecode(value);
    }

    private void ulaLatchColumn(int col) {
        if (col < 0 || col >= 32 || line < 64 || line > 255) {
            return;
        }
        int y = line - 64;
        latchPix[col] = mem[0x4000 + SpectrumHw.SCR_TABLE[y] + col];
        latchAttr[col] = mem[0x5800 + ((y >> 3) << 5) + col];
        latchMask |= 1 << col;
    }

    private void onCycles(int cycles) {
        for (int n = 0; n < cycles; n++) {
            if (frameT >= TSTATES_PER_FRAME) {
                frameT++;
                continue;
            }
            if (line >= 64 && line <= 255 && tInLine >= 0 && tInLine < 128 && (tInLine & 3) == 0) {
                ulaLatchColumn(tInLine >> 2);
            }
            if (line >= 0 && line < LINES_PER_FRAME && tInLine >= 0 && tInLine < TSTATES_PER_LINE) {
                borderBuf[line][tInLine] = (byte) borderIndex();
            }
            tInLine++;
            frameT++;
            if (tInLine >= TSTATES_PER_LINE) {
                tInLine -= TSTATES_PER_LINE;
                renderLine(line);
                latchMask = 0;
                line++;
            }
        }
        if (tape.isPlaying()) {
            ear = tape.advance(cycles) != 0 ? 0x40 : 0x00;
        }
        audioAcc += (long) cycles * SAMPLE_RATE;
        while (audioAcc >= CLOCK) {
            audioAcc -= CLOCK;
            audio.add(beeperLevel);
        }
    }

    private void renderLine(int scan) {
        if (scan < 15 || scan > 296 || scan == 15) {
            return;
        }
        int sy = scan - 16;
        if (sy < 0 || sy >= SCREEN_HEIGHT) {
            return;
        }
        int dst = sy * SCREEN_WIDTH;
        byte[] brow = borderBuf[scan % LINES_PER_FRAME];
        if (scan > 15) {
            byte[] prev = borderBuf[(scan - 1) % LINES_PER_FRAME];
            for (int f = 200; f <= 223; f++) {
                int c = colourAt(prev[f] & 0xff);
                int px = (f - 200) * 2;
                framebuffer[dst + px] = c;
                framebuffer[dst + px + 1] = c;
            }
        } else {
            Arrays.fill(framebuffer, dst, dst + 48, colourAt(borderIndex()));
        }
        if (scan >= 296) {
            return;
        }
        for (int f = 128; f <= 151; f++) {
            int c = colourAt(brow[f] & 0xff);
            int px = 304 + (f - 128) * 2;
            framebuffer[dst + px] = c;
            framebuffer[dst + px + 1] = c;
        }
        if (scan >= 64 && scan <= 255) {
            int y = scan - 64;
            int pixBase = SpectrumHw.SCR_TABLE[y];
            int attrRow = (y >> 3) << 5;
            boolean uplus = ulaplusActive && ulaplusEnabled;
            for (int col = 0; col < 32; col++) {
                int attrib = ((latchMask & (1 << col)) != 0 ? latchAttr[col] : mem[0x5800 + attrRow + col]) & 0xff;
                int pixels = ((latchMask & (1 << col)) != 0 ? latchPix[col] : mem[0x4000 + pixBase + col]) & 0xff;
                int cInk;
                int cPaper;
                if (uplus) {
                    int bank = ((((attrib & 0x80) >> 6) + ((attrib & 0x40) >> 6)) << 4) + 16;
                    cInk = paletteExt[bank + (attrib & 7)];
                    cPaper = paletteExt[bank + ((attrib >> 3) & 7) + 8];
                } else {
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
                    cInk = palette[ink];
                    cPaper = palette[paper];
                }
                for (int b = 0; b < 8; b++) {
                    framebuffer[dst + 48 + col * 8 + b] = (pixels & 0x80) != 0 ? cInk : cPaper;
                    pixels = (pixels << 1) & 0xff;
                }
            }
        } else {
            for (int f = 0; f <= 127; f++) {
                int c = colourAt(brow[f] & 0xff);
                int px = 48 + f * 2;
                framebuffer[dst + px] = c;
                framebuffer[dst + px + 1] = c;
            }
        }
    }

    private void applySnap(SpectrumSnap snap) {
        snap.applyCpu(cpu);
        border = snap.border & 7;
        System.arraycopy(snap.ram48, 0, mem, 0x4000, 0xc000);
    }

    private boolean loadSnap(String path, StringBuilder error) {
        byte[] buf = SpectrumFiles.readAll(path);
        if (buf == null) {
            SpectrumFiles.fail(error, "cannot open snapshot: " + path);
            return false;
        }
        SpectrumSnap snap = new SpectrumSnap();
        if (!SpectrumSnap.fromBytes(buf, path, snap, error)) {
            return false;
        }
        applySnap(snap);
        return true;
    }

    private boolean loadRzx(String path, StringBuilder error) {
        rzx.stop();
        if (!rzx.load(path, error)) {
            return false;
        }
        applySnap(rzx.snap());
        rzx.start();
        return true;
    }

    private void beginFrame() {
        line = tInLine = frameT = instrTFlushed = 0;
        latchMask = 0;
        byte col = (byte) borderIndex();
        for (byte[] row : borderBuf) {
            Arrays.fill(row, col);
        }
    }

    private void finishFrame() {
        if (line < LINES_PER_FRAME) {
            while (line < LINES_PER_FRAME) {
                renderLine(line);
                line++;
            }
        }
        line = tInLine = frameT = instrTFlushed = 0;
        flashCount = (flashCount + 1) & 0x0f;
        if (flashCount == 0) {
            flash = !flash;
        }
    }

    private void runRzxFrame() {
        if (!rzx.beginFrame()) {
            return;
        }
        beginFrame();
        cpu.setIrq(IrqLine.HOLD);
        int safety = TSTATES_PER_FRAME * 4;
        while (rzx.fetchesLeft() > 0 && safety-- > 0) {
            if (cpu.run(1) <= 0) {
                break;
            }
            if (frameT >= 32) {
                cpu.setIrq(IrqLine.CLEAR);
            }
        }
        finishFrame();
    }
}
