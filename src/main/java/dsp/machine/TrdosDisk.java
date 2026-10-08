package dsp.machine;

import java.util.Arrays;

/** TR-DOS floppy image (TRD or SCL). Ported from dsp-cpp trdos_disk.cpp. */
public final class TrdosDisk {
    public static final int SECTOR_SIZE = 256;
    public static final int SECTORS_PER_TRACK = 16;

    private byte[] image = new byte[0];
    private int tracks;
    private int heads;

    public boolean loadFile(String path, StringBuilder error) {
        byte[] data = SpectrumFiles.readAll(path);
        if (data == null) {
            SpectrumFiles.fail(error, "cannot open disk image: " + path);
            return false;
        }
        return loadBytes(data, error);
    }

    public boolean loadBytes(byte[] data, StringBuilder error) {
        if (data == null || data.length < 8) {
            SpectrumFiles.fail(error, "disk image is empty");
            return false;
        }
        if (data.length >= 8 && data[0] == 'S' && data[1] == 'I' && data[2] == 'N'
                && data[3] == 'C' && data[4] == 'L' && data[5] == 'A' && data[6] == 'I'
                && data[7] == 'R') {
            return loadScl(data, error);
        }
        return loadTrd(data, error);
    }

    public boolean present() {
        return image.length > 0;
    }

    public void eject() {
        image = new byte[0];
        tracks = 0;
        heads = 0;
    }

    public byte[] sector(int track, int head, int sector) {
        int off = sectorOffset(track, head, sector);
        if (off < 0 || off + SECTOR_SIZE > image.length) {
            return null;
        }
        return Arrays.copyOfRange(image, off, off + SECTOR_SIZE);
    }

    public boolean writeSector(int track, int head, int sector, byte[] src) {
        int off = sectorOffset(track, head, sector);
        if (off < 0 || off + SECTOR_SIZE > image.length || src == null) {
            return false;
        }
        System.arraycopy(src, 0, image, off, Math.min(SECTOR_SIZE, src.length));
        return true;
    }

    private void format(int tracks, int heads) {
        this.tracks = tracks;
        this.heads = heads;
        image = new byte[tracks * heads * SECTORS_PER_TRACK * SECTOR_SIZE];
    }

    private int sectorOffset(int track, int head, int sector) {
        if (tracks <= 0 || heads <= 0) {
            return -1;
        }
        if (track < 0 || track >= tracks || head < 0 || head >= heads) {
            return -1;
        }
        if (sector < 1 || sector > SECTORS_PER_TRACK) {
            return -1;
        }
        int cyl = track * heads + head;
        return (cyl * SECTORS_PER_TRACK + (sector - 1)) * SECTOR_SIZE;
    }

    private boolean loadTrd(byte[] data, StringBuilder error) {
        int infoSector = 8 * SECTOR_SIZE;
        int typeOffset = infoSector + 0xe3;
        if (data.length <= typeOffset) {
            SpectrumFiles.fail(error, "TRD image is too small");
            return false;
        }
        int t = 0;
        int h = 0;
        switch (data[typeOffset] & 0xff) {
            case 0x16:
                t = 80;
                h = 2;
                break;
            case 0x17:
                t = 40;
                h = 2;
                break;
            case 0x18:
                t = 80;
                h = 1;
                break;
            case 0x19:
                t = 40;
                h = 1;
                break;
            default:
                break;
        }
        if (t == 0) {
            int trackBytes = SECTORS_PER_TRACK * SECTOR_SIZE;
            if (data.length > 80 * trackBytes) {
                t = 80;
                h = 2;
            } else if (data.length > 40 * trackBytes) {
                t = 40;
                h = 2;
            } else {
                t = 40;
                h = 1;
            }
        }
        format(t, h);
        System.arraycopy(data, 0, image, 0, Math.min(data.length, image.length));
        return true;
    }

    private boolean loadScl(byte[] data, StringBuilder error) {
        if (data.length < 9) {
            SpectrumFiles.fail(error, "not an SCL image");
            return false;
        }
        int nfiles = data[8] & 0xff;
        if (nfiles > 128) {
            SpectrumFiles.fail(error, "SCL catalogue is invalid");
            return false;
        }
        int header = 9 + nfiles * 14;
        if (data.length < header) {
            SpectrumFiles.fail(error, "SCL image is truncated");
            return false;
        }
        format(80, 2);
        int logTrack = 1;
        int sec0 = 0;
        int dataOff = header;
        int used = 0;
        for (int i = 0; i < nfiles; i++) {
            int hOff = 9 + i * 14;
            int nsec = data[hOff + 13] & 0xff;
            System.arraycopy(data, hOff, image, i * 16, 14);
            image[i * 16 + 14] = (byte) sec0;
            image[i * 16 + 15] = (byte) logTrack;
            for (int s = 0; s < nsec; s++) {
                int cyl = logTrack / heads;
                int head = logTrack % heads;
                int dest = sectorOffset(cyl, head, sec0 + 1);
                if (dest < 0) {
                    SpectrumFiles.fail(error, "SCL files do not fit on a DS/80 disk");
                    return false;
                }
                if (dataOff < data.length) {
                    int chunk = Math.min(SECTOR_SIZE, data.length - dataOff);
                    System.arraycopy(data, dataOff, image, dest, chunk);
                    dataOff += chunk;
                }
                used++;
                if (++sec0 >= SECTORS_PER_TRACK) {
                    sec0 = 0;
                    logTrack++;
                }
            }
        }
        int info = sectorOffset(0, 0, 9);
        if (info < 0) {
            return false;
        }
        image[info + 0xe1] = (byte) sec0;
        image[info + 0xe2] = (byte) logTrack;
        image[info + 0xe3] = 0x16;
        image[info + 0xe4] = (byte) nfiles;
        int free = 80 * 2 * 16 - 16 - used;
        image[info + 0xe5] = (byte) (free & 0xff);
        image[info + 0xe6] = (byte) ((free >> 8) & 0xff);
        image[info + 0xe7] = 0x10;
        image[info + 0xe8] = 0;
        Arrays.fill(image, info + 0xe9, info + 0xe9 + 10, (byte) 0x20);
        image[info + 0xf3] = 0;
        image[info + 0xf4] = 0;
        byte[] label = "SCLDISK ".getBytes();
        System.arraycopy(label, 0, image, info + 0xf5, 8);
        return true;
    }
}
