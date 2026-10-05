package dev.mipad.guidedaccess;

final class VolumeChord {
    static final long HOLD_MILLIS = 5000;
    static final long JOIN_MILLIS = 150;
    static final int UP = 1;
    static final int DOWN = 2;

    private int pressed;
    private long deadline;
    private boolean waitingForRelease;
    private boolean volumeChord;
    private boolean discardVolumeEvent;
    private long volumeDeadline;

    synchronized long update(int key, boolean down, long now) {
        discardVolumeEvent = consumesKeys();
        if (pressed == 0 && down) {
            volumeDeadline = now + JOIN_MILLIS;
        }
        pressed = down ? pressed | key : pressed & ~key;
        if (pressed == 0) {
            waitingForRelease = false;
            volumeChord = false;
        } else if (pressed == (UP | DOWN)) {
            volumeChord = true;
        }

        if (pressed != (UP | DOWN) || waitingForRelease) {
            deadline = 0;
        } else if (deadline == 0) {
            deadline = now + HOLD_MILLIS;
        }
        discardVolumeEvent |= consumesKeys();
        return deadline;
    }

    synchronized boolean discardVolumeEvent() {
        return discardVolumeEvent;
    }

    synchronized long volumeDeadline() {
        return volumeDeadline;
    }

    synchronized boolean consumeToggle(long now) {
        if (deadline == 0 || now < deadline || pressed != (UP | DOWN)) {
            return false;
        }

        deadline = 0;
        waitingForRelease = true;
        return true;
    }

    synchronized boolean consumesKeys() {
        return volumeChord || waitingForRelease;
    }

    synchronized void reset() {
        pressed = 0;
        deadline = 0;
        waitingForRelease = false;
        volumeChord = false;
        discardVolumeEvent = false;
        volumeDeadline = 0;
    }
}
