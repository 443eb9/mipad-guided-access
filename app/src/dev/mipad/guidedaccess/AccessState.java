package dev.mipad.guidedaccess;

final class AccessState {
    static final int NO_TASK = -1;

    final long ownerStartedMillis;
    final long revision;
    final boolean active;
    final int taskId;

    AccessState(long ownerStartedMillis, long revision, boolean active, int taskId) {
        this.ownerStartedMillis = ownerStartedMillis;
        this.revision = revision;
        this.active = active;
        this.taskId = taskId;
    }

    boolean isNewerThan(AccessState previous) {
        return ownerStartedMillis > previous.ownerStartedMillis
                || (ownerStartedMillis == previous.ownerStartedMillis && revision > previous.revision);
    }

    boolean locksTask(int id) {
        return active && taskId == id;
    }
}
