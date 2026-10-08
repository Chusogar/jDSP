package dsp.machine;

/** Beta 128 disk interface: WD1793 plus the system latch at port $FF. */
public final class Beta128 {
    private final Wd1793 fdc = new Wd1793();
    private final TrdosDisk disk = new TrdosDisk();
    private boolean active;
    private int control = 0x3c;

    public void reset() {
        fdc.reset();
        fdc.setDisk(disk);
        active = false;
        control = 0x3c;
        fdc.setDrive(0);
        fdc.setSide(0);
    }

    public void enable() {
        active = true;
    }

    public void disable() {
        active = false;
    }

    public boolean active() {
        return active;
    }

    public boolean loadDisk(String path, StringBuilder error) {
        if (!disk.loadFile(path, error)) {
            return false;
        }
        fdc.setDisk(disk);
        return true;
    }

    public boolean diskPresent() {
        return disk.present();
    }

    public int statusR() {
        return active ? fdc.statusR() : 0xff;
    }

    public int trackR() {
        return active ? fdc.trackR() : 0xff;
    }

    public int sectorR() {
        return active ? fdc.sectorR() : 0xff;
    }

    public int dataR() {
        return active ? fdc.dataR() : 0xff;
    }

    public int stateR() {
        if (!active) {
            return 0xff;
        }
        int value = 0x3f;
        if (fdc.drq()) {
            value |= 0x40;
        }
        if (fdc.intrq()) {
            value |= 0x80;
        }
        return value;
    }

    public void commandW(int value) {
        if (active) {
            fdc.commandW(value);
        }
    }

    public void trackW(int value) {
        if (active) {
            fdc.trackW(value);
        }
    }

    public void sectorW(int value) {
        if (active) {
            fdc.sectorW(value);
        }
    }

    public void dataW(int value) {
        if (active) {
            fdc.dataW(value);
        }
    }

    public void paramW(int value) {
        if (!active) {
            return;
        }
        control = value & 0xff;
        fdc.setDrive(value & 3);
        fdc.setSide((value & 0x10) != 0 ? 0 : 1);
        if ((value & 0x04) == 0) {
            fdc.reset();
        }
    }
}
