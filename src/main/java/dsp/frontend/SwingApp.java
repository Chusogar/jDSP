package dsp.frontend;

import dsp.core.Machine;
import dsp.core.MachineInputs;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Swing + Java Sound front end: window, nearest-neighbour blit, audio queue and keyboard.
 * Ported from dsp-cpp {@code frontend/sdl_app.cpp}.
 */
public final class SwingApp {
    private final AppOptions options;
    private volatile boolean running = true;
    private volatile boolean paused;
    private final HostKeyboard keyboard = new HostKeyboard();

    public SwingApp(AppOptions options) {
        this.options = options;
    }

    public int run(Machine machine) throws Exception {
        if (options.screenshot != null && !options.screenshot.isEmpty()) {
            return runHeadless(machine);
        }
        return runWindow(machine);
    }

    private int runHeadless(Machine machine) throws Exception {
        int frames = Math.max(options.frames, 1);
        int coinFrom = envInt("JDSP_COIN_FROM", -1);
        int coinUntil = envInt("JDSP_COIN_UNTIL", coinFrom < 0 ? -1 : coinFrom + 8);
        int startFrom = envInt("JDSP_START_FROM", -1);
        int startUntil = envInt("JDSP_START_UNTIL", startFrom < 0 ? -1 : startFrom + 8);
        List<Short> samples = new ArrayList<>();
        for (int frame = 0; frame < frames; frame++) {
            MachineInputs inputs = new MachineInputs();
            if (coinFrom >= 0 && frame >= coinFrom && frame < coinUntil) {
                inputs.coin1 = true;
            }
            if (startFrom >= 0 && frame >= startFrom && frame < startUntil) {
                inputs.player1.start = true;
            }
            machine.setInputs(inputs);
            machine.runFrame();
            samples.clear();
            machine.drainAudio(samples);
        }
        BmpWriter.write(Path.of(options.screenshot), machine.framebuffer(),
                machine.screenWidth(), machine.screenHeight());
        System.out.println("wrote " + options.screenshot);
        return 0;
    }

    private int runWindow(Machine machine) throws Exception {
        int scale = Math.max(1, options.scale);
        int displayW = machine.displayWidth();
        int displayH = machine.displayHeight();
        BufferedImage image = new BufferedImage(machine.screenWidth(), machine.screenHeight(),
                BufferedImage.TYPE_INT_ARGB);
        ScreenPanel panel = new ScreenPanel(image, displayW * scale, displayH * scale);

        JFrame frame = new JFrame("jDSP - " + machine.title());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.pack();
        frame.setResizable(true);
        frame.setLocationRelativeTo(null);
        if (options.fullscreen) {
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setUndecorated(true);
        }
        // The screen panel is the focus owner; a listener on the JFrame never
        // sees keys. Capture them globally for this window, and also on the
        // panel itself so clicks/arrows cannot steal input.
        frame.setFocusable(true);
        frame.setFocusTraversalKeysEnabled(false);
        panel.setFocusTraversalKeysEnabled(false);
        KeyAdapter keys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                onKeyPressed(e, machine, frame);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                keyboard.release(e.getKeyCode());
            }
        };
        frame.addKeyListener(keys);
        panel.addKeyListener(keys);
        KeyEventDispatcher dispatcher = e -> {
            if (!running || !(frame.isActive() || belongsTo(frame, e.getComponent()))) {
                return false;
            }
            int id = e.getID();
            if (id == KeyEvent.KEY_PRESSED) {
                onKeyPressed(e, machine, frame);
                return true;
            }
            if (id == KeyEvent.KEY_RELEASED) {
                keyboard.release(e.getKeyCode());
                return true;
            }
            return id == KeyEvent.KEY_TYPED;
        };
        KeyboardFocusManager focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        focusManager.addKeyEventDispatcher(dispatcher);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                running = false;
                focusManager.removeKeyEventDispatcher(dispatcher);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                running = false;
                focusManager.removeKeyEventDispatcher(dispatcher);
            }

            @Override
            public void windowActivated(WindowEvent e) {
                panel.requestFocusInWindow();
            }

            @Override
            public void windowDeactivated(WindowEvent e) {
                keyboard.clear();
            }
        });
        frame.setVisible(true);
        SwingUtilities.invokeLater(panel::requestFocusInWindow);

        SourceDataLine line = null;
        if (!options.mute) {
            AudioFormat format = new AudioFormat(machine.sampleRate(), 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (AudioSystem.isLineSupported(info)) {
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format, machine.sampleRate());
                line.start();
            }
        }
        SourceDataLine audioLine = line;

        int delayMs = Math.max(1, (int) Math.round(1000.0 / machine.framesPerSecond()));
        Timer timer = new Timer(delayMs, e -> {
            if (!running) {
                ((Timer) e.getSource()).stop();
                if (audioLine != null) {
                    audioLine.close();
                }
                return;
            }
            if (paused) {
                return;
            }
            MachineInputs inputs = collectInputs();
            machine.setInputs(inputs);
            machine.runFrame();

            int[] src = machine.framebuffer();
            int[] dst = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
            System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
            panel.repaint();

            if (audioLine != null) {
                List<Short> samples = new ArrayList<>();
                machine.drainAudio(samples);
                if (!samples.isEmpty()) {
                    ByteBuffer buffer = ByteBuffer.allocate(samples.size() * 2).order(ByteOrder.LITTLE_ENDIAN);
                    for (short sample : samples) {
                        buffer.putShort(sample);
                    }
                    byte[] bytes = buffer.array();
                    audioLine.write(bytes, 0, bytes.length);
                }
            }
        });
        timer.start();

        while (running && frame.isDisplayable()) {
            Thread.sleep(50);
        }
        timer.stop();
        focusManager.removeKeyEventDispatcher(dispatcher);
        if (audioLine != null) {
            audioLine.close();
        }
        return 0;
    }

    private void onKeyPressed(KeyEvent e, Machine machine, JFrame frame) {
        int code = e.getKeyCode();
        boolean repeat = keyboard.isDown(code);
        keyboard.press(code);
        if (repeat) {
            return;
        }
        if (code == KeyEvent.VK_ESCAPE) {
            running = false;
            frame.dispose();
        } else if (code == KeyEvent.VK_F3) {
            machine.reset();
        } else if (code == KeyEvent.VK_P || code == KeyEvent.VK_F2) {
            paused = !paused;
            updateTitle(frame, machine);
        }
    }

    private static boolean belongsTo(JFrame frame, Component component) {
        if (component == null) {
            return frame.isFocused() || frame.isActive();
        }
        return component == frame || SwingUtilities.getRoot(component) == frame;
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }

    private void updateTitle(JFrame frame, Machine machine) {
        String title = "jDSP - " + machine.title();
        if (paused) {
            title += " [PAUSED]";
        }
        frame.setTitle(title);
    }

    private MachineInputs collectInputs() {
        return keyboard.snapshot();
    }

    private static final class ScreenPanel extends JPanel {
        private final BufferedImage image;

        ScreenPanel(BufferedImage image, int width, int height) {
            this.image = image;
            setPreferredSize(new Dimension(width, height));
            setBackground(Color.BLACK);
            setFocusable(true);
            setRequestFocusEnabled(true);
            setFocusTraversalKeysEnabled(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(image, 0, 0, getWidth(), getHeight(), null);
        }
    }
}
