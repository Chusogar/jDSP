package dsp.drivers.computers;

import dsp.core.Key;
import dsp.core.Machine;
import dsp.core.MachineInputs;
import dsp.core.RomLoader;
import dsp.cpu.IrqLine;
import dsp.cpu.Z80;
import dsp.machine.Beta128;
import dsp.machine.SpectrumFiles;
import dsp.machine.SpectrumHw;
import dsp.machine.SpectrumKeyboard;
import dsp.machine.SpectrumRzx;
import dsp.machine.SpectrumSnap;
import dsp.machine.TapeTzx;
import dsp.sound.AY8910;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pentagon 1024 / Scorpion ZS-256 clones. Ported from dsp-cpp zx_clone.cpp.
 */
public class ZxClone extends Machine {
    public enum Model { PENTAGON_1024, SCORPION_256 }

    public static final int SCREEN_WIDTH = SpectrumHw.SCREEN_WIDTH;
    public static final int SCREEN_HEIGHT = SpectrumHw.SCREEN_HEIGHT;
    public static final int CLOCK = 3_500_000;
    public static final int AY_CLOCK = 1_750_000;
    public static final int TSTATES_PER_LINE = 224;
    public static final int LINES_PER_FRAME = 320;
    public static final int TSTATES_PER_FRAME = TSTATES_PER_LINE * LINES_PER_FRAME;
    public static final double FPS = (double) CLOCK / (double) TSTATES_PER_FRAME;
    public static final int SAMPLE_RATE = AY8910.SAMPLE_RATE;
    public static final int MAX_RAM_PAGES = 64;

