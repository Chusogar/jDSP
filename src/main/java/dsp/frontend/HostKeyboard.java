package dsp.frontend;

import dsp.core.Key;
import dsp.core.MachineInputs;

import java.awt.event.KeyEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host key state independent of which Swing component currently has focus.
 * Arcade controls match dsp-cpp {@code frontend/sdl_app.cpp}.
 */
public final class HostKeyboard {
    private final Set<Integer> keysDown = ConcurrentHashMap.newKeySet();

    public void press(int keyCode) {
        if (keyCode != KeyEvent.VK_UNDEFINED) {
            keysDown.add(keyCode);
        }
    }

    public void release(int keyCode) {
        keysDown.remove(keyCode);
    }

    public void clear() {
        keysDown.clear();
    }

    public boolean isDown(int keyCode) {
        if (keysDown.contains(keyCode)) {
            return true;
        }
        return switch (keyCode) {
            case KeyEvent.VK_1 -> keysDown.contains(KeyEvent.VK_NUMPAD1);
            case KeyEvent.VK_2 -> keysDown.contains(KeyEvent.VK_NUMPAD2);
            case KeyEvent.VK_3 -> keysDown.contains(KeyEvent.VK_NUMPAD3);
            case KeyEvent.VK_4 -> keysDown.contains(KeyEvent.VK_NUMPAD4);
            case KeyEvent.VK_5 -> keysDown.contains(KeyEvent.VK_NUMPAD5);
            case KeyEvent.VK_6 -> keysDown.contains(KeyEvent.VK_NUMPAD6);
            case KeyEvent.VK_UP -> keysDown.contains(KeyEvent.VK_KP_UP);
            case KeyEvent.VK_DOWN -> keysDown.contains(KeyEvent.VK_KP_DOWN);
            case KeyEvent.VK_LEFT -> keysDown.contains(KeyEvent.VK_KP_LEFT);
            case KeyEvent.VK_RIGHT -> keysDown.contains(KeyEvent.VK_KP_RIGHT);
            default -> false;
        };
    }

    public MachineInputs snapshot() {
        MachineInputs inputs = new MachineInputs();
        inputs.player1.up = isDown(KeyEvent.VK_UP);
        inputs.player1.down = isDown(KeyEvent.VK_DOWN);
        inputs.player1.left = isDown(KeyEvent.VK_LEFT);
        inputs.player1.right = isDown(KeyEvent.VK_RIGHT);
        inputs.player1.button1 = isDown(KeyEvent.VK_CONTROL) || isDown(KeyEvent.VK_SPACE);
        inputs.player1.button2 = isDown(KeyEvent.VK_ALT) || isDown(KeyEvent.VK_Z);
        inputs.player1.button3 = isDown(KeyEvent.VK_X);
        inputs.player1.button4 = isDown(KeyEvent.VK_C);
        inputs.player1.start = isDown(KeyEvent.VK_1);
        inputs.player1.select = isDown(KeyEvent.VK_3);

        inputs.player2.up = isDown(KeyEvent.VK_R);
        inputs.player2.down = isDown(KeyEvent.VK_F);
        inputs.player2.left = isDown(KeyEvent.VK_D);
        inputs.player2.right = isDown(KeyEvent.VK_G);
        inputs.player2.button1 = isDown(KeyEvent.VK_A);
        inputs.player2.button2 = isDown(KeyEvent.VK_S);
        inputs.player2.button3 = isDown(KeyEvent.VK_Q);
        inputs.player2.button4 = isDown(KeyEvent.VK_W);
        inputs.player2.start = isDown(KeyEvent.VK_2);
        inputs.player2.select = isDown(KeyEvent.VK_4);

        inputs.coin1 = isDown(KeyEvent.VK_5);
        inputs.coin2 = isDown(KeyEvent.VK_6);
        inputs.service = isDown(KeyEvent.VK_F1);

        for (Key key : Key.values()) {
            Integer code = keyCode(key);
            if (code != null) {
                inputs.keys[key.ordinal()] = isDown(code);
            }
        }
        return inputs;
    }

