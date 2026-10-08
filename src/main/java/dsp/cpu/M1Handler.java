package dsp.cpu;

/** Called on every opcode-fetch M1 that advances R (RZX-compatible). */
@FunctionalInterface
public interface M1Handler {
    void onM1();
}
