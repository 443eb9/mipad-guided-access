package dev.mipad.guidedaccess;

public final class VolumeChordTest {
    private static int assertions;

    public static void main(String[] args) {
        VolumeChord chord = new VolumeChord();
        check(chord.update(VolumeChord.UP, true, 100) == 0, "One key has no timer");
        check(chord.update(VolumeChord.DOWN, true, 250) == 5250, "Timer starts at second key");
        check(!chord.consumeToggle(5249), "4999 milliseconds is short");
        check(chord.consumeToggle(5250), "5000 milliseconds triggers");
        check(!chord.consumeToggle(20000), "Holding longer triggers once");
        chord.update(VolumeChord.UP, false, 20001);
        check(chord.update(VolumeChord.UP, true, 20002) == 0, "Both keys must be released to rearm");
        chord.update(VolumeChord.UP, false, 20003);
        chord.update(VolumeChord.DOWN, false, 20004);
        check(!chord.consumesKeys(), "Fully released returns normal volume handling");
        chord.update(VolumeChord.DOWN, true, 21000);
        check(chord.update(VolumeChord.UP, true, 21500) == 26500, "Reverse order works");
        check(chord.update(VolumeChord.UP, true, 22000) == 26500, "Key repeat preserves timer");
        check(chord.consumeToggle(26500), "Next complete hold triggers again");

        chord.reset();
        chord.update(VolumeChord.UP, true, 30000);
        chord.update(VolumeChord.DOWN, true, 30001);
        chord.update(VolumeChord.DOWN, false, 34000);
        check(!chord.consumeToggle(35001), "Early release cancels timer");
        check(chord.update(VolumeChord.DOWN, true, 36000) == 41000, "Interrupted hold starts fresh");
        check(!chord.consumeToggle(40999), "Fresh hold observes full duration");
        chord.reset();
        check(!chord.consumeToggle(42000), "Reset cancels pending hold");
        check(!chord.consumesKeys(), "Reset restores volume keys");
        chord.update(VolumeChord.UP, true, 50000);
        check(!chord.discardVolumeEvent(), "First key is buffered for single-key delivery");
        check(chord.volumeDeadline() == 50150, "Single key waits for the join window");
        chord.update(VolumeChord.UP, false, 50040);
        check(!chord.discardVolumeEvent(), "Single tap release is buffered with the press");
        check(chord.volumeDeadline() == 50150, "Release preserves the delivery time");
        check(!chord.consumeToggle(55000), "Single key never toggles guided access");

        chord.reset();
        chord.update(VolumeChord.DOWN, true, 60000);
        chord.update(VolumeChord.UP, true, 60080);
        check(chord.discardVolumeEvent(), "Second key cancels buffered volume events");
        check(chord.volumeDeadline() == 60150, "Second key preserves the first-key join window");
        chord.update(VolumeChord.DOWN, false, 62000);
        check(chord.discardVolumeEvent(), "First chord release is consumed");
        chord.update(VolumeChord.UP, false, 62010);
        check(chord.discardVolumeEvent(), "Final chord release is consumed");
        check(!chord.consumeToggle(65080), "Short chord leaves guided access unchanged");

        chord.update(VolumeChord.UP, true, 70000);
        check(!chord.discardVolumeEvent(), "Single-key volume handling resumes after a chord");
        check(chord.volumeDeadline() == 70150, "A new press gets a new join window");
        chord.update(VolumeChord.UP, true, 71000);
        check(chord.volumeDeadline() == 70150, "Single-key repeat keeps its original delivery time");
        chord.reset();
        check(chord.volumeDeadline() == 0, "Reset clears the volume delivery deadline");
        System.out.println("VolumeChordTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
