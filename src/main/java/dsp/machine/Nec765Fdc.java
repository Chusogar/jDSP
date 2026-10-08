package dsp.machine;

import dsp.core.RomLoader;

import java.util.Arrays;

/** uPD765 / NEC765 FDC. Ported from dsp-cpp nec765.cpp for Spectrum +3. */
public final class Nec765Fdc {
    public static final int DRIVE_COUNT = 2;

    private static final int[] BYTES_IN_CMD = {
            1, 1, 9, 3, 2, 9, 9, 2,
            1, 9, 2, 1, 9, 6, 1, 3,
            1, 9, 1, 1, 1, 1, 1, 1,
            1, 9, 1, 1, 1, 1, 9, 1
    };

    static final class SectorInfo {
        int track, head, sector, sectorSize, status1, status2, dataLength, position;
        boolean multi;
    }

    static final class TrackInfo {
        int trackNumber, sideNumber, dataRate, recordingMode, sectorSize, numberSector, gap3, filler;
        int trackLength;
        byte[] data = new byte[0];
        final SectorInfo[] sectors = new SectorInfo[32];

        TrackInfo() {
            for (int i = 0; i < sectors.length; i++) {
                sectors[i] = new SectorInfo();
            }
        }
    }

    static final class Disk {
        boolean open;
        boolean writeProtected;
        int trackActual, sideActual, sectorActual, sectorReadTrack;
        int tracksCount, headsCount;
        final TrackInfo[][] tracks = new TrackInfo[2][84];
        int multiCounter, multiMax;

        Disk() {
            for (int s = 0; s < 2; s++) {
                for (int t = 0; t < 84; t++) {
                    tracks[s][t] = new TrackInfo();
                }
            }
        }
    }

    private final Disk[] disks = {new Disk(), new Disk()};
    private boolean floppyMotor;
    private int currentDrive;
    private boolean execCmdPhase;
    private boolean resultPhase;
    private int statusRegister = 0x80;
    private int st0, st1, st2, st3;
    private final int[] command = new int[9];
    private final int[] result = new int[7];
    private int commandPointer, resultPointer, resultCounter;
    private int dataPointer, dataLength, counter;
    private boolean seekTrackFlag, tc, tcDone;

    public Nec765Fdc() {
        reset();
    }

    public boolean loadDisk(int drive, String path, StringBuilder error) {
        byte[] data = SpectrumFiles.readAll(path);
        if (data == null) {
            SpectrumFiles.fail(error, "cannot open " + path);
            return false;
        }
        return loadDiskFromMemory(drive, data, error);
    }

    public boolean loadDiskFromMemory(int drive, byte[] data, StringBuilder error) {
        if (drive < 0 || drive >= DRIVE_COUNT) {
            SpectrumFiles.fail(error, "invalid drive number");
            return false;
        }
        Disk fresh = new Disk();
        if (!parseDsk(fresh, data, error)) {
            return false;
        }
        disks[drive] = fresh;
        return true;
    }

    public void ejectDisk(int drive) {
        if (drive >= 0 && drive < DRIVE_COUNT) {
            disks[drive] = new Disk();
        }
    }

    public boolean diskInserted(int drive) {
        return drive >= 0 && drive < DRIVE_COUNT && disks[drive].open;
    }

    public void reset() {
        floppyMotor = false;
        currentDrive = 0;
        execCmdPhase = false;
        resultPhase = false;
        statusRegister = 0x80;
        st0 = st1 = st2 = st3 = 0;
        commandPointer = resultPointer = resultCounter = 0;
        dataPointer = dataLength = counter = 0;
        seekTrackFlag = tc = tcDone = false;
        Arrays.fill(command, 0);
        Arrays.fill(result, 0);
        for (Disk disk : disks) {
            disk.trackActual = disk.sideActual = disk.sectorActual = disk.sectorReadTrack = 0;
            if (disk.multiMax == 0) {
                disk.multiMax = 1;
            }
        }
    }

    public void writeMotor(int value) {
        floppyMotor = value != 0;
    }

