package dsp.machine;

import dsp.core.Key;
import dsp.core.MachineInputs;

/** Spectrum matrix + Kempston + ROM-style cursor/symbol mappings. */
public final class SpectrumKeyboard {
    public final int[] keys = new int[8];
    public int joy;

    public SpectrumKeyboard() {
        releaseAll();
    }

    public void releaseAll() {
        for (int i = 0; i < 8; i++) {
            keys[i] = 0xff;
        }
        joy = 0;
    }

    public void apply(MachineInputs in) {
        releaseAll();
        for (int row = 0; row < 8; row++) {
            for (int bit = 0; bit < 5; bit++) {
                if (in.key(SpectrumHw.MATRIX[row][bit])) {
                    keys[row] &= ~(1 << bit);
                }
            }
        }
        if (in.key(Key.LEFT_CTRL) || in.key(Key.RIGHT_SHIFT)) {
            keys[7] &= 0xfd;
        }
        capsWith(in, Key.LEFT, 3, 4);
        capsWith(in, Key.DOWN, 4, 4);
        capsWith(in, Key.UP, 4, 3);
        capsWith(in, Key.RIGHT, 4, 2);
        capsWith(in, Key.BACKSPACE, 4, 0);
        if (in.key(Key.DELETE)) {
            capsWith(in, Key.DELETE, 4, 0);
        }
        if (in.key(Key.ESCAPE)) {
            keys[0] &= 0xfe;
            keys[7] &= 0xfe;
        }
        if (in.key(Key.CAPS_LOCK)) {
            keys[0] &= 0xfe;
            keys[3] &= ~(1 << 1);
        }
        if (in.key(Key.TAB)) {
            keys[0] &= 0xfe;
            keys[7] &= 0xfd;
        }

        applyPunctuation(in);

        if (in.player1.right) {
            joy |= 0x01;
        }
        if (in.player1.left) {
            joy |= 0x02;
        }
        if (in.player1.down) {
            joy |= 0x04;
        }
        if (in.player1.up) {
            joy |= 0x08;
        }
        if (in.player1.button1) {
            joy |= 0x10;
        }
    }

    public int ulaKeys(int port) {
        int bits = 0x1f;
        if ((port & 0x8000) == 0) {
            bits &= keys[7];
        }
        if ((port & 0x4000) == 0) {
            bits &= keys[6];
        }
        if ((port & 0x2000) == 0) {
            bits &= keys[5];
        }
        if ((port & 0x1000) == 0) {
            bits &= keys[4];
        }
        if ((port & 0x0800) == 0) {
            bits &= keys[3];
        }
        if ((port & 0x0400) == 0) {
            bits &= keys[2];
        }
        if ((port & 0x0200) == 0) {
            bits &= keys[1];
        }
        if ((port & 0x0100) == 0) {
            bits &= keys[0];
        }
        return bits & 0x1f;
    }

    private void capsWith(MachineInputs in, Key key, int row, int bit) {
        if (in.key(key)) {
            keys[0] &= 0xfe;
            keys[row] &= ~(1 << bit);
        }
    }

    private void applyPunctuation(MachineInputs in) {
        boolean hostShift = in.key(Key.LEFT_SHIFT) || in.key(Key.RIGHT_SHIFT);
        boolean punct = false;
        punct |= punct(in, Key.COMMA, 7, 3, 2, 3, hostShift);
        punct |= punct(in, Key.PERIOD, 7, 2, 2, 4, hostShift);
        punct |= punct(in, Key.SEMICOLON, 5, 1, 0, 1, hostShift);
        punct |= punct(in, Key.QUOTE, 4, 3, 5, 0, hostShift);
        punct |= punct(in, Key.SLASH, 0, 4, 0, 3, hostShift);
        punct |= punct(in, Key.MINUS, 6, 3, 4, 0, hostShift);
        punct |= punct(in, Key.EQUALS, 6, 1, 6, 2, hostShift);
        punct |= punct(in, Key.PLUS, 6, 2, 6, 2, hostShift);
        punct |= punct(in, Key.ASTERISK, 6, 2, 7, 4, hostShift);
        if (punct && hostShift) {
            keys[0] |= 0x01;
        }
        if (in.key(Key.LEFT_CTRL) || in.key(Key.RIGHT_CTRL) || in.key(Key.RIGHT_ALT)
                || (in.key(Key.RIGHT_SHIFT) && !punct)) {
            keys[7] &= 0xfd;
        }
    }

    private boolean punct(MachineInputs in, Key key, int row, int bit, int shiftRow, int shiftBit,
                          boolean hostShift) {
        if (!in.key(key)) {
            return false;
        }
        keys[7] &= 0xfd;
        if (hostShift) {
            keys[shiftRow] &= ~(1 << shiftBit);
        } else {
            keys[row] &= ~(1 << bit);
        }
        return true;
    }
}
