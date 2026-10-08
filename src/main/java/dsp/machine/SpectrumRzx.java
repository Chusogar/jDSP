package dsp.machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** RZX input-recording player. Ported from dsp-cpp spectrum_rzx.cpp. */
public final class SpectrumRzx {
    private static final class Frame {
        int fetchCount;
        byte[] ins = new byte[0];
        boolean repeat;
    }

    private final SpectrumSnap snap = new SpectrumSnap();
    private boolean snapOk;
    private int startTstates;
    private final List<Frame> frames = new ArrayList<>();
    private byte[] lastIns = new byte[0];
    private boolean playing;
    private boolean finished;
    private int frameIndex;
    private int fetchesLeft;
    private int inPos;
    private byte[] curIns = new byte[0];

    public boolean load(String path, StringBuilder error) {
        byte[] data = SpectrumFiles.readAll(path);
        if (data == null) {
            SpectrumFiles.fail(error, "cannot open RZX: " + path);
            return false;
        }
        return loadBytes(data, error);
    }

    public boolean loadBytes(byte[] data, StringBuilder error) {
        stop();
        frames.clear();
        snapOk = false;
        finished = false;
        return parse(data, error);
    }

    public boolean ok() {
        return !frames.isEmpty() && snapOk;
    }

    public boolean playing() {
        return playing;
    }

    public SpectrumSnap snap() {
        return snap;
    }

    public int fetchesLeft() {
        return fetchesLeft;
    }

    public void start() {
        if (!ok()) {
            return;
        }
        playing = true;
        finished = false;
        frameIndex = 0;
        fetchesLeft = 0;
        inPos = 0;
        lastIns = new byte[0];
        curIns = new byte[0];
    }

    public void stop() {
        playing = false;
        finished = false;
        fetchesLeft = 0;
        inPos = 0;
    }

    public boolean beginFrame() {
        if (!playing || finished) {
            return false;
        }
        if (frameIndex >= frames.size()) {
            playing = false;
            finished = true;
            return false;
        }
        Frame fr = frames.get(frameIndex);
        fetchesLeft = fr.fetchCount;
        if (fr.repeat) {
            curIns = lastIns;
        } else {
            curIns = fr.ins;
            lastIns = fr.ins;
        }
        inPos = 0;
        frameIndex++;
        return true;
    }

    public void onM1() {
        if (!playing || fetchesLeft == 0) {
            return;
        }
        fetchesLeft--;
    }

    public int nextIn() {
        if (!playing) {
            return 0xff;
        }
        if (inPos < curIns.length) {
            return curIns[inPos++] & 0xff;
        }
        return 0xff;
    }

    private boolean parse(byte[] data, StringBuilder error) {
        if (data == null || data.length < 10 || data[0] != 'R' || data[1] != 'Z' || data[2] != 'X'
                || data[3] != '!') {
            SpectrumFiles.fail(error, "not an RZX file");
            return false;
        }
        if ((rd32(data, 6) & 1) != 0) {
            SpectrumFiles.fail(error, "encrypted RZX not supported");
            return false;
        }
        int o = 10;
        boolean gotInput = false;
        while (o + 5 <= data.length) {
            int id = data[o] & 0xff;
            int blen = rd32(data, o + 1);
            if (blen < 5 || o + blen > data.length) {
                SpectrumFiles.fail(error, "corrupt RZX block length");
                return false;
            }
            if (id == 0x30) {
                if (blen < 17) {
                    SpectrumFiles.fail(error, "RZX snapshot block too small");
                    return false;
                }
                int flags = rd32(data, o + 5);
                if ((flags & 1) != 0) {
                    SpectrumFiles.fail(error, "RZX external snapshot not supported");
                    return false;
                }
                String ext = new String(data, o + 9, 4);
                int usl = rd32(data, o + 13);
                byte[] snapData = Arrays.copyOfRange(data, o + 17, o + blen);
                if ((flags & 2) != 0) {
                    snapData = inflate(snapData, usl, error);
                    if (snapData == null) {
                        return false;
                    }
                }
                if (!SpectrumSnap.fromBytes(snapData, ext, snap, error)) {
                    return false;
                }
                snapOk = true;
            } else if (id == 0x80) {
                if (blen < 18) {
                    SpectrumFiles.fail(error, "RZX input block too small");
                    return false;
                }
                int nframes = rd32(data, o + 5);
                startTstates = rd32(data, o + 10);
                int flags = rd32(data, o + 14);
                if ((flags & 1) != 0) {
                    SpectrumFiles.fail(error, "protected/encrypted RZX input not supported");
                    return false;
                }
                byte[] fdata = Arrays.copyOfRange(data, o + 18, o + blen);
                if ((flags & 2) != 0) {
                    fdata = inflate(fdata, 0, error);
                    if (fdata == null) {
                        return false;
                    }
                }
                int p = 0;
                frames.clear();
                for (int i = 0; i < nframes; i++) {
                    if (p + 4 > fdata.length) {
                        SpectrumFiles.fail(error, "RZX frame data truncated");
                        return false;
                    }
                    Frame fr = new Frame();
                    fr.fetchCount = SpectrumSnap.rd16(fdata, p);
                    int inCount = SpectrumSnap.rd16(fdata, p + 2);
                    p += 4;
                    if (inCount == 0xffff) {
                        fr.repeat = true;
                    } else {
                        if (p + inCount > fdata.length) {
                            SpectrumFiles.fail(error, "RZX IN bytes truncated");
                            return false;
                        }
                        fr.ins = Arrays.copyOfRange(fdata, p, p + inCount);
                        p += inCount;
                    }
                    frames.add(fr);
                }
                gotInput = true;
            }
            o += blen;
        }
        if (!snapOk) {
            SpectrumFiles.fail(error, "RZX has no embedded snapshot");
            return false;
        }
        if (!gotInput || frames.isEmpty()) {
            SpectrumFiles.fail(error, "RZX has no input frames");
            return false;
        }
        return true;
    }

    private static byte[] inflate(byte[] src, int expected, StringBuilder error) {
        Inflater inflater = new Inflater();
        inflater.setInput(src);
        byte[] out = new byte[expected > 0 ? expected : Math.max(src.length * 4, 1024)];
        try {
            int got = inflater.inflate(out);
            if (!inflater.finished() && expected == 0) {
                byte[] bigger = new byte[out.length * 4];
                System.arraycopy(out, 0, bigger, 0, got);
                out = bigger;
                got += inflater.inflate(out, got, out.length - got);
            }
            if (got == 0) {
                inflater.end();
                inflater = new Inflater(true);
                inflater.setInput(src);
                out = new byte[expected > 0 ? expected : src.length * 8];
                got = inflater.inflate(out);
            }
            return Arrays.copyOf(out, got);
        } catch (DataFormatException e) {
            SpectrumFiles.fail(error, "RZX decompress failed");
            return null;
        } finally {
            inflater.end();
        }
    }

    private static int rd32(byte[] data, int off) {
        return (data[off] & 0xff)
                | ((data[off + 1] & 0xff) << 8)
                | ((data[off + 2] & 0xff) << 16)
                | ((data[off + 3] & 0xff) << 24);
    }
}