    public void writeData(int value) {
        if (resultPhase) {
            return;
        }
        value &= 0xff;
        if (!execCmdPhase) {
            if (commandPointer == 0) {
                command[0] = value;
                commandPointer = 1;
                statusRegister = 0x90;
            } else {
                command[commandPointer++] = value;
            }
            int expected = BYTES_IN_CMD[command[0] & 0x1f];
            if (commandPointer >= expected) {
                commandPointer = 0;
                statusRegister = 0x80;
                execWriteCommand();
            }
        } else {
            execWriteCommand();
        }
    }

    public int readStatus() {
        if (resultPhase) {
            statusRegister = 0xd0;
        } else if (execCmdPhase) {
            statusRegister = 0xf0;
        } else {
            statusRegister = 0x80;
        }
        return statusRegister;
    }

    public int readData() {
        if (execCmdPhase) {
            return execReadCommand();
        }
        if (resultPhase) {
            return getResult();
        }
        return 0xff;
    }

    public void tcW(boolean asserted) {
        if (asserted && !tc) {
            tcDone = true;
            if (execCmdPhase && !resultPhase) {
                finishTransfer(false);
            } else if (resultPhase && resultPointer == 0 && (result[0] & 0x40) != 0) {
                result[0] &= ~0x40;
                result[1] &= ~0x80;
            }
        }
        tc = asserted;
    }

    private boolean parseDsk(Disk disk, byte[] raw, StringBuilder error) {
        if (raw.length < 0x100) {
            SpectrumFiles.fail(error, "file too small to be a .dsk image");
            return false;
        }
        boolean standard;
        if (startsWith(raw, 0, "MV - CPC")) {
            standard = true;
        } else if (startsWith(raw, 0, "EXTENDED")) {
            standard = false;
        } else {
            SpectrumFiles.fail(error, "not a .dsk/.edsk image (bad signature)");
            return false;
        }
        int headerTracks = raw[0x30] & 0xff;
        int headerSides = raw[0x31] & 0xff;
        int headerTrackSize = SpectrumSnap.rd16(raw, 0x32);
        if (headerTracks == 0 || headerSides == 0 || headerSides > 2) {
            SpectrumFiles.fail(error, "unsupported disk geometry");
            return false;
        }
        disk.tracksCount = headerTracks;
        disk.headsCount = headerSides;
        int[][] trackSizeTable = new int[2][132];
        int mapIndex = 0;
        for (int t = 0; t < headerTracks; t++) {
            for (int s = 0; s < headerSides; s++) {
                if (0x34 + mapIndex < raw.length) {
                    trackSizeTable[s][t] = raw[0x34 + mapIndex] & 0xff;
                }
                mapIndex++;
            }
        }
        boolean hasMulti = false;
        int offset = 0x100;
        for (int logicalTrack = 0; logicalTrack < headerTracks; logicalTrack++) {
            for (int logicalSide = 0; logicalSide < headerSides; logicalSide++) {
                int fullTrackSize = standard ? headerTrackSize : trackSizeTable[logicalSide][logicalTrack] * 0x100;
                if (!standard && trackSizeTable[logicalSide][logicalTrack] == 0) {
                    continue;
                }
                if (fullTrackSize < 0x100 || offset + 0x100 > raw.length) {
                    break;
                }
                if (!startsWith(raw, offset, "Track-Info")) {
                    break;
                }
                int trackNum = raw[offset + 16] & 0xff;
                int sideNum = raw[offset + 17] & 0xff;
                if (sideNum >= 2 || trackNum >= 84) {
                    SpectrumFiles.fail(error, "track header out of supported geometry");
                    return false;
                }
                TrackInfo track = disk.tracks[sideNum][trackNum];
                track.trackNumber = trackNum;
                track.sideNumber = sideNum;
                track.dataRate = raw[offset + 18] & 0xff;
                track.recordingMode = raw[offset + 19] & 0xff;
                track.sectorSize = raw[offset + 20] & 0xff;
                track.numberSector = Math.min(raw[offset + 21] & 0xff, track.sectors.length);
                track.gap3 = raw[offset + 22] & 0xff;
                track.filler = raw[offset + 23] & 0xff;
                int position = 0;
                int sectorPtr = offset + 24;
                for (int i = 0; i < track.numberSector; i++) {
                    if (sectorPtr + 8 > raw.length) {
                        break;
                    }
                    SectorInfo sector = track.sectors[i];
                    sector.track = raw[sectorPtr] & 0xff;
                    sector.head = raw[sectorPtr + 1] & 0xff;
                    sector.sector = raw[sectorPtr + 2] & 0xff;
                    sector.sectorSize = raw[sectorPtr + 3] & 0xff;
                    sector.status1 = raw[sectorPtr + 4] & 0xff;
                    sector.status2 = raw[sectorPtr + 5] & 0xff;
                    int nominal = sectorNominalSize(raw[sectorPtr + 3] & 0xff);
                    if (standard) {
                        sector.dataLength = Math.min(nominal, 0xffff);
                    } else {
                        sector.dataLength = SpectrumSnap.rd16(raw, sectorPtr + 6);
                        if (sector.dataLength == 0) {
                            sector.dataLength = Math.min(nominal, 0xffff);
                        }
                    }
                    sector.position = position;
                    position += sector.dataLength;
                    if (nominal != 0 && sector.dataLength > nominal) {
                        int multiplicity = sector.dataLength / nominal;
                        if (multiplicity > 1) {
                            sector.multi = true;
                            disk.multiMax = Math.min(multiplicity, 4);
                            hasMulti = true;
                        }
                    }
                    sectorPtr += 8;
                }
                offset += 0x100;
                int dataSize = fullTrackSize - 0x100;
                int available = offset < raw.length ? raw.length - offset : 0;
                int toCopy = Math.min(dataSize, available);
                track.trackLength = toCopy;
                track.data = Arrays.copyOfRange(raw, offset, offset + toCopy);
                offset += dataSize;
            }
        }
        applyProtectionPatches(disk, hasMulti);
        disk.open = true;
        if (disk.multiMax == 0) {
            disk.multiMax = 1;
        }
        return true;
    }

