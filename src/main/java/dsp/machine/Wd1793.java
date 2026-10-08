package dsp.machine;

import java.util.Arrays;

/** Western Digital WD1793 / KR1818VG93, enough for TR-DOS on the Beta 128. */
public final class Wd1793 {
    private static final int BUSY = 0x01;
    private static final int DRQ = 0x02;
    private static final int TRACK0 = 0x04;
    private static final int RNF = 0x10;
    private static final int WP = 0x40;
    private static final int NOT_READY = 0x80;

    private TrdosDisk disk;
    private int status;
    private int track;
    private int sector = 1;
    private int data;
    private int command;
    private int side;
    private int drive;
    private boolean drq;
    private boolean intrq;
    private boolean busy;
    private boolean type1 = true;
    private boolean writing;
    private boolean indexPulse;
    private byte[] buf = new byte[0];
    private int bufPos;

    public void reset() {
        status = 0;
        track = 0;
        sector = 1;
        data = 0;
        command = 0;
        drq = false;
        intrq = false;
        busy = false;
        type1 = true;
        writing = false;
        indexPulse = false;
        buf = new byte[0];
        bufPos = 0;
    }

    public void setDisk(TrdosDisk disk) {
        this.disk = disk;
    }

    public void setSide(int side) {
        this.side = side & 1;
    }

    public void setDrive(int drive) {
        this.drive = drive & 3;
    }

    public boolean drq() {
        return drq;
    }

    public boolean intrq() {
        return intrq;
    }

    public int trackR() {
        return track;
    }

    public int sectorR() {
        return sector;
    }

    public int statusR() {
        int value = 0;
        if (disk == null || !disk.present() || drive != 0) {
            value |= NOT_READY;
        }
        if (busy) {
            value |= BUSY;
        }
        if (type1) {
            if (track == 0) {
                value |= TRACK0;
            }
            value |= 0x20;
            indexPulse = !indexPulse;
            if (indexPulse) {
                value |= 0x02;
            }
        } else {
            if (drq) {
                value |= DRQ;
            }
            if ((status & RNF) != 0) {
                value |= RNF;
            }
            if ((status & WP) != 0) {
                value |= WP;
            }
        }
        return value;
    }

    public void commandW(int value) {
        command = value & 0xff;
        intrq = false;
        int type = command & 0xf0;
        if (type == 0xd0) {
            busy = false;
            drq = false;
            writing = false;
            type1 = true;
            if ((value & 0x08) != 0) {
                intrq = true;
            }
            return;
        }
        busy = true;
        drq = false;
        if (type <= 0x70) {
            if (type == 0x00) {
                track = 0;
            } else if (type == 0x10) {
                track = data;
            } else if (type == 0x40 || type == 0x50) {
                if (track < 255) {
                    track++;
                }
            } else if (type == 0x60 || type == 0x70) {
                if (track > 0) {
                    track--;
                }
            }
            finishType1();
            return;
        }
        if (type == 0x80 || type == 0x90) {
            startReadSector();
            return;
        }
        if (type == 0xa0 || type == 0xb0) {
            startWriteSector();
            return;
        }
        if (type == 0xc0) {
            startReadAddress();
            return;
        }
        if (type == 0xe0 || type == 0xf0) {
            completeIo(false);
            return;
        }
        completeIo(true);
    }

    public void trackW(int value) {
        track = value & 0xff;
    }

    public void sectorW(int value) {
        sector = value & 0xff;
    }

    public int dataR() {
        if (!drq || bufPos >= buf.length) {
            return data;
        }
        data = buf[bufPos++] & 0xff;
        if (bufPos >= buf.length) {
            boolean multi = (command & 0x10) != 0;
            if (multi && sector < TrdosDisk.SECTORS_PER_TRACK) {
                sector++;
                startReadSector();
            } else {
                completeIo(false);
            }
        }
        return data;
    }

    public void dataW(int value) {
        data = value & 0xff;
        if (!writing || !drq) {
            return;
        }
        if (bufPos < buf.length) {
            buf[bufPos++] = (byte) data;
        }
        if (bufPos >= buf.length) {
            disk.writeSector(track, side, sector, buf);
            completeIo(false);
        }
    }

    private byte[] currentSector() {
        if (disk == null || !disk.present() || drive != 0) {
            return null;
        }
        return disk.sector(track, side, sector);
    }

    private void finishType1() {
        type1 = true;
        busy = false;
        drq = false;
        intrq = true;
        status = 0;
    }

    private void completeIo(boolean rnf) {
        type1 = false;
        busy = false;
        drq = false;
        intrq = true;
        writing = false;
        status = rnf ? RNF : 0;
    }

    private void startReadSector() {
        type1 = false;
        writing = false;
        byte[] src = currentSector();
        if (src == null) {
            completeIo(true);
            return;
        }
        buf = src;
        bufPos = 0;
        busy = true;
        drq = true;
        intrq = false;
        status = 0;
    }

    private void startWriteSector() {
        type1 = false;
        writing = true;
        if (currentSector() == null) {
            completeIo(true);
            return;
        }
        buf = new byte[TrdosDisk.SECTOR_SIZE];
        bufPos = 0;
        busy = true;
        drq = true;
        intrq = false;
        status = 0;
    }

    private void startReadAddress() {
        type1 = false;
        writing = false;
        if (disk == null || !disk.present() || drive != 0 || disk.sector(track, side, 1) == null) {
            completeIo(true);
            return;
        }
        buf = new byte[] {(byte) track, (byte) side, 1, 1, 0, 0};
        sector = track;
        bufPos = 0;
        busy = true;
        drq = true;
        intrq = false;
        status = 0;
    }
}