    private final Model model;
    private final Z80 cpu = new Z80(CLOCK);
    private final AY8910 ay = new AY8910(AY_CLOCK);
    private final TapeTzx tape = new TapeTzx();
    private final SpectrumRzx rzx = new SpectrumRzx();
    private final Beta128 beta = new Beta128();
    private final SpectrumKeyboard keyboard = new SpectrumKeyboard();
    private final byte[][] ram = new byte[MAX_RAM_PAGES][0x4000];
    private final byte[][] rom = new byte[4][0x4000];
    private final int ramPages;
    private int ram3;
    private int romPage;
    private boolean page0Ram;
    private int pantalla = 5;
    private int port7ffd;
    private int port1ffd;
    private int portDffd;
    private boolean pagingLocked;
    private boolean nmiPending;
    private boolean magicDown;
    private boolean glukPresent;
    private boolean portCompat = true;
    private final int[] framebuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];
    private final int[] palette = Arrays.copyOf(SpectrumHw.PALETTE, 16);
    private int border = 7;
    private final byte[][] borderBuf = new byte[LINES_PER_FRAME][TSTATES_PER_LINE];
    private int speaker;
    private int ear;
    private boolean flash;
    private int flashCount;
    private int line;
    private int tInLine;
    private int frameT;
    private final byte[] latchPix = new byte[32];
    private final byte[] latchAttr = new byte[32];
    private int latchMask;
    private final ArrayList<Short> audio = new ArrayList<>();
    private long audioAcc;
    private short beeperLevel;

    public ZxClone(Model model) {
        this.model = model;
        this.ramPages = model == Model.PENTAGON_1024 ? 64 : 16;
    }

    public static final class Pentagon1024 extends ZxClone {
        public Pentagon1024() {
            super(Model.PENTAGON_1024);
        }
    }

    public static final class Scorpion256 extends ZxClone {
        public Scorpion256() {
            super(Model.SCORPION_256);
        }
    }

    @Override
    public boolean init(String romPath, StringBuilder error) {
        if (!loadRoms(romPath, error)) {
            return false;
        }
        cpu.setMemoryHandlers(this::memRead, this::memWrite);
        cpu.setIoHandlers(this::ioIn, this::ioOut);
        cpu.setCycleHandler(this::onCycles);
        cpu.setInstructionHook(this::onM1);
        cpu.setM1Handler(() -> {
            if (rzx.playing()) {
                rzx.onM1();
            }
        });
        reset();
        if (endsCi(romPath, ".trd") || endsCi(romPath, ".scl")) {
            StringBuilder diskError = new StringBuilder();
            if (!beta.loadDisk(romPath, diskError)) {
                warnings.add(diskError.toString());
            }
        } else if (endsCi(romPath, ".sna") || endsCi(romPath, ".rzx") || endsCi(romPath, ".tzx")
                || endsCi(romPath, ".tap") || endsCi(romPath, ".cdt")) {
            StringBuilder mediaError = new StringBuilder();
            if (!loadMedia(romPath, mediaError)) {
                warnings.add(mediaError.toString());
            }
        }
        return true;
    }

    @Override
    public void reset() {
        for (int i = 0; i < ramPages; i++) {
            Arrays.fill(ram[i], (byte) 0);
        }
        port7ffd = port1ffd = portDffd = 0;
        pagingLocked = false;
        nmiPending = false;
        magicDown = false;
        beta.reset();
        if (model == Model.PENTAGON_1024 && glukPresent) {
            beta.enable();
        }
        updateMemory();
        cpu.reset();
        ay.reset();
        border = 7;
        speaker = ear = 0;
        keyboard.releaseAll();
        flash = false;
        flashCount = 0;
        line = tInLine = frameT = 0;
        audio.clear();
        audioAcc = 0;
        beeperLevel = 0;
        tape.stop();
        Arrays.fill(framebuffer, palette[7]);
    }

    @Override
    public void setDipSwitch(int bank, int value) {
        if (bank == 0) {
            portCompat = (value & 1) == 0;
        }
    }

    @Override
    public void setInputs(MachineInputs inputs) {
        keyboard.apply(inputs);
        boolean magic = inputs.key(Key.F5);
        if (magic && !magicDown) {
            nmiPending = true;
            if (model != Model.SCORPION_256) {
                cpu.setNmi(IrqLine.PULSE);
            }
        }
        magicDown = magic;
    }

    @Override
    public void runFrame() {
        if (rzx.playing()) {
            runRzxFrame();
            return;
        }
        beginFrame();
        cpu.setIrq(IrqLine.HOLD);
        int remaining = TSTATES_PER_FRAME;
        while (remaining > 0) {
            int ran = cpu.run(Math.min(remaining, TSTATES_PER_LINE));
            if (ran <= 0) {
                break;
            }
            remaining -= ran;
            if (remaining < TSTATES_PER_FRAME - 32) {
                cpu.setIrq(IrqLine.CLEAR);
            }
        }
        finishUndrawn();
        endFrameCounters();
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
        return model == Model.SCORPION_256 ? "Scorpion ZS-256" : "Pentagon 1024";
    }

    @Override
    public boolean usesKeyboard() {
        return true;
    }

    @Override
    public boolean loadMedia(String path, StringBuilder error) {
        if (endsCi(path, ".trd") || endsCi(path, ".scl")) {
            return beta.loadDisk(path, error);
        }
        if (endsCi(path, ".tzx") || endsCi(path, ".tap") || endsCi(path, ".cdt")) {
            if (!tape.loadFile(path, error)) {
                return false;
            }
            tape.stop();
            return true;
        }
        if (endsCi(path, ".sna") || endsCi(path, ".z80")) {
            return loadSnap(path, error);
        }
        if (endsCi(path, ".rzx")) {
            return loadRzx(path, error);
        }
        SpectrumFiles.fail(error, "unsupported media (use .trd/.scl disk, .tap/.tzx tape, .sna, or .rzx): " + path);
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

    private boolean loadRoms(String path, StringBuilder error) {
        for (byte[] page : rom) {
            Arrays.fill(page, (byte) 0);
        }
        String dir = path;
        if (endsCi(path, ".trd") || endsCi(path, ".scl") || endsCi(path, ".tap") || endsCi(path, ".tzx")
                || endsCi(path, ".cdt") || endsCi(path, ".sna") || endsCi(path, ".rzx")) {
            Path parent = Path.of(path).getParent();
            dir = parent == null ? "." : parent.toString();
        }
        List<RomLoader> sources = new ArrayList<>();
        addSource(sources, dir);
        Path dirPath = Path.of(dir);
        if (Files.isDirectory(dirPath)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath, "*.zip")) {
                for (Path zip : stream) {
                    String lower = zip.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (lower.contains("pentagon") || lower.contains("pent1024") || lower.contains("scorpio")
                            || lower.contains("scorpion") || lower.contains("spec128") || lower.contains("beta128")
                            || lower.contains("trdos") || lower.contains("zxmak")) {
                        addSource(sources, zip.toString());
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        glukPresent = false;
        boolean have128 = false;
        boolean haveDos = false;
        boolean joined64 = false;
        byte[] blob;
        if (model == Model.SCORPION_256) {
            blob = tryNamed(sources, dir, "scorpion.rom", "scorp294.rom", "scorpion.rom.bin", "scorpio.rom");
            if (blob != null && blob.length >= 0x10000) {
                copyPages(blob);
                have128 = haveDos = joined64 = true;
            }
            if (!have128) {
                byte[] p0 = tryNamed(sources, dir, "scorp0.rom");
                byte[] p1 = tryNamed(sources, dir, "scorp1.rom");
                byte[] p2 = tryNamed(sources, dir, "scorp2.rom");
                byte[] p3 = tryNamed(sources, dir, "scorp3.rom");
                if (p0 != null && p1 != null && p2 != null && p3 != null
                        && p0.length >= 0x4000 && p1.length >= 0x4000 && p2.length >= 0x4000
                        && p3.length >= 0x4000) {
                    SpectrumFiles.copyPage(rom[0], p0, 0);
                    SpectrumFiles.copyPage(rom[1], p1, 0);
                    SpectrumFiles.copyPage(rom[2], p2, 0);
                    SpectrumFiles.copyPage(rom[3], p3, 0);
                    have128 = haveDos = true;
                }
            }
        } else {
            blob = tryNamed(sources, dir, "pentagon.rom", "pentagon.rom.bin");
            if (blob != null && blob.length >= 0x10000) {
                copyPages(blob);
                have128 = haveDos = joined64 = true;
                if (contains(blob, 0x8000, 0x4000, "GLUK") || contains(blob, 0x8000, 0x4000, "RESET SERVICE")) {
                    glukPresent = true;
                }
            }
        }
        if (!have128) {
            blob = tryNamed(sources, dir, "pentagon.rom", "128.rom", "zx128.rom", "spectrum128.rom", "128p.rom");
            if (blob != null && blob.length >= 0x8000) {
                SpectrumFiles.copyPage(rom[0], blob, 0);
                SpectrumFiles.copyPage(rom[1], blob, 0x4000);
                have128 = true;
            }
        }
        if (!have128) {
            byte[] r0 = tryNamed(sources, dir, "128p-0.rom", "zx128_0.rom", "128-0.rom", "plus2-0.rom");
            byte[] r1 = tryNamed(sources, dir, "128p-1.rom", "zx128_1.rom", "128-1.rom", "plus2-1.rom");
            if (r0 != null && r1 != null && r0.length >= 0x4000 && r1.length >= 0x4000) {
                SpectrumFiles.copyPage(rom[0], r0, 0);
                SpectrumFiles.copyPage(rom[1], r1, 0);
                have128 = true;
            }
        }
        if (!have128) {
            blob = SpectrumFiles.readAll(path);
            if (blob != null && blob.length >= 0x8000) {
                SpectrumFiles.copyPage(rom[0], blob, 0);
                SpectrumFiles.copyPage(rom[1], blob, 0x4000);
                if (blob.length >= 0x10000) {
                    SpectrumFiles.copyPage(rom[2], blob, 0x8000);
                    SpectrumFiles.copyPage(rom[3], blob, 0xc000);
                    haveDos = true;
                }
                have128 = true;
            }
        }
        if (!have128) {
            SpectrumFiles.fail(error, "128K ROM (32 KB) not found in " + dir);
            return false;
        }
        if (!haveDos) {
            byte[] dos = tryNamed(sources, dir, "trdos.rom", "trd503.rom", "trd504.rom", "trd504t.rom",
                    "trd505.rom", "dos.rom", "128-3.rom", "scorp3.rom", "beta128.rom");
            if (dos != null && dos.length >= 0x4000) {
                SpectrumFiles.copyPage(rom[3], dos, 0);
                haveDos = true;
            }
        }
        if (!haveDos) {
            SpectrumFiles.fail(error, "TR-DOS ROM (16 KB) not found in " + dir);
            return false;
        }
        if (model == Model.PENTAGON_1024) {
            byte[] gluk = tryNamed(sources, dir, "gluk63r.rom", "gluk.rom", "gluk54r.rom", "gluk60r.rom",
                    "scorp2.rom", "service.rom");
            if (gluk != null && gluk.length >= 0x4000) {
                SpectrumFiles.copyPage(rom[2], gluk, 0);
                glukPresent = true;
            } else if (!joined64) {
                System.arraycopy(rom[3], 0, rom[2], 0, 0x4000);
            }
        } else if (rom[2][0] == 0 && rom[2][1] == 0) {
            byte[] svc = tryNamed(sources, dir, "scorp2.rom", "service.rom");
            if (svc != null && svc.length >= 0x4000) {
                SpectrumFiles.copyPage(rom[2], svc, 0);
            }
        }
        for (RomLoader loader : sources) {
            loader.close();
        }
        return true;
    }

    private void copyPages(byte[] blob) {
        for (int p = 0; p < 4; p++) {
            SpectrumFiles.copyPage(rom[p], blob, p * 0x4000);
        }
    }

    private void updateMemory() {
        pantalla = (port7ffd & 0x08) != 0 ? 7 : 5;
        page0Ram = false;
        if (model == Model.PENTAGON_1024) {
            ram3 = ((port7ffd & 7) | ((port7ffd & 0xc0) >> 3) | ((portDffd & 1) << 5)) & (ramPages - 1);
            int rom1 = (port7ffd >> 4) & 1;
            if (beta.active()) {
                romPage = (glukPresent && rom1 == 0) ? 2 : 3;
            } else {
                romPage = rom1;
            }
        } else {
            ram3 = ((port7ffd & 7) | ((port1ffd & 0x10) >> 1)) & (ramPages - 1);
            if ((port1ffd & 0x01) != 0 && !nmiPending) {
                page0Ram = true;
                romPage = 0;
            } else if ((port1ffd & 0x02) != 0) {
                romPage = 2;
            } else {
                int rom1 = (port7ffd >> 4) & 1;
                romPage = (((nmiPending || beta.active()) ? 1 : 0) << 1) | rom1;
            }
        }
    }

    private void onM1(int pc) {
        pc &= 0xffff;
        if (pc >= 0x3d00 && pc < 0x3e00) {
            boolean rom48 = (port7ffd & 0x10) != 0;
            if (rom48 && !page0Ram) {
                beta.enable();
            }
            updateMemory();
        } else if (pc >= 0x4000) {
            if (nmiPending && model != Model.SCORPION_256) {
                nmiPending = false;
                updateMemory();
            } else if (nmiPending) {
                beta.enable();
                updateMemory();
                nmiPending = false;
                cpu.setNmi(IrqLine.PULSE);
                return;
            }
            if (beta.active()) {
                beta.disable();
                updateMemory();
            }
        }
    }

    private int memRead(int addr) {
        addr &= 0xffff;
        if (addr < 0x4000) {
            return page0Ram ? ram[0][addr] & 0xff : rom[romPage][addr] & 0xff;
        }
        if (addr < 0x8000) {
            return ram[5][addr & 0x3fff] & 0xff;
        }
        if (addr < 0xc000) {
            return ram[2][addr & 0x3fff] & 0xff;
        }
        return ram[ram3 % ramPages][addr & 0x3fff] & 0xff;
    }

    private void memWrite(int addr, int value) {
        addr &= 0xffff;
        if (addr < 0x4000) {
            if (page0Ram) {
                ram[0][addr] = (byte) value;
            }
            return;
        }
        if (addr < 0x8000) {
            ram[5][addr & 0x3fff] = (byte) value;
            return;
        }
        if (addr < 0xc000) {
            ram[2][addr & 0x3fff] = (byte) value;
            return;
        }
        ram[ram3 % ramPages][addr & 0x3fff] = (byte) value;
    }

    private int ioIn(int port) {
        port &= 0xffff;
        if (rzx.playing()) {
            return rzx.nextIn();
        }
        if ((port & 1) == 0) {
            int keys = keyboard.ulaKeys(port);
            return (keys & 0x1f) | 0xa0 | ear | speaker;
        }
        if (beta.active()) {
            switch (port & 0xff) {
                case 0x1f:
                    return beta.statusR();
                case 0x3f:
                    return beta.trackR();
                case 0x5f:
                    return beta.sectorR();
                case 0x7f:
                    return beta.dataR();
                case 0xff:
                    return beta.stateR();
                default:
                    break;
            }
        } else if ((port & 0x21) == 0x01) {
            if (model == Model.SCORPION_256 && (port & 0xa3) == 0x03) {
                return (beta.stateR() & 0xc0) | (keyboard.joy & 0x1f);
            }
            return keyboard.joy & 0x1f;
        }
        if (model == Model.PENTAGON_1024) {
            if ((port & 0xc002) == 0xc000) {
                return ay.read();
            }
        } else if ((port & 0xe023) == 0xe021) {
            return ay.read();
        }
        return 0xff;
    }

    private void ioOut(int port, int value) {
        port &= 0xffff;
        value &= 0xff;
        if ((port & 1) == 0) {
            border = value & 7;
            speaker = (value & 0x10) != 0 ? 0x10 : 0x00;
            beeperLevel = (value & 0x10) != 0 ? (short) 4096 : (short) -4096;
        }
        if (beta.active()) {
            switch (port & 0xff) {
                case 0x1f:
                    beta.commandW(value);
                    break;
                case 0x3f:
                    beta.trackW(value);
                    break;
                case 0x5f:
                    beta.sectorW(value);
                    break;
                case 0x7f:
                    beta.dataW(value);
                    break;
                case 0xff:
                    beta.paramW(value);
                    break;
                default:
                    break;
            }
        }
        if (model == Model.PENTAGON_1024) {
            if ((port & 0x8002) == 0 && (port & 1) != 0) {
                if (!pagingLocked) {
                    port7ffd = value;
                    pagingLocked = (value & 0x20) != 0;
                    updateMemory();
                }
            }
            if ((port & 0xf002) == 0xd000) {
                portDffd = value;
                updateMemory();
            } else if ((port & 0xc002) == 0xc000) {
                ay.control(value);
            } else if ((port & 0xc002) == 0x8000) {
                ay.write(value);
            }
        } else {
            boolean is7ffd = (port & 0xc023) == 0x4021;
            boolean is1ffd = (port & 0xc023) == 0x0021;
            if (portCompat && (port & 0x8002) == 0 && (port & 1) != 0) {
                is1ffd = (port & 0xff00) == 0x1f00;
                is7ffd = !is1ffd;
            }
            if (is7ffd && !pagingLocked) {
                port7ffd = value;
                pagingLocked = (value & 0x20) != 0;
                updateMemory();
            }
            if (is1ffd) {
                port1ffd = value;
                updateMemory();
            }
            if ((port & 0xe023) == 0xe021) {
                ay.control(value);
            }
            if ((port & 0xe023) == 0xa021) {
                ay.write(value);
            }
        }
    }

    private void ulaLatchColumn(int col) {
        if (col < 0 || col >= 32 || line < 80 || line > 271) {
            return;
        }
        int y = line - 80;
        if (y < 0 || y >= 192) {
            return;
        }
        byte[] vram = ram[pantalla];
        latchPix[col] = vram[SpectrumHw.SCR_TABLE[y] + col];
        latchAttr[col] = vram[0x1800 + ((y >> 3) << 5) + col];
        latchMask |= 1 << col;
    }

    private void onCycles(int cycles) {
        for (int n = 0; n < cycles; n++) {
            if (line >= 80 && line <= 271 && tInLine >= 0 && tInLine < 128 && (tInLine & 3) == 0) {
                ulaLatchColumn(tInLine >> 2);
            }
            if (line >= 0 && line < LINES_PER_FRAME && tInLine >= 0 && tInLine < TSTATES_PER_LINE) {
                borderBuf[line][tInLine] = (byte) (border & 7);
            }
            tInLine++;
            frameT++;
            if (tInLine >= TSTATES_PER_LINE) {
                tInLine -= TSTATES_PER_LINE;
                renderLine(line);
                latchMask = 0;
                line++;
                if (line >= LINES_PER_FRAME) {
                    line = 0;
                }
            }
        }
        if (tape.isPlaying()) {
            ear = tape.advance(cycles) != 0 ? 0x40 : 0x00;
        } else if (tape.isLoaded()) {
            int pc = cpu.pc();
            if ((pc >= 0x04c2 && pc < 0x0800) || (pc >= 0x056c && pc < 0x0600)) {
                tape.play(true);
            }
        }
        audioAcc += (long) cycles * SAMPLE_RATE;
        while (audioAcc >= CLOCK) {
            audioAcc -= CLOCK;
            int mixed = ay.update() + beeperLevel;
            if (mixed < -32768) {
                mixed = -32768;
            } else if (mixed > 32767) {
                mixed = 32767;
            }
            audio.add((short) mixed);
        }
    }

    private void renderLine(int scan) {
        if (scan < 32 || scan > 311) {
            return;
        }
        int sy = scan - 32;
        if (sy < 0 || sy >= SCREEN_HEIGHT) {
            return;
        }
        int dst = sy * SCREEN_WIDTH;
        byte[] brow = borderBuf[scan % LINES_PER_FRAME];
        if (scan > 32) {
            byte[] prev = borderBuf[(scan - 1) % LINES_PER_FRAME];
            for (int f = 200; f <= 223; f++) {
                int c = palette[prev[f] & 7];
                int px = (f - 200) * 2;
                framebuffer[dst + px] = c;
                framebuffer[dst + px + 1] = c;
            }
        } else {
            Arrays.fill(framebuffer, dst, dst + 48, palette[border & 7]);
        }
        if (scan >= 311) {
            return;
        }
        for (int f = 128; f <= 151; f++) {
            int c = palette[brow[f] & 7];
            int px = 304 + (f - 128) * 2;
            framebuffer[dst + px] = c;
            framebuffer[dst + px + 1] = c;
        }
        if (scan >= 80 && scan <= 271) {
            int y = scan - 80;
            byte[] vram = ram[pantalla];
            int pixBase = SpectrumHw.SCR_TABLE[y];
            int attrRow = (y >> 3) << 5;
            for (int col = 0; col < 32; col++) {
                int attrib = ((latchMask & (1 << col)) != 0 ? latchAttr[col] : vram[0x1800 + attrRow + col]) & 0xff;
                int pixels = ((latchMask & (1 << col)) != 0 ? latchPix[col] : vram[pixBase + col]) & 0xff;
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
                int cInk = palette[ink];
                int cPaper = palette[paper];
                for (int b = 0; b < 8; b++) {
                    framebuffer[dst + 48 + col * 8 + b] = (pixels & 0x80) != 0 ? cInk : cPaper;
                    pixels = (pixels << 1) & 0xff;
                }
            }
        } else {
            for (int f = 0; f <= 127; f++) {
                int c = palette[brow[f] & 7];
                int px = 48 + f * 2;
                framebuffer[dst + px] = c;
                framebuffer[dst + px + 1] = c;
            }
        }
    }

    private void applySnap(SpectrumSnap snap) {
        snap.applyCpu(cpu);
        border = snap.border & 7;
        for (int b = 0; b < 8 && b < ramPages; b++) {
            System.arraycopy(snap.banks[b], 0, ram[b], 0, 0x4000);
        }
        port1ffd = snap.is128 ? snap.port1ffd : 0;
        portDffd = 0;
        pagingLocked = false;
        nmiPending = false;
        if (snap.is128) {
            port7ffd = snap.port7ffd;
            if (snap.trdosPaged) {
                beta.enable();
            } else {
                beta.disable();
            }
        } else {
            port7ffd = 0x10;
            beta.disable();
        }
        updateMemory();
        if (!snap.is128) {
            pantalla = 5;
            ram3 = 0;
            page0Ram = false;
            romPage = 1;
        }
        if (snap.ayUsed) {
            ay.reset();
            for (int r = 0; r < 16; r++) {
                ay.control(r);
                ay.write(snap.ayRegs[r]);
            }
            ay.control(snap.ayLatch);
        }
    }

    private boolean loadSnap(String path, StringBuilder error) {
        byte[] buf = SpectrumFiles.readAll(path);
        if (buf == null) {
            SpectrumFiles.fail(error, "cannot open SNA: " + path);
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
        line = tInLine = frameT = 0;
        latchMask = 0;
        byte col = (byte) (border & 7);
        for (byte[] row : borderBuf) {
            Arrays.fill(row, col);
        }
    }

    private void finishUndrawn() {
        if (line != 0 || tInLine != 0) {
            while (line < LINES_PER_FRAME) {
                renderLine(line);
                line++;
            }
        }
    }

    private void endFrameCounters() {
        line = tInLine = frameT = 0;
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
        finishUndrawn();
        endFrameCounters();
    }

    private static void addSource(List<RomLoader> sources, String path) {
        RomLoader loader = new RomLoader();
        if (loader.open(path, new StringBuilder())) {
            sources.add(loader);
        }
    }

    private static byte[] tryNamed(List<RomLoader> sources, String dir, String... names) {
        for (String name : names) {
            for (RomLoader loader : sources) {
                byte[] data = loader.tryRead(name);
                if (data != null && data.length > 0) {
                    return data;
                }
            }
            byte[] data = SpectrumFiles.tryNamed(dir, name);
            if (data != null && data.length > 0) {
                return data;
            }
        }
        return null;
    }

    private static boolean contains(byte[] data, int off, int n, String text) {
        byte[] needle = text.getBytes();
        if (off + n > data.length || needle.length == 0 || n < needle.length) {
            return false;
        }
        for (int i = 0; i + needle.length <= n; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[off + i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsCi(String path, String ext) {
        return SpectrumFiles.endsWith(path, ext);
    }
}