    private void applyProtectionPatches(Disk disk, boolean ignored) {
        TrackInfo track0 = disk.tracks[0][0];
        if (track0.data.length == 0 || track0.sectors[0].dataLength == 0
                || track0.sectors[0].dataLength > track0.data.length) {
            return;
        }
        byte[] slice = Arrays.copyOf(track0.data, track0.sectors[0].dataLength);
        int crc = RomLoader.crc32Of(slice);
        switch (crc) {
            case 0x8c817e25:
            case 0x4b616c83:
                disk.tracks[0][40].sectors[6].sectorSize = 2;
                break;
            case 0x57a3276f:
                disk.tracks[0][39].sectors[10].sectorSize = 0;
                break;
            case 0xf05fe06e:
                disk.tracks[0][39].sectors[0].sectorSize = 2;
                break;
            default:
                break;
        }
    }

    private void getResult7() {
        Disk disk = disks[currentDrive];
        SectorInfo sector = disk.tracks[disk.sideActual][disk.trackActual].sectors[disk.sectorActual];
        result[0] = st0;
        result[1] = st1;
        result[2] = st2;
        result[3] = sector.track;
        result[4] = sector.head;
        result[5] = sector.sector;
        result[6] = sector.sectorSize;
        resultPointer = 0;
        resultCounter = 7;
        execCmdPhase = false;
        resultPhase = true;
        statusRegister = 0xd0;
        st0 = st1 = st2 = 0;
    }

    private boolean findSector() {
        Disk disk = disks[currentDrive];
        if (!disk.open || disk.sideActual >= 2 || disk.trackActual >= 84) {
            st1 |= 0x04;
            st2 |= 0x10;
            return false;
        }
        TrackInfo track = disk.tracks[disk.sideActual][disk.trackActual];
        if (track.numberSector == 0) {
            st1 |= 0x04;
            st2 |= 0x10;
            return false;
        }
        if (disk.sectorActual >= track.numberSector) {
            disk.sectorActual = 0;
        }
        int wantedC = command[2];
        int wantedH = command[3];
        int wantedR = command[4];
        int wantedN = command[5];
        for (int tries = 0; tries < track.numberSector; tries++) {
            SectorInfo sector = track.sectors[disk.sectorActual];
            if (sector.track == wantedC && sector.head == wantedH && sector.sector == wantedR
                    && sector.sectorSize == wantedN) {
                if (command[4] == command[6]) {
                    st1 |= 0x80;
                }
                st1 |= sector.status1 & 0x20;
                st2 |= sector.status2 & 0x60;
                return true;
            }
            disk.sectorActual++;
            if (disk.sectorActual >= track.numberSector) {
                disk.sectorActual = 0;
            }
        }
        st1 |= 0x04;
        st2 |= 0x10;
        return false;
    }

