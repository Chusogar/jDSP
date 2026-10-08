package dsp.machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TAP / TZX tape player ported from dsp-cpp {@code machine/tape_tzx.cpp}.
 * Standard, turbo, tone, pulse, pure-data, direct-recording and pause blocks
 * cover the Spectrum loaders used by the 48K/128K/+3/clone drivers.
 */
public final class TapeTzx {
    enum BlockType {
        STANDARD, TURBO, PURE_TONE, PULSE_SEQ, PURE_DATA, DIRECT, PAUSE, GROUP_START, GROUP_END,
        JUMP, LOOP_START, LOOP_END, CALL, RET, STOP_48K, SET_LEVEL, TEXT, UNKNOWN
    }

    static final class Block {
        BlockType type = BlockType.UNKNOWN;
        int pilot = 2168;
        int sync1 = 667;
        int sync2 = 735;
        int zero = 855;
        int one = 1710;
        int pilotPulses = 8063;
        int usedBits = 8;
        int pauseMs = 1000;
        int jump;
        int loopCount;
        int signalLevel;
        int initialLevel;
        int sampleTstates;
        final List<Integer> pulses = new ArrayList<>();
        final List<Integer> callOffsets = new ArrayList<>();
        byte[] data = new byte[0];
        String text = "";
    }

    private final List<Block> blocks = new ArrayList<>();
    private boolean loaded;
    private boolean playing;
    private boolean paused;
    private int index;
    private int level;
    private int estado;
    private int estadosLeft;
    private int bitMask = 0x80;
    private int lastBitMask = 1;
    private int dataPos;
    private int pulseCount;
    private int pulseIndex;
    private int loopStart;
    private int loopRemain;
    private final List<Integer> callStack = new ArrayList<>();

    public void clear() {
        blocks.clear();
        loaded = playing = paused = false;
        index = 0;
        level = 0;
        estado = 0;
        estadosLeft = 0;
        callStack.clear();
    }

    public boolean loadFile(String path, StringBuilder error) {
        byte[] data = SpectrumFiles.readAll(path);
        if (data == null) {
            SpectrumFiles.fail(error, "cannot open tape: " + path);
            return false;
        }
        return loadMemory(data, error);
    }

    public boolean loadMemory(byte[] data, StringBuilder error) {
        clear();
        if (!parseTzx(data, error)) {
            return false;
        }
        loaded = !blocks.isEmpty();
        if (!loaded) {
            SpectrumFiles.fail(error, "no usable tape blocks");
        }
        return loaded;
    }

    public void play(boolean restart) {
        if (!loaded) {
            return;
        }
        if (restart || index >= blocks.size()) {
            index = 0;
            level = 0;
            startBlock();
        }
        playing = true;
        paused = false;
    }

    public void pause() {
        paused = true;
    }

    public void stop() {
        playing = false;
        paused = false;
        level = 0;
        estadosLeft = 0;
    }

