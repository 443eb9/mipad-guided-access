package dev.mipad.guidedaccess;

public final class AccessStateTest {
    private static int assertions;

    public static void main(String[] args) {
        AccessState initial = new AccessState(0, 0, false, AccessState.NO_TASK);
        AccessState locked = new AccessState(100, 1, true, 40);
        check(locked.isNewerThan(initial), "First owner state is accepted");
        check(locked.locksTask(40), "Selected task is locked");
        check(!locked.locksTask(41), "Other task stays available to its own decoration policy");
        check(!initial.locksTask(40), "Initial state uses normal decoration policy");
        check(!new AccessState(100, 0, false, AccessState.NO_TASK).isNewerThan(locked), "Older revision is ignored");
        check(!new AccessState(100, 1, true, 40).isNewerThan(locked), "Duplicate revision is ignored");

        AccessState released = new AccessState(100, 2, false, AccessState.NO_TASK);
        check(released.isNewerThan(locked), "Exit state is accepted");
        check(!released.locksTask(40), "Exit restores decoration policy");
        check(!locked.isNewerThan(released), "Delayed entry packet is ignored after exit");

        AccessState restarted = new AccessState(200, 0, false, AccessState.NO_TASK);
        check(restarted.isNewerThan(released), "A restarted owner replaces the previous owner");
        check(!new AccessState(100, 999, true, 40).isNewerThan(restarted),
                "Delayed packets from the previous owner are ignored");
        AccessState nextTask = new AccessState(200, 1, true, 52);
        check(nextTask.isNewerThan(restarted), "The new owner can enter guided access");
        check(nextTask.locksTask(52), "New task is selected");
        check(!nextTask.locksTask(40), "Previous task follows normal policy");
        System.out.println("AccessStateTest: " + assertions + " assertions passed");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