    private static Integer keyCode(Key key) {
        return switch (key) {
            case A -> KeyEvent.VK_A;
            case B -> KeyEvent.VK_B;
            case C -> KeyEvent.VK_C;
            case D -> KeyEvent.VK_D;
            case E -> KeyEvent.VK_E;
            case F -> KeyEvent.VK_F;
            case G -> KeyEvent.VK_G;
            case H -> KeyEvent.VK_H;
            case I -> KeyEvent.VK_I;
            case J -> KeyEvent.VK_J;
            case K -> KeyEvent.VK_K;
            case L -> KeyEvent.VK_L;
            case M -> KeyEvent.VK_M;
            case N -> KeyEvent.VK_N;
            case O -> KeyEvent.VK_O;
            case P -> KeyEvent.VK_P;
            case Q -> KeyEvent.VK_Q;
            case R -> KeyEvent.VK_R;
            case S -> KeyEvent.VK_S;
            case T -> KeyEvent.VK_T;
            case U -> KeyEvent.VK_U;
            case V -> KeyEvent.VK_V;
            case W -> KeyEvent.VK_W;
            case X -> KeyEvent.VK_X;
            case Y -> KeyEvent.VK_Y;
            case Z -> KeyEvent.VK_Z;
            case NUM0 -> KeyEvent.VK_0;
            case NUM1 -> KeyEvent.VK_1;
            case NUM2 -> KeyEvent.VK_2;
            case NUM3 -> KeyEvent.VK_3;
            case NUM4 -> KeyEvent.VK_4;
            case NUM5 -> KeyEvent.VK_5;
            case NUM6 -> KeyEvent.VK_6;
            case NUM7 -> KeyEvent.VK_7;
            case NUM8 -> KeyEvent.VK_8;
            case NUM9 -> KeyEvent.VK_9;
            case ENTER -> KeyEvent.VK_ENTER;
            case SPACE -> KeyEvent.VK_SPACE;
            case LEFT_SHIFT -> KeyEvent.VK_SHIFT;
            case RIGHT_SHIFT -> KeyEvent.VK_SHIFT;
            case LEFT_CTRL -> KeyEvent.VK_CONTROL;
            case RIGHT_CTRL -> KeyEvent.VK_CONTROL;
            case BACKSPACE -> KeyEvent.VK_BACK_SPACE;
            case UP -> KeyEvent.VK_UP;
            case DOWN -> KeyEvent.VK_DOWN;
            case LEFT -> KeyEvent.VK_LEFT;
            case RIGHT -> KeyEvent.VK_RIGHT;
            case COMMA -> KeyEvent.VK_COMMA;
            case PERIOD -> KeyEvent.VK_PERIOD;
            case SEMICOLON -> KeyEvent.VK_SEMICOLON;
            case QUOTE -> KeyEvent.VK_QUOTE;
            case SLASH -> KeyEvent.VK_SLASH;
            case MINUS -> KeyEvent.VK_MINUS;
            case ESCAPE -> KeyEvent.VK_ESCAPE;
            case TAB -> KeyEvent.VK_TAB;
            case CAPS_LOCK -> KeyEvent.VK_CAPS_LOCK;
            case HOME -> KeyEvent.VK_HOME;
            case CBM -> KeyEvent.VK_ALT;
            case EQUALS -> KeyEvent.VK_EQUALS;
            case PLUS -> KeyEvent.VK_ADD;
            case ASTERISK -> KeyEvent.VK_CLOSE_BRACKET;
            case AT -> KeyEvent.VK_OPEN_BRACKET;
            case F1 -> KeyEvent.VK_F1;
            case F2 -> KeyEvent.VK_F2;
            case F3 -> KeyEvent.VK_F3;
            case F4 -> KeyEvent.VK_F4;
            case F5 -> KeyEvent.VK_F5;
            case F6 -> KeyEvent.VK_F6;
            case F7 -> KeyEvent.VK_F7;
            case F8 -> KeyEvent.VK_F8;
            case F9 -> KeyEvent.VK_F9;
            case F10 -> KeyEvent.VK_F10;
            case F11 -> KeyEvent.VK_F11;
            case F12 -> KeyEvent.VK_F12;
            case BACKSLASH -> KeyEvent.VK_BACK_SLASH;
            case BACKQUOTE -> KeyEvent.VK_BACK_QUOTE;
            case DELETE -> KeyEvent.VK_DELETE;
            case LEFT_GUI -> KeyEvent.VK_WINDOWS;
            case RIGHT_GUI -> KeyEvent.VK_WINDOWS;
            case RIGHT_ALT -> KeyEvent.VK_ALT;
        };
    }
}