    private boolean shouldSkipSector() {
        Disk disk = disks[currentDrive];
        SectorInfo sector = disk.tracks[disk.sideActual][disk.trackActual].sectors[disk.sectorActual];
        if ((command[0] & 0x20) != 0) {
            if ((command[0] & 0x1f) == 0x06) {
                return (sector.status2 & 0x40) != 0;
            }
            if ((command[0] & 0x1f) == 0x0c) {
                return (sector.status2 & 0x40) == 0;
            }
        }
        return false;
    }

    private void startReadSector() {
        Disk disk = disks[currentDrive];
        if (!findSector()) {
            st0 |= 0x40;
            st1 |= 0x04;
            getResult7();
            return;
        }
        if (shouldSkipSector()) {
            if (command[4] == command[6]) {
                st1 &= 0x7f;
                getResult7();
                return;
            }
            command[4]++;
            startReadSector();
            return;
        }
        TrackInfo track = disk.tracks[disk.sideActual][disk.trackActual];
        SectorInfo sector = track.sectors[disk.sectorActual];
        int nominalLength = command[5] == 0 ? Math.min(command[8], 0x80) : sectorNominalSize(command[5]);
        int realLength = sector.dataLength == 0 ? nominalLength : sector.dataLength;
        if (sector.multi) {
            int slice = sectorNominalSize(sector.sectorSize);
            if (disk.multiMax == 0) {
                disk.multiMax = 1;
            }
            disk.multiCounter++;
            if (disk.multiCounter >= disk.multiMax) {
                disk.multiCounter = 0;
            }
            dataPointer = sector.position + disk.multiCounter * slice;
            dataLength = Math.min(nominalLength, slice);
        } else {
            dataPointer = sector.position;
            dataLength = Math.min(nominalLength, realLength);
        }
        if (dataPointer >= track.data.length) {
            dataLength = 0;
        } else if (dataPointer + dataLength > track.data.length) {
            dataLength = track.data.length - dataPointer;
        }
        counter = 0;
        execCmdPhase = true;
        resultPhase = false;
        statusRegister = 0xf0;
    }

    private void startReadTrack() {
        Disk disk = disks[currentDrive];
        if (!disk.open || disk.sideActual >= 2 || disk.trackActual >= 84) {
            st0 = 0x40;
            st1 = 0x04;
            st2 = 0;
            getResult7();
            return;
        }
        TrackInfo track = disk.tracks[disk.sideActual][disk.trackActual];
        if (track.numberSector == 0 || track.data.length == 0) {
            st0 = 0x40;
            st1 = 0x04;
            st2 = 0;
            getResult7();
            return;
        }
        if (disk.sectorReadTrack >= track.numberSector) {
            disk.sectorReadTrack = 0;
        }
        SectorInfo sector = track.sectors[disk.sectorReadTrack];
        dataPointer = sector.position;
        dataLength = sector.dataLength == 0 ? sectorNominalSize(sector.sectorSize) : sector.dataLength;
        if (dataPointer >= track.data.length) {
            dataLength = 0;
        } else if (dataPointer + dataLength > track.data.length) {
            dataLength = track.data.length - dataPointer;
        }
        counter = 0;
        execCmdPhase = true;
        resultPhase = false;
        statusRegister = 0xf0;
    }

    private void selectDrive() {
        currentDrive = command[1] & 1;
        Disk disk = disks[currentDrive];
        disk.sideActual = (command[1] & 4) >> 2;
        if (disk.headsCount != 0 && disk.sideActual >= disk.headsCount) {
            disk.sideActual = 0;
        }
        st0 = (st0 & 0xf8) | (command[1] & 1) | (command[1] & 4);
        st3 = (st3 & 0xf8) | (command[1] & 1) | (command[1] & 4);
    }

