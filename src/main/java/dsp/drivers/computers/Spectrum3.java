package dsp.drivers.computers;

import dsp.core.Machine;
import dsp.core.MachineInputs;
import dsp.cpu.IrqLine;
import dsp.cpu.Z80;
import dsp.machine.SpectrumFiles;
import dsp.machine.SpectrumHw;
import dsp.machine.SpectrumKeyboard;
import dsp.machine.Nec765Fdc;
import dsp.machine.SpectrumRzx;
import dsp.machine.SpectrumSnap;
import dsp.machine.TapeTzx;
import dsp.sound.AY8910;

import java.util.Arrays;
import java.util.List;

/** ZX Spectrum +3, ported from dsp-cpp spectrum_3.cpp. */
public final class Spectrum3 extends Machine {
    public static final int SCREEN_WIDTH = SpectrumHw.SCREEN_WIDTH;
    public static final int SCREEN_HEIGHT = SpectrumHw.SCREEN_HEIGHT;
    public static final int CLOCK = 3_546_895;
    public static final int TSTATES_PER_LINE = 228;
    public static final int LINES_PER_FRAME = 311;
    public static final int TSTATES_PER_FRAME = TSTATES_PER_LINE * LINES_PER_FRAME;
    public static final double FPS = (double) CLOCK / (double) TSTATES_PER_FRAME;
    public static final int SAMPLE_RATE = AY8910.SAMPLE_RATE;
    public static final int AY_CLOCK = 1_773_447;

    private final Z80 cpu = new Z80(CLOCK);
    private final AY8910 ay0 = new AY8910(AY_CLOCK);
    private final AY8910 ay1 = new AY8910(AY_CLOCK);
    private final TapeTzx tape = new TapeTzx();
    private final SpectrumRzx rzx = new SpectrumRzx();
    private final SpectrumKeyboard keyboard = new SpectrumKeyboard();
    private final Nec765Fdc fdc = new Nec765Fdc();
    private final byte[][] banks = new byte[12][0x4000];
    private final int[] marco = {8, 5, 2, 0};
    private int pantalla = 5;
    private int port7ffd;
    private int port1ffd;
    private boolean pagingEnabled = true;
    private boolean specialPaging;
    private int aySelect;
    private boolean if2Present;
    private boolean if2Switched;
    private long if2Delay;
    private final byte[] if2Rom = new byte[0x8000];
    private final int[] atribScr = new int[192];
    private final int[] framebuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private final int[] palette = Arrays.copyOf(SpectrumHw.PALETTE, 16);
    private final int[] paletteExt = new int[80];
    private int border = 7;
    private final byte[][] borderBuf = new byte[LINES_PER_FRAME][TSTATES_PER_LINE];
    private int speaker;
    private int ear;
    private boolean kempstonEnabled = true;
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
    private final byte[] contention = new byte[72000];
    private final byte[] latchPix = new byte[32];
    private final byte[] latchAttr = new byte[32];
    private int latchMask;
    private final java.util.ArrayList<Short> audio = new java.util.ArrayList<>();
    private long audioAcc;
    private short beeperLevel;

    public Spectrum3() {
        System.arraycopy(palette, 0, paletteExt, 0, 16);
        Arrays.fill(paletteExt, 16, 80, 0xff000000);
    }