    public boolean isPlaying() {
        return playing && !paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int blockCount() {
        return blocks.size();
    }

    public int advance(int tstates) {
        if (!playing || paused || tstates <= 0) {
            return (level & 0x40) != 0 ? 1 : 0;
        }
        while (tstates > 0 && playing) {
            if (estadosLeft > tstates) {
                estadosLeft -= tstates;
                tstates = 0;
                break;
            }
            tstates -= estadosLeft;
            estadosLeft = 0;
            if (index >= blocks.size()) {
                stop();
                break;
            }
            Block b = blocks.get(index);
            switch (estado) {
                case 0:
                    level ^= 0x40;
                    if (pulseCount > 1) {
                        pulseCount--;
                        estadosLeft = b.pilot;
                    } else {
                        estado = 1;
                        estadosLeft = b.sync1 != 0 ? b.sync1 : 1;
                    }
                    break;
                case 1:
                    level ^= 0x40;
                    estado = 2;
                    estadosLeft = b.sync2 != 0 ? b.sync2 : 1;
                    break;
                case 2:
                    level ^= 0x40;
                    estado = 3;
                    dataPos = 0;
                    bitMask = 0x80;
                    if (b.data.length == 0) {
                        estado = 5;
                        estadosLeft = pauseTstates(b.pauseMs);
                        break;
                    }
                    if (b.data.length == 1) {
                        lastBitMask = 1 << (8 - (b.usedBits != 0 ? b.usedBits : 8));
                    }
                    estadosLeft = (b.data[0] & 0x80) != 0 ? b.one : b.zero;
                    break;
                case 3:
                    level ^= 0x40;
                    estado = 4;
                    estadosLeft = (b.data[dataPos] & bitMask) != 0 ? b.one : b.zero;
                    break;
                case 4:
                    level ^= 0x40;
                    if (bitMask > lastBitMask) {
                        bitMask >>= 1;
                        if (bitMask != 0) {
                            estado = 3;
                            estadosLeft = (b.data[dataPos] & bitMask) != 0 ? b.one : b.zero;
                            break;
                        }
                    }
                    dataPos++;
                    if (dataPos < b.data.length) {
                        bitMask = 0x80;
                        lastBitMask = (dataPos + 1 == b.data.length)
                                ? (1 << (8 - (b.usedBits != 0 ? b.usedBits : 8)))
                                : 1;
                        estado = 3;
                        estadosLeft = (b.data[dataPos] & 0x80) != 0 ? b.one : b.zero;
                    } else {
                        estado = 5;
                        int p = pauseTstates(b.pauseMs);
                        estadosLeft = p != 0 ? p : 1;
                    }
                    break;
                case 5:
                    nextBlock();
                    break;
                case 11:
                    level ^= 0x40;
                    if (pulseCount > 1) {
                        pulseCount--;
                        estadosLeft = b.pilot != 0 ? b.pilot : 1;
                    } else {
                        estado = 5;
                        int p = pauseTstates(b.pauseMs);
                        estadosLeft = p != 0 ? p : 1;
                    }
                    break;
                case 10:
                    level ^= 0x40;
                    pulseIndex++;
                    if (pulseIndex < b.pulses.size()) {
                        int d = b.pulses.get(pulseIndex);
                        if (d == 0) {
                            d = 1;
                        }
                        estadosLeft = d;
                    } else {
                        nextBlock();
                    }
                    break;
                case 15:
                    if (bitMask > lastBitMask) {
                        bitMask >>= 1;
                        level = (b.data[dataPos] & bitMask) != 0 ? 0x40 : 0;
                        estadosLeft = b.sampleTstates != 0 ? b.sampleTstates : 1;
                    } else {
                        dataPos++;
                        if (dataPos < b.data.length) {
                            bitMask = 0x80;
                            lastBitMask = (dataPos + 1 == b.data.length)
                                    ? (1 << (8 - (b.usedBits != 0 ? b.usedBits : 8)))
                                    : 1;
                            level = (b.data[dataPos] & 0x80) != 0 ? 0x40 : 0;
                            estadosLeft = b.sampleTstates != 0 ? b.sampleTstates : 1;
                        } else {
                            estado = 5;
                            int p = pauseTstates(b.pauseMs);
                            estadosLeft = p != 0 ? p : 1;
                        }
                    }
                    break;
                default:
                    nextBlock();
                    break;
            }
            if (estadosLeft <= 0) {
                estadosLeft = 1;
            }
        }
        return (level & 0x40) != 0 ? 1 : 0;
    }

    private boolean parseTzx(byte[] data, StringBuilder error) {
        if (data.length < 8) {
            SpectrumFiles.fail(error, "file too small");
            return false;
        }
        boolean tzx = data.length >= 7 && data[0] == 'Z' && data[1] == 'X' && data[2] == 'T'
                && data[3] == 'a' && data[4] == 'p' && data[5] == 'e' && data[6] == '!';
        if (!tzx) {
            int pos = 0;
            while (pos + 2 <= data.length) {
                int len = SpectrumSnap.rd16(data, pos);
                pos += 2;
                if (len == 0 || pos + len > data.length) {
                    break;
                }
                Block b = standardBlock();
                b.pilotPulses = (data[pos] & 0x80) != 0 ? 3223 : 8063;
                b.data = Arrays.copyOfRange(data, pos, pos + len);
                blocks.add(b);
                pos += len;
            }
            return !blocks.isEmpty();
        }
        int pos = 10;
        while (pos < data.length) {
            int id = data[pos++] & 0xff;
            Block b = new Block();
            switch (id) {
                case 0x10: {
                    if (pos + 4 > data.length) {
                        return truncated(error);
                    }
                    b = standardBlock();
                    b.pauseMs = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    int len = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    if (pos + len > data.length) {
                        return truncated(error);
                    }
                    b.pilotPulses = (len != 0 && (data[pos] & 0x80) != 0) ? 3223 : 8063;
                    b.data = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;
                    blocks.add(b);
                    break;
                }
                case 0x11: {
                    if (pos + 18 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.TURBO;
                    b.pilot = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.sync1 = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.sync2 = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.zero = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.one = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.pilotPulses = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.usedBits = data[pos++] & 0xff;
                    b.pauseMs = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    int len = rd24(data, pos);
                    pos += 3;
                    if (pos + len > data.length) {
                        return truncated(error);
                    }
                    b.data = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;
                    blocks.add(b);
                    break;
                }
                case 0x12: {
                    if (pos + 4 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.PURE_TONE;
                    b.pilot = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.pilotPulses = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.pauseMs = 0;
                    blocks.add(b);
                    break;
                }
                case 0x13: {
                    if (pos >= data.length) {
                        return truncated(error);
                    }
                    int n = data[pos++] & 0xff;
                    if (pos + n * 2 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.PULSE_SEQ;
                    for (int i = 0; i < n; i++) {
                        b.pulses.add(SpectrumSnap.rd16(data, pos));
                        pos += 2;
                    }
                    b.pauseMs = 0;
                    blocks.add(b);
                    break;
                }
                case 0x14: {
                    if (pos + 10 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.PURE_DATA;
                    b.zero = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.one = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.usedBits = data[pos++] & 0xff;
                    b.pauseMs = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    int len = rd24(data, pos);
                    pos += 3;
                    if (pos + len > data.length) {
                        return truncated(error);
                    }
                    b.pilotPulses = 0;
                    b.data = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;
                    blocks.add(b);
                    break;
                }
                case 0x15: {
                    if (pos + 8 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.DIRECT;
                    b.sampleTstates = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.pauseMs = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    b.usedBits = data[pos++] & 0xff;
                    int len = rd24(data, pos);
                    pos += 3;
                    if (pos + len > data.length) {
                        return truncated(error);
                    }
                    b.one = b.sampleTstates;
                    b.data = Arrays.copyOfRange(data, pos, pos + len);
                    pos += len;
                    blocks.add(b);
                    break;
                }
                case 0x20: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.PAUSE;
                    b.pauseMs = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    blocks.add(b);
                    break;
                }
                case 0x21: {
                    if (pos >= data.length) {
                        return truncated(error);
                    }
                    int n = data[pos++] & 0xff;
                    if (pos + n > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.GROUP_START;
                    b.text = new String(data, pos, n);
                    pos += n;
                    blocks.add(b);
                    break;
                }
                case 0x22:
                    b.type = BlockType.GROUP_END;
                    blocks.add(b);
                    break;
                case 0x23: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.JUMP;
                    b.jump = (short) SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    blocks.add(b);
                    break;
                }
                case 0x24: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.LOOP_START;
                    b.loopCount = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    blocks.add(b);
                    break;
                }
                case 0x25:
                    b.type = BlockType.LOOP_END;
                    blocks.add(b);
                    break;
                case 0x26: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    int n = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    if (pos + n * 2 > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.CALL;
                    for (int i = 0; i < n; i++) {
                        b.callOffsets.add((int) (short) SpectrumSnap.rd16(data, pos));
                        pos += 2;
                    }
                    blocks.add(b);
                    break;
                }
                case 0x27:
                    b.type = BlockType.RET;
                    blocks.add(b);
                    break;
                case 0x2a:
                    if (pos + 4 > data.length) {
                        return truncated(error);
                    }
                    pos += 4;
                    b.type = BlockType.STOP_48K;
                    blocks.add(b);
                    break;
                case 0x2b:
                    if (pos + 5 > data.length) {
                        return truncated(error);
                    }
                    pos += 4;
                    b.type = BlockType.SET_LEVEL;
                    b.signalLevel = (data[pos++] & 1) != 0 ? 0x40 : 0;
                    blocks.add(b);
                    break;
                case 0x30: {
                    if (pos >= data.length) {
                        return truncated(error);
                    }
                    int n = data[pos++] & 0xff;
                    if (pos + n > data.length) {
                        return truncated(error);
                    }
                    b.type = BlockType.TEXT;
                    b.text = new String(data, pos, n);
                    pos += n;
                    blocks.add(b);
                    break;
                }
                case 0x31: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    pos++;
                    int n = data[pos++] & 0xff;
                    if (pos + n > data.length) {
                        return truncated(error);
                    }
                    pos += n;
                    break;
                }
                case 0x32: {
                    if (pos + 2 > data.length) {
                        return truncated(error);
                    }
                    int n = SpectrumSnap.rd16(data, pos);
                    pos += 2;
                    if (pos + n > data.length) {
                        return truncated(error);
                    }
                    pos += n;
                    break;
                }
                case 0x33: {
                    if (pos >= data.length) {
                        return truncated(error);
                    }
                    int n = data[pos++] & 0xff;
                    if (pos + n * 3 > data.length) {
                        return truncated(error);
                    }
                    pos += n * 3;
                    break;
                }
                case 0x35: {
                    if (pos + 14 > data.length) {
                        return truncated(error);
                    }
                    pos += 10;
                    int n = rd32(data, pos);
                    pos += 4;
                    if (pos + n > data.length) {
                        return truncated(error);
                    }
                    pos += n;
                    break;
                }
                case 0x5a:
                    if (pos + 9 > data.length) {
                        return truncated(error);
                    }
                    pos += 9;
                    break;
                default:
                    SpectrumFiles.fail(error, "unsupported TZX block 0x" + Integer.toHexString(id));
                    return false;
            }
        }
        return !blocks.isEmpty();
    }

    private void startBlock() {
        while (index < blocks.size()) {
            Block b = blocks.get(index);
            switch (b.type) {
                case STANDARD:
                case TURBO:
                    estado = 0;
                    estadosLeft = b.pilot;
                    pulseCount = b.pilotPulses;
                    return;
                case PURE_TONE:
                    estado = 11;
                    estadosLeft = b.pilot != 0 ? b.pilot : 1;
                    pulseCount = b.pilotPulses != 0 ? b.pilotPulses : 1;
                    return;
                case PULSE_SEQ:
                    estado = 10;
                    if (b.initialLevel != 0) {
                        level = b.initialLevel;
                    }
                    if (b.pulses.isEmpty()) {
                        index++;
                        continue;
                    }
                    estadosLeft = Math.max(1, b.pulses.get(0));
                    pulseIndex = 0;
                    return;
                case PURE_DATA:
                    estado = 3;
                    dataPos = 0;
                    if (b.data.length == 0) {
                        index++;
                        continue;
                    }
                    bitMask = 0x80;
                    lastBitMask = b.data.length == 1
                            ? (1 << (8 - (b.usedBits != 0 ? b.usedBits : 8)))
                            : 1;
                    estadosLeft = (b.data[0] & 0x80) != 0 ? b.one : b.zero;
                    return;
                case DIRECT:
                    estado = 15;
                    if (b.data.length == 0) {
                        index++;
                        continue;
                    }
                    bitMask = 0x80;
                    lastBitMask = b.data.length == 1 ? (1 << (8 - b.usedBits)) : 1;
                    level = (b.data[0] & 0x80) != 0 ? 0x40 : 0;
                    estadosLeft = b.sampleTstates != 0 ? b.sampleTstates : 1;
                    return;
                case PAUSE:
                    if (b.pauseMs == 0 && b.sampleTstates == 0) {
                        stop();
                        return;
                    }
                    estado = 5;
                    estadosLeft = b.sampleTstates != 0 ? b.sampleTstates : pauseTstates(b.pauseMs);
                    return;
                case SET_LEVEL:
                    level = b.signalLevel;
                    index++;
                    continue;
                case JUMP: {
                    int target = index + 1 + b.jump;
                    if (target >= 0 && target < blocks.size()) {
                        index = target;
                        continue;
                    }
                    index++;
                    continue;
                }
                case LOOP_START:
                    loopStart = index + 1;
                    loopRemain = b.loopCount;
                    index++;
                    continue;
                case LOOP_END:
                    if (loopRemain > 1) {
                        loopRemain--;
                        index = loopStart;
                        continue;
                    }
                    index++;
                    continue;
                case CALL:
                    if (b.callOffsets.isEmpty()) {
                        index++;
                        continue;
                    }
                    for (int i = b.callOffsets.size() - 1; i >= 0; i--) {
                        callStack.add(index + 1 + b.callOffsets.get(i));
                    }
                    index = callStack.remove(callStack.size() - 1);
                    continue;
                case RET:
                    if (callStack.isEmpty()) {
                        index++;
                        continue;
                    }
                    index = callStack.remove(callStack.size() - 1);
                    continue;
                default:
                    index++;
                    continue;
            }
        }
        stop();
    }

    private void nextBlock() {
        index++;
        if (index >= blocks.size()) {
            stop();
            return;
        }
        startBlock();
    }

    private static Block standardBlock() {
        Block b = new Block();
        b.type = BlockType.STANDARD;
        return b;
    }

    private static boolean truncated(StringBuilder error) {
        SpectrumFiles.fail(error, "truncated TZX block");
        return false;
    }

    private static int pauseTstates(int ms) {
        return ms == 0 ? 0 : ms * 3500;
    }

    private static int rd24(byte[] data, int off) {
        return (data[off] & 0xff) | ((data[off + 1] & 0xff) << 8) | ((data[off + 2] & 0xff) << 16);
    }

    private static int rd32(byte[] data, int off) {
        return rd24(data, off) | ((data[off + 3] & 0xff) << 24);
    }
}