    private boolean seekTrack(int track) {
        Disk disk = disks[currentDrive];
        if (!disk.open || disk.tracksCount == 0) {
            disk.trackActual = 0;
            disk.sectorActual = 0;
            disk.sectorReadTrack = 0;
            return false;
        }
        boolean ok = track < disk.tracksCount;
        disk.trackActual = ok ? track : disk.tracksCount - 1;
        disk.sectorActual = 0;
        disk.sectorReadTrack = 0;
        return ok;
    }

    private void finishTransfer(boolean abnormal) {
        if (abnormal) {
            st0 |= 0x40;
            st1 |= 0x80;
        } else {
            st0 &= ~0x40;
            st1 &= ~0x80;
        }
        getResult7();
    }

    private void execWriteCommand() {
        switch (command[0] & 0x1f) {
            case 2:
                st0 = st1 = st2 = 0;
                tcDone = false;
                selectDrive();
                if (!disks[currentDrive].open) {
                    st0 |= 0x48;
                    getResult7();
                } else {
                    disks[currentDrive].sectorReadTrack = 0;
                    startReadTrack();
                }
                break;
            case 3:
                execCmdPhase = false;
                resultPhase = false;
                statusRegister = 0x80;
                break;
            case 4: {
                currentDrive = command[1] & 1;
                Disk drive = disks[currentDrive];
                st3 = (command[1] & 1) | (command[1] & 4);
                if (drive.open) {
                    st3 |= 0x20;
                }
                if (drive.writeProtected) {
                    st3 |= 0x40;
                }
                if (drive.trackActual == 0) {
                    st3 |= 0x10;
                }
                if (drive.headsCount > 1) {
                    st3 |= 0x08;
                }
                result[0] = st3;
                resultPointer = 0;
                resultCounter = 1;
                execCmdPhase = false;
                resultPhase = true;
                statusRegister = 0xd0;
                break;
            }
            case 5:
            case 9:
                st0 = st1 = st2 = 0;
                selectDrive();
                st0 |= 0x40;
                st1 |= disks[currentDrive].open ? 0x02 : 0x48;
                getResult7();
                break;
            case 6:
            case 12:
                st0 = st1 = st2 = 0;
                tcDone = false;
                selectDrive();
                if (!disks[currentDrive].open) {
                    st0 |= 0x48;
                    getResult7();
                } else {
                    startReadSector();
                }
                break;
            case 7:
                st0 = 0x20;
                st1 = st2 = 0;
                selectDrive();
                if (!disks[currentDrive].open) {
                    st0 |= 0x48;
                } else {
                    seekTrack(0);
                }
                seekTrackFlag = true;
                execCmdPhase = false;
                resultPhase = false;
                statusRegister = 0x80;
                break;
            case 8:
                resultPointer = 0;
                if (seekTrackFlag) {
                    st0 = (st0 & 0xf8) | 0x20 | (currentDrive & 1);
                    result[0] = st0;
                    result[1] = disks[currentDrive].trackActual;
                    resultCounter = 2;
                    seekTrackFlag = false;
                } else {
                    result[0] = 0x80;
                    resultCounter = 1;
                }
                execCmdPhase = false;
                resultPhase = true;
                statusRegister = 0xd0;
                break;
            case 10: {
                st0 = st1 = st2 = 0;
                selectDrive();
                Disk drive = disks[currentDrive];
                if (!drive.open) {
                    st0 = 0x48;
                    getResult7();
                    break;
                }
                TrackInfo track = drive.tracks[drive.sideActual][drive.trackActual];
                if (track.numberSector == 0) {
                    st0 = 0x40;
                    st1 = 0x01;
                    result[0] = st0;
                    result[1] = st1;
                    result[2] = st2;
                    result[3] = drive.trackActual;
                    result[4] = drive.sideActual;
                    result[5] = 0;
                    result[6] = 0;
                    resultPointer = 0;
                    resultCounter = 7;
                    execCmdPhase = false;
                    resultPhase = true;
                    statusRegister = 0xd0;
                    break;
                }
                if (drive.sectorActual >= track.numberSector) {
                    drive.sectorActual = 0;
                }
                getResult7();
                drive.sectorActual++;
                if (drive.sectorActual >= track.numberSector) {
                    drive.sectorActual = 0;
                }
                break;
            }
            case 15:
                selectDrive();
                st0 = 0x20;
                st1 = st2 = 0;
                if (!disks[currentDrive].open) {
                    st0 |= 0x48;
                } else {
                    seekTrack(command[2]);
                }
                seekTrackFlag = true;
                execCmdPhase = false;
                resultPhase = false;
                statusRegister = 0x80;
                break;
            default:
                resultPointer = 0;
                resultCounter = 1;
                result[0] = 0x80;
                execCmdPhase = false;
                resultPhase = true;
                seekTrackFlag = false;
                statusRegister = 0xd0;
                break;
        }
    }