    @Override
    public boolean init(String romPath, StringBuilder error) {
        byte[] rom = SpectrumFiles.tryNamed(romPath, "plus3.rom", "plus3-0.rom", "zx+3.rom",
                "spectrum+3.rom", "p3.rom");
        if (rom == null || rom.length < 0x10000) {
            byte[] joined = new byte[0x10000];
            String[] parts = {"plus3-0.rom", "plus3-1.rom", "plus3-2.rom", "plus3-3.rom"};
            boolean ok = true;
            for (int i = 0; i < 4; i++) {
                byte[] part = SpectrumFiles.tryNamed(romPath, parts[i]);
                if (part == null || part.length < 0x4000) {
                    ok = false;
                    break;
                }
                System.arraycopy(part, 0, joined, i * 0x4000, 0x4000);
            }
            if (ok) {
                rom = joined;
            }
        }
        if (rom == null || rom.length < 0x10000) {
            rom = SpectrumFiles.readAll(romPath);
        }
        if (rom == null || rom.length < 0x10000) {
            SpectrumFiles.fail(error, "+3 ROM (64 KB) not found in " + romPath);
            return false;
        }
        for (int i = 0; i < 4; i++) {
            System.arraycopy(rom, i * 0x4000, banks[8 + i], 0, 0x4000);
        }
        cpu.setMemoryHandlers(this::memRead, this::memWrite);
        cpu.setIoHandlers(this::ioIn, this::ioOut);
        cpu.setCycleHandler(this::onInsnCycles);
        cpu.setM1Handler(() -> {
            if (rzx.playing()) {
                rzx.onM1();
            }
        });
        buildContention();
        for (int f = 0; f < 192; f++) {
            atribScr[f] = 0x1800 + 32 * (f / 8);
        }
        reset();
        return true;
    }

