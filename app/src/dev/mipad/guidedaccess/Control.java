package dev.mipad.guidedaccess;

import android.os.Bundle;

final class Control {
    static final String ACTION = "dev.mipad.guidedaccess.CONTROL";
    static final String STATE_ACTION = "dev.mipad.guidedaccess.STATE";
    static final String PERMISSION = "android.permission.MANAGE_ACTIVITY_TASKS";
    static final String SYSTEM_PACKAGE = "android";
    static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    static final String SECURITY_CENTER_PACKAGE = "com.miui.securitycenter";
    static final String COMMAND = "command";
    static final String ENABLE = "enable";
    static final String DISABLE = "disable";
    static final String EXIT = "exit";
    static final String STATUS = "status";

    private static final String OWNER_STARTED = "ownerStartedMillis";
    private static final String REVISION = "revision";
    private static final String ACTIVE = "active";
    private static final String TASK = "taskId";

    private Control() {}

    static Bundle encode(AccessState state) {
        Bundle result = new Bundle();
        result.putLong(OWNER_STARTED, state.ownerStartedMillis);
        result.putLong(REVISION, state.revision);
        result.putBoolean(ACTIVE, state.active);
        result.putInt(TASK, state.taskId);
        return result;
    }

    static AccessState decode(Bundle data) {
        if (data == null || !data.containsKey(OWNER_STARTED) || !data.containsKey(REVISION)
                || !data.containsKey(ACTIVE) || !data.containsKey(TASK)) {
            throw new IllegalArgumentException("Missing guided access state fields");
        }
        return new AccessState(data.getLong(OWNER_STARTED), data.getLong(REVISION),
                data.getBoolean(ACTIVE), data.getInt(TASK));
    }
}