    private boolean readDataShouldStop() {
        Disk disk = disks[currentDrive];
        SectorInfo sector = disk.tracks[disk.sideActual][disk.trackActual].sectors[disk.sectorActual];
        boolean stop = false;
        if ((command[0] & 0x20) == 0) {
            if ((command[0] & 0x1f) == 0x06 && (sector.status2 & 0x40) != 0) {
                st2 |= 0x40;
                stop = true;
            } else if ((command[0] & 0x1f) == 0x0c && (sector.status2 & 0x40) == 0) {
                st2 |= 0x40;
                stop = true;
            }
        }
        if ((sector.status1 & 0x20) != 0) {
            st1 |= 0x20;
            stop = true;
        }
        return stop;
    }

    private int execReadCommand() {
        Disk disk = disks[currentDrive];
        switch (command[0] & 0x1f) {
            case 2: {
                TrackInfo track = disk.tracks[disk.sideActual][disk.trackActual];
                if (track.data.length == 0 || dataLength == 0) {
                    st0 = 0x40;
                    st1 = 0x20;
                    st2 = 0x01;
                    getResult7();
                    return 0;
                }
                int value = dataPointer < track.data.length ? track.data[dataPointer] & 0xff : 0xff;
                dataPointer++;
                counter++;
                if (counter >= dataLength) {
                    if (disk.sectorReadTrack >= command[6] - 1
                            || disk.sectorReadTrack + 1 >= track.numberSector) {
                        st1 |= 0x80;
                        getResult7();
                    } else {
                        disk.sectorReadTrack++;
                        startReadTrack();
                    }
                }
                return value;
            }
            case 6:
            case 12: {
                TrackInfo track = disk.tracks[disk.sideActual][disk.trackActual];
                if (track.data.length == 0 || dataLength == 0) {
                    st0 = 0x40;
                    st1 = 0x20;
                    st2 = 0x01;
                    getResult7();
                    return 0;
                }
                SectorInfo sector = track.sectors[disk.sectorActual];
                int value = dataPointer < track.data.length ? track.data[dataPointer] & 0xff : 0xff;
                dataPointer++;
                counter++;
                if (counter >= dataLength) {
                    if (tcDone || command[4] == command[6] || readDataShouldStop()) {
                        if (sector.sectorSize > 5) {
                            st1 |= 0x20;
                        }
                        finishTransfer(!tcDone);
                    } else {
                        command[4]++;
                        disk.sectorActual++;
                        if (disk.sectorActual >= track.numberSector) {
                            disk.sectorActual = 0;
                        }
                        startReadSector();
                    }
                }
                return value;
            }
            default:
                return 0xff;
        }
    }

    private int getResult() {
        int value = result[resultPointer++];
        if (resultPointer >= resultCounter) {
            statusRegister = 0x80;
            resultPhase = false;
            resultPointer = resultCounter = 0;
            Arrays.fill(result, 0);
            Arrays.fill(command, 0);
        }
        return value;
    }

    private static int sectorNominalSize(int n) {
        if (n > 7) {
            return 0x4000;
        }
        return 1 << (n + 7);
    }

    private static boolean startsWith(byte[] data, int off, String text) {
        byte[] bytes = text.getBytes();
        if (off + bytes.length > data.length) {
            return false;
        }
        for (int i = 0; i < bytes.length; i++) {
            if (data[off + i] != bytes[i]) {
                return false;
            }
        }
        return true;
    }
}
