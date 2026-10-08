package dsp.frontend;

/**
 * F12 unlimited-speed mode, matching dsp-cpp {@code sdl_app.cpp}:
 * skip frame/audio pacing and present only every 4th frame.
 */
public final class TurboMode {
    private boolean enabled;
    private int presentCounter;

    public boolean toggle() {
        enabled = !enabled;
        presentCounter = 0;
        return enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean shouldPresent() {
        if (!enabled) {
            return true;
        }
        return (++presentCounter & 3) == 0;
    }

    public boolean shouldQueueAudio() {
        return !enabled;
    }

    public int timerDelayMs(int pacedDelayMs) {
        return enabled ? 1 : pacedDelayMs;
    }
}