    @Override
    public void reset() {
        for (int b = 0; b < 8; b++) {
            Arrays.fill(banks[b], (byte) 0);
        }
        marco[0] = 8;
        marco[1] = 5;
        marco[2] = 2;
        marco[3] = 0;
        pantalla = 5;
        port7ffd = 0;
        port1ffd = 0;
        pagingEnabled = true;
        specialPaging = false;
        fdc.reset();
        fdc.writeMotor(0);
        aySelect = 0;
        if2Switched = false;
        if2Delay = 0;
        cpu.reset();
        ay0.reset();
        ay1.reset();
        border = 7;
        for (byte[] row : borderBuf) {
            Arrays.fill(row, (byte) 7);
        }
        speaker = ear = 0;
        keyboard.releaseAll();
        kempstonEnabled = true;
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
        if (inputs.player1.left) {
            keyboard.keys[4] &= ~(1 << 4);
        }
        if (inputs.player1.right) {
            keyboard.keys[4] &= ~(1 << 3);
        }
        if (inputs.player1.down) {
            keyboard.keys[4] &= ~(1 << 2);
        }
        if (inputs.player1.up) {
            keyboard.keys[4] &= ~(1 << 1);
        }
        if (inputs.player1.button1) {
            keyboard.keys[4] &= ~(1 << 0);
        }
        if (inputs.player2.left) {
            keyboard.keys[3] &= ~(1 << 0);
        }
        if (inputs.player2.right) {
            keyboard.keys[3] &= ~(1 << 1);
        }
        if (inputs.player2.down) {
            keyboard.keys[3] &= ~(1 << 2);
        }
        if (inputs.player2.up) {
            keyboard.keys[3] &= ~(1 << 3);
        }
        if (inputs.player2.button1) {
            keyboard.keys[3] &= ~(1 << 4);
        }
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
        return "ZX Spectrum +3";
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
            tape.stop();
            return true;
        }
        if (SpectrumFiles.endsWith(path, ".sna") || SpectrumFiles.endsWith(path, ".z80")) {
            return loadSnap(path, error);
        }
        if (SpectrumFiles.endsWith(path, ".rzx")) {
            return loadRzx(path, error);
        }
        if (SpectrumFiles.endsWith(path, ".dsk") || SpectrumFiles.endsWith(path, ".edsk")) {
            return fdc.loadDisk(0, path, error);
        }
        if (SpectrumFiles.endsWith(path, ".rom") || SpectrumFiles.endsWith(path, ".bin")
                || SpectrumFiles.endsWith(path, ".if2")) {
            return loadIf2(path, error);
        }
        SpectrumFiles.fail(error, "unsupported media: " + path);
        return false;
    }

    @Override
    public void tapeTogglePlay() {
        if (tape.isLoaded()) {
            tape.play(!tape.isPlaying());
        }
    }

    @Override
    public boolean tapeLoaded() {
        return tape.isLoaded();
    }

    private void buildContention() {
        Arrays.fill(contention, (byte) 0);
        int f = 14361;
        for (int h = 0; h < 192; h++) {
            for (int i = 0; i < 128; i++) {
                if (f + i < contention.length) {
                    contention[f + i] = (byte) SpectrumHw.CMEMORY[i];
                }
            }
            f += TSTATES_PER_LINE;
        }
    }

    private void updateMemoryMap() {
        pagingEnabled = (port7ffd & 0x20) == 0;
        specialPaging = (port1ffd & 0x01) != 0;
        if (!specialPaging) {
            marco[0] = (((port7ffd >> 4) & 1) | ((port1ffd >> 1) & 2)) + 8;
            marco[1] = 5;
            marco[2] = 2;
            marco[3] = port7ffd & 7;
        } else {
            int[][] ramBank = {{0, 1, 2, 3}, {4, 5, 6, 7}, {4, 5, 6, 3}, {4, 7, 6, 3}};
            int cfg = (port1ffd >> 1) & 3;
            System.arraycopy(ramBank[cfg], 0, marco, 0, 4);
        }
    }

    private void apply7ffd(int value) {
        if (!pagingEnabled) {
            return;
        }
        value &= 0xff;
        pantalla = ((value & 8) >> 2) + 5;
        port7ffd = value;
        updateMemoryMap();
    }

    private void apply1ffd(int value) {
        port1ffd = value & 0xff;
        fdc.writeMotor((value & 0x08) != 0 ? 1 : 0);
        updateMemoryMap();
    }

    private int borderIndex() {
        return ulaplusActive && ulaplusEnabled ? 16 + (border & 7) : border & 7;
    }

    private int borderColour() {
        int idx = borderIndex();
        return idx < 16 ? palette[idx] : paletteExt[idx < 80 ? idx : 16];
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
        int slot = addr >> 14;
        if (if2Present && slot == 0) {
            int off = if2Switched ? 0x4000 + (addr & 0x3fff) : (addr & 0x3fff);
            return if2Rom[off] & 0xff;
        }
        int bank = marco[slot];
        int t = ulaTime();
        if (slot == 1 || (slot == 3 && (bank & 1) != 0)) {
            if (t >= 0 && t < contention.length) {
                int extra = contention[t] & 0xff;
                if (extra != 0) {
                    contend(extra);
                }
            }
        }
        return banks[bank][addr & 0x3fff] & 0xff;
    }

    private void memWrite(int addr, int value) {
        addr &= 0xffff;
        int slot = addr >> 14;
        int bank = marco[slot];
        if (!specialPaging && slot == 0) {
            return;
        }
        if (bank >= 8) {
            return;
        }
        int t = ulaTime();
        if (slot == 1 || (slot == 3 && (bank & 1) != 0)) {
            if (t >= 0 && t < contention.length) {
                int extra = contention[t] & 0xff;
                if (extra != 0) {
                    contend(extra);
                }
            }
        }
        banks[bank][addr & 0x3fff] = (byte) value;
    }

    private int floatingBus() {
        int cont = tInLine;
        int lin = line;
        if (cont < 0) {
            cont = 0;
        }
        if (cont >= TSTATES_PER_LINE) {
            lin += cont / TSTATES_PER_LINE;
            cont %= TSTATES_PER_LINE;
        }
        if (!(lin > 62 && lin < 255 && cont < 128)) {
            return 0xff;
        }
        int y = lin - 63;
        if (y < 0 || y >= 192) {
            return 0xff;
        }
        int col = (cont & 0xf8) >> 2;
        byte[] vram = banks[pantalla];
        int attrBase = atribScr[y];
        int pixBase = SpectrumHw.SCR_TABLE[y];
        switch (cont & 7) {
            case 1:
                return vram[(pixBase + col) & 0x3fff] & 0xff;
            case 2:
                return vram[(attrBase + col) & 0x3fff] & 0xff;
            case 3:
                return vram[(pixBase + col + 1) & 0x3fff] & 0xff;
            case 4:
                return vram[(attrBase + col + 1) & 0x3fff] & 0xff;
            default:
                return 0xff;
        }
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
        return p >= 0 && p < contention.length ? contention[p] & 0xff : 0;
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
            result = (keys & 0x1f) | 0xa0;
            result = (result & 0xbf) | ear | speaker;
        } else {
            result = floatingBus();
        }
        if (kempstonEnabled && (port & 0x21) == 0x01) {
            result = keyboard.joy & 0x1f;
        }
        if ((port & 0xf002) == 0x2000) {
            result = fdc.readStatus();
        }
        if ((port & 0xf002) == 0x3000) {
            result = fdc.readData();
        }
        if ((port & 0xc002) == 0xc000) {
            result = aySelect == 0 ? ay0.read() : ay1.read();
        }
        if (port == 0xff3b && ulaplusEnabled) {
            if (ulaplusMode == 0) {
                result = ulaplusPal[ulaplusLastReg & 63];
            } else if (ulaplusMode == 1) {
                result = ulaplusActive ? 1 : 0;
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
            speaker = (value & 0x10) != 0 ? 0x10 : 0x00;
            beeperLevel = (value & 0x10) != 0 ? (short) 4096 : (short) -4096;
        }
        switch (port & 0xf002) {
            case 0x1000:
                apply1ffd(value);
                break;
            case 0x3000:
                fdc.writeData(value);
                break;
            case 0x4000:
            case 0x5000:
            case 0x6000:
            case 0x7000:
                apply7ffd(value);
                break;
            case 0x8000:
            case 0x9000:
            case 0xa000:
            case 0xb000:
                if (aySelect == 0) {
                    ay0.write(value);
                } else {
                    ay1.write(value);
                }
                break;
            case 0xc000:
            case 0xd000:
            case 0xe000:
            case 0xf000:
                if ((value & 0x9c) == 0x9c) {
                    aySelect = (~value) & 1;
                }
                if (aySelect == 0) {
                    ay0.control(value);
                } else {
                    ay1.control(value);
                }
                break;
            default:
                break;
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
        if (col < 0 || col >= 32 || line < 63 || line > 254) {
            return;
        }
        int y = line - 63;
        if (y < 0 || y >= 192) {
            return;
        }
        byte[] vram = banks[pantalla];
        latchPix[col] = vram[SpectrumHw.SCR_TABLE[y] + col];
        latchAttr[col] = vram[0x1800 + ((y >> 3) << 5) + col];
        latchMask |= 1 << col;
    }

    private void onCycles(int cycles) {
        for (int n = 0; n < cycles; n++) {
            if (frameT >= TSTATES_PER_FRAME) {
                frameT++;
                continue;
            }
            if (line >= 63 && line <= 254 && tInLine >= 0 && tInLine < 128 && (tInLine & 3) == 0) {
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
        maybeStartTapeFromRom();
        if (tape.isPlaying()) {
            ear = tape.advance(cycles) != 0 ? 0x40 : 0x00;
        }
        if (if2Present && !if2Switched) {
            if2Delay += cycles;
            if (if2Delay > 10_500_000) {
                if2Switched = true;
            }
        }
        audioAcc += (long) cycles * SAMPLE_RATE;
        while (audioAcc >= CLOCK) {
            audioAcc -= CLOCK;
            int mixed = ay0.update() + ay1.update() + beeperLevel;
            if (mixed < -32768) {
                mixed = -32768;
            } else if (mixed > 32767) {
                mixed = 32767;
            }
            audio.add((short) mixed);
        }
    }

    private void maybeStartTapeFromRom() {
        if (!tape.isLoaded() || tape.isPlaying()) {
            return;
        }
        int pc = cpu.pc();
        if ((pc >= 0x04c2 && pc < 0x0800) || (pc >= 0x056c && pc < 0x0600)) {
            tape.play(true);
        }
    }

    private void renderLine(int scan) {
        if (scan < 14 || scan > 296 || scan == 14) {
            return;
        }
        int sy = scan - 15;
        if (sy < 0 || sy >= SCREEN_HEIGHT) {
            return;
        }
        int dst = sy * SCREEN_WIDTH;
        byte[] brow = borderBuf[scan % LINES_PER_FRAME];
        if (scan > 14) {
            byte[] prev = borderBuf[(scan - 1) % LINES_PER_FRAME];
            for (int f = 0; f < 24; f++) {
                int c = colourAt(prev[203 + f] & 0xff);
                framebuffer[dst + f * 2] = c;
                framebuffer[dst + f * 2 + 1] = c;
            }
        } else {
            Arrays.fill(framebuffer, dst, dst + 48, borderColour());
        }
        if (scan >= 296) {
            return;
        }
        for (int f = 0; f < 24; f++) {
            int c = colourAt(brow[128 + f] & 0xff);
            framebuffer[dst + 304 + f * 2] = c;
            framebuffer[dst + 304 + f * 2 + 1] = c;
        }
        if (scan >= 63 && scan <= 254) {
            int y = scan - 63;
            if (y >= 0 && y < 192) {
                byte[] vram = banks[pantalla];
                int pixBase = SpectrumHw.SCR_TABLE[y];
                int attrRow = (y >> 3) << 5;
                boolean uplus = ulaplusActive && ulaplusEnabled;
                for (int col = 0; col < 32; col++) {
                    int attrib = ((latchMask & (1 << col)) != 0 ? latchAttr[col] : vram[0x1800 + attrRow + col]) & 0xff;
                    int pixels = ((latchMask & (1 << col)) != 0 ? latchPix[col] : vram[pixBase + col]) & 0xff;
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
                return;
            }
        }
        for (int f = 0; f < 128; f++) {
            int c = colourAt(brow[f] & 0xff);
            framebuffer[dst + 48 + f * 2] = c;
            framebuffer[dst + 48 + f * 2 + 1] = c;
        }
    }

    private void applySnap(SpectrumSnap snap) {
        snap.applyCpu(cpu);
        border = snap.border & 7;
        for (int b = 0; b < 8; b++) {
            System.arraycopy(snap.banks[b], 0, banks[b], 0, 0x4000);
        }
        apply7ffd(snap.is128 ? snap.port7ffd : 0x10);
        if (snap.is128) {
            apply1ffd(snap.port1ffd);
        }
        if (!snap.is128) {
            marco[0] = 8;
            marco[1] = 5;
            marco[2] = 2;
            marco[3] = 0;
            pantalla = 5;
        }
        if (snap.ayUsed) {
            ay0.reset();
            for (int r = 0; r < 16; r++) {
                ay0.control(r);
                ay0.write(snap.ayRegs[r]);
            }
            ay0.control(snap.ayLatch);
        }
    }

    private boolean loadSnap(String path, StringBuilder error) {
        byte[] buf = SpectrumFiles.readAll(path);
        if (buf == null) {
            SpectrumFiles.fail(error, "cannot open snapshot");
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

    private boolean loadIf2(String path, StringBuilder error) {
        byte[] buf = SpectrumFiles.readAll(path);
        if (buf == null) {
            SpectrumFiles.fail(error, "cannot open IF2 ROM: " + path);
            return false;
        }
        if (buf.length < 0x4000) {
            SpectrumFiles.fail(error, "IF2 ROM too small (need 16K or 32K)");
            return false;
        }
        Arrays.fill(if2Rom, (byte) 0xff);
        int n = Math.min(buf.length, 0x8000);
        System.arraycopy(buf, 0, if2Rom, 0, n);
        if (n < 0x8000) {
            System.arraycopy(if2Rom, 0, if2Rom, 0x4000, 0x4000);
        }
        if2Present = true;
        if2Switched = false;
        if2Delay = 0;
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
