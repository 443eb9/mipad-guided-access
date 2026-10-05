package dev.mipad.guidedaccess;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Toast;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class GuidedAccess implements IXposedHookLoadPackage {
    private static final String TAG = "MiPadGuidedAccess";
    private static final int BAR_TYPES = WindowInsets.Type.statusBars()
            | WindowInsets.Type.navigationBars();

    private final long ownerStartedMillis = SystemClock.elapsedRealtime();
    private long stateRevision;
    private final VolumeChord chord = new VolumeChord();
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private final List<KeyEvent> pendingVolumeEvents = new ArrayList<>();
    private final Set<Integer> replayEventIds = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;
    private volatile Session session;
    private Object activityService;
    private Object policy;
    private Context context;
    private Handler keyHandler;
    private Handler taskHandler;
    private InputManager inputManager;
    private int injectionMode;
    private int displayOverlayType;
    private Class<?> windowClass;
    private volatile boolean ready;

    private final Runnable hold = () -> {
        if (chord.consumeToggle(SystemClock.uptimeMillis())) {
            taskHandler.post(this::toggle);
        }
    };

    private final Runnable deliverVolume = this::deliverVolumeEvents;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                || !"pipa".equals(Build.DEVICE)) {
            return;
        }
        if (Control.SYSTEM_UI_PACKAGE.equals(param.packageName)
                && Control.SYSTEM_UI_PACKAGE.equals(param.processName)) {
            new CaptionControls().install(param.classLoader);
            return;
        }
        if (!Control.SYSTEM_PACKAGE.equals(param.packageName)) {
            return;
        }

        try {
            installHooks(param.classLoader);
            log("Hooks installed");
        } catch (Throwable error) {
            enabled = false;
            for (XC_MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            hooks.clear();
            logError("Hook installation failed", error);
        }
    }

    private void installHooks(ClassLoader loader) {
        Class<?> serviceClass = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService", loader);
        Class<?> policyClass = XposedHelpers.findClass(
                "com.android.server.policy.MiuiPhoneWindowManager", loader);
        windowClass = XposedHelpers.findClass("com.android.server.wm.WindowState", loader);
        Class<?> insetsClass = XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", loader);
        Class<?> displayPolicyClass = XposedHelpers.findClass(
                "com.android.server.wm.DisplayPolicy", loader);
        Class<?> lockClass = XposedHelpers.findClass(
                "com.android.server.wm.LockTaskController", loader);
        Class<?> sourceProviderClass = XposedHelpers.findClass(
                "com.android.server.wm.InsetsSourceProvider", loader);
        Class<?> controlTargetClass = XposedHelpers.findClass(
                "com.android.server.wm.InsetsControlTarget", loader);
        Class<?> animatorClass = XposedHelpers.findClass(
                "com.android.server.wm.WindowStateAnimator", loader);

        List<Member> visibilityCallers = new ArrayList<>();
        for (String name : new String[]{"isOnScreen", "isVisible", "isVisibleByPolicyOrInsets",
                "isReadyForDisplay"}) {
            visibilityCallers.add(XposedHelpers.findMethodExact(windowClass, name));
        }
        visibilityCallers.add(XposedHelpers.findMethodExact(animatorClass,
                "prepareSurfaceLocked", SurfaceControl.Transaction.class));
        for (Member caller : visibilityCallers) {
            XposedHelpers.callStaticMethod(XposedBridge.class, "deoptimizeMethod", caller);
        }

        hooks.addAll(XposedBridge.hookAllConstructors(serviceClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                activityService = param.thisObject;
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(policyClass, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    initialize(param.thisObject);
                } catch (Throwable error) {
                    enabled = false;
                    logError("Initialization failed", error);
                }
            }
        }));
        hooks.add(XposedHelpers.findAndHookMethod(policyClass, "interceptKeyBeforeQueueing",
                KeyEvent.class, int.class, new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!ready) {
                            return;
                        }

                        try {
                            KeyEvent event = (KeyEvent) param.args[0];
                            int code = event.getKeyCode();
                            boolean volume = code == KeyEvent.KEYCODE_VOLUME_UP
                                    || code == KeyEvent.KEYCODE_VOLUME_DOWN;
                            if (volume && replayEventIds.remove(eventId(event))) {
                                return;
                            }
                            if (!enabled) {
                                return;
                            }
                            if (volume) {
                                volumeKey(event);
                                param.setResult(0);
                            } else if (session != null && isNavigationKey(event)) {
                                param.setResult(0);
                            }
                        } catch (Throwable error) {
                            logError("Key handling failed", error);
                            taskHandler.post(GuidedAccess.this::exit);
                        }
                    }
                }));
        Class<?> basePolicyClass = XposedHelpers.findClass(
                "com.android.server.policy.PhoneWindowManager", loader);
        hooks.add(XposedHelpers.findAndHookMethod(basePolicyClass, "finishedGoingToSleep",
                int.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (keyHandler != null) {
                            resetChord();
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(windowClass, "getRequestedVisibleTypes",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (session != null && belongsToDisplay(param.thisObject)) {
                            param.setResult((int) param.getResult() & ~BAR_TYPES);
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(windowClass, "isRequestedVisible", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (session != null && ((int) param.args[0] & BAR_TYPES) != 0
                                && belongsToDisplay(param.thisObject)) {
                            int remaining = (int) param.args[0] & ~BAR_TYPES;
                            int requested = XposedHelpers.getIntField(
                                    param.thisObject, "mRequestedVisibleTypes");
                            param.setResult((requested & remaining) != 0);
                        }
                    }
                }));

        hooks.add(XposedHelpers.findAndHookMethod(sourceProviderClass, "updateClientVisibility",
                controlTargetClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSessionBarProvider(param.thisObject)
                                && param.args[0] == XposedHelpers.getObjectField(
                                        param.thisObject, "mControlTarget")) {
                            boolean changed = XposedHelpers.getBooleanField(
                                    param.thisObject, "mClientVisible");
                            XposedHelpers.callMethod(param.thisObject, "setClientVisible", false);
                            param.setResult(changed);
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(sourceProviderClass, "setClientVisible",
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSessionBarProvider(param.thisObject)) {
                            param.args[0] = false;
                        }
                    }
                }));

        XC_MethodHook controlTarget = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (isSessionInsetsPolicy(param.thisObject) && param.args[0] != null) {
                    param.setResult(param.args[0]);
                }
            }
        };
        hooks.add(XposedHelpers.findAndHookMethod(insetsClass, "getStatusControlTarget",
                windowClass, boolean.class, controlTarget));
        hooks.add(XposedHelpers.findAndHookMethod(insetsClass, "getNavControlTarget",
                windowClass, boolean.class, controlTarget));
        hooks.add(XposedHelpers.findAndHookMethod(insetsClass, "showTransient", int.class,
                boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isSessionInsetsPolicy(param.thisObject)) {
                            param.args[0] = (int) param.args[0] & ~BAR_TYPES;
                            if ((int) param.args[0] == 0) {
                                param.setResult(null);
                            }
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(displayPolicyClass, "requestTransientBars",
                windowClass, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Session current = session;
                        if (current != null && XposedHelpers.getObjectField(
                                param.thisObject, "mDisplayContent") == current.display) {
                            param.setResult(null);
                        }
                    }
                }));
        hooks.addAll(XposedBridge.hookAllMethods(serviceClass, "startBackNavigation",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (session != null) {
                            param.setResult(null);
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(windowClass, "isVisibleByPolicy",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Session current = session;
                        if (current != null && (boolean) param.getResult()
                                && isVendorOverlay(current, param.thisObject)) {
                            param.setResult(false);
                        }
                    }
                }));
        hooks.add(XposedHelpers.findAndHookMethod(lockClass, "performStopLockTask", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Session current = session;
                        if (current != null && current.controller == param.thisObject) {
                            exit();
                        }
                    }
                }));
    }

    private void initialize(Object policyObject) {
        if (ready) {
            return;
        }

        policy = policyObject;
        context = (Context) XposedHelpers.getObjectField(policy, "mContext");
        keyHandler = (Handler) XposedHelpers.getObjectField(policy, "mHandler");
        Object controller = XposedHelpers.getObjectField(activityService, "mLockTaskController");
        taskHandler = (Handler) XposedHelpers.getObjectField(controller, "mHandler");
        inputManager = context.getSystemService(InputManager.class);
        injectionMode = XposedHelpers.getStaticIntField(
                InputManager.class, "INJECT_INPUT_EVENT_MODE_ASYNC");
        displayOverlayType = XposedHelpers.getStaticIntField(
                WindowManager.LayoutParams.class, "TYPE_DISPLAY_OVERLAY");
        IntentFilter filter = new IntentFilter(Control.ACTION);
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                String command = intent.getStringExtra(Control.COMMAND);
                if (Control.ENABLE.equals(command)) {
                    enabled = true;
                    publishState();
                } else if (Control.DISABLE.equals(command)) {
                    enabled = false;
                    resetChord();
                    exit();
                    publishState();
                } else if (Control.EXIT.equals(command)) {
                    resetChord();
                    exit();
                }
                setResultExtras(Control.encode(accessState()));
                setResultData(status());
                log(status());
            }
        }, filter, Control.PERMISSION, taskHandler, Context.RECEIVER_EXPORTED);
        ready = true;
        log("Ready, waiting for Magisk service");
    }

    private void volumeKey(KeyEvent event) {
        synchronized (chord) {
            int key = event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP
                    ? VolumeChord.UP : VolumeChord.DOWN;
            long deadline = chord.update(key, event.getAction() == KeyEvent.ACTION_DOWN,
                    SystemClock.uptimeMillis());
            keyHandler.removeCallbacks(hold);
            if (deadline != 0) {
                keyHandler.postAtTime(hold, deadline);
            }

            keyHandler.removeCallbacks(deliverVolume);
            if (chord.discardVolumeEvent()) {
                pendingVolumeEvents.clear();
            } else {
                pendingVolumeEvents.add(new KeyEvent(event));
                keyHandler.postAtTime(deliverVolume, chord.volumeDeadline());
            }
        }
    }

    private void deliverVolumeEvents() {
        List<KeyEvent> events;
        synchronized (chord) {
            events = new ArrayList<>(pendingVolumeEvents);
            pendingVolumeEvents.clear();
        }

        for (KeyEvent event : events) {
            Integer id = null;
            try {
                id = eventId(event);
                replayEventIds.add(id);
                boolean accepted = (boolean) XposedHelpers.callMethod(
                        inputManager, "injectInputEvent", event, injectionMode);
                if (!accepted) {
                    throw new IllegalStateException("Volume key injection was rejected");
                }
            } catch (Throwable error) {
                if (id != null) {
                    replayEventIds.remove(id);
                }
                enabled = false;
                resetChord();
                taskHandler.post(this::exit);
                logError("Volume key delivery failed", error);
                return;
            }
        }
    }

    private static int eventId(KeyEvent event) {
        return (int) XposedHelpers.callMethod(event, "getId");
    }

    private static boolean isNavigationKey(KeyEvent event) {
        if (event.isMetaPressed()) {
            return true;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT:
            case KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT:
                return true;
            default:
                return false;
        }
    }

    private boolean belongsToDisplay(Object window) {
        Session current = session;
        return current != null
                && XposedHelpers.callMethod(window, "getDisplayContent") == current.display;
    }

    private boolean isSessionBarProvider(Object provider) {
        Session current = session;
        if (current == null || XposedHelpers.getObjectField(
                provider, "mDisplayContent") != current.display) {
            return false;
        }
        Object source = XposedHelpers.getObjectField(provider, "mSource");
        return ((int) XposedHelpers.callMethod(source, "getType") & BAR_TYPES) != 0;
    }

    private boolean isSessionInsetsPolicy(Object insetsPolicy) {
        Session current = session;
        return current != null && XposedHelpers.getObjectField(
                insetsPolicy, "mDisplayContent") == current.display;
    }

    private void toggle() {
        if (!enabled) {
            return;
        }
        if (session != null) {
            exit();
            return;
        }

        try {
            enter();
        } catch (Throwable error) {
            logError("Entry failed", error);
            exit();
            showMessage("引导式访问启动失败，请查看日志");
        }
    }

    @SuppressWarnings("unchecked")
    private void enter() {
        if (!context.getSystemService(android.os.PowerManager.class).isInteractive()
                || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            showMessage("请解锁屏幕后启动引导式访问");
            return;
        }

        synchronized (XposedHelpers.getObjectField(activityService, "mGlobalLock")) {
            Object controller = XposedHelpers.getObjectField(activityService, "mLockTaskController");
            if ((int) XposedHelpers.callMethod(controller, "getLockTaskModeState")
                    != ActivityManager.LOCK_TASK_MODE_NONE) {
                showMessage("请先结束系统屏幕固定或锁定任务");
                return;
            }

            Object root = XposedHelpers.getObjectField(activityService, "mRootWindowContainer");
            Object activity = XposedHelpers.callMethod(root, "topRunningActivity");
            if (activity == null) {
                return;
            }
            Object task = XposedHelpers.callMethod(activity, "getTask");
            Class<?> windowConfiguration = XposedHelpers.findClass(
                    "android.app.WindowConfiguration", null);
            int standard = XposedHelpers.getStaticIntField(windowConfiguration, "ACTIVITY_TYPE_STANDARD");
            int fullscreen = XposedHelpers.getStaticIntField(windowConfiguration, "WINDOWING_MODE_FULLSCREEN");
            if ((int) XposedHelpers.callMethod(task, "getActivityType") != standard
                    || (int) XposedHelpers.callMethod(task, "getWindowingMode") != fullscreen
                    || (int) XposedHelpers.callMethod(task, "getDisplayId") != Display.DEFAULT_DISPLAY) {
                showMessage("请在主屏幕打开一个全屏 App");
                return;
            }

            int userId = XposedHelpers.getIntField(task, "mUserId");
            SparseArray<String[]> packages = (SparseArray<String[]>) XposedHelpers.getObjectField(
                    controller, "mLockTaskPackages");
            SparseIntArray features = (SparseIntArray) XposedHelpers.getObjectField(
                    controller, "mLockTaskFeatures");
            String[] previousPackages = packages.get(userId);
            if (previousPackages != null && previousPackages.length > 0) {
                showMessage("当前用户已有设备管理锁定策略");
                return;
            }

            String packageName = (String) XposedHelpers.getObjectField(activity, "packageName");
            Session current = new Session(controller, task,
                    XposedHelpers.callMethod(task, "getDisplayContent"), userId, packageName,
                    previousPackages, packages.indexOfKey(userId) >= 0,
                    features.get(userId, DevicePolicyManager.LOCK_TASK_FEATURE_NONE),
                    features.indexOfKey(userId) >= 0);
            session = current;
            XposedHelpers.callMethod(controller, "updateLockTaskFeatures", userId,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            XposedHelpers.callMethod(controller, "updateLockTaskPackages", userId,
                    new String[]{packageName});
            XposedHelpers.callMethod(controller, "startLockTaskMode", task, false, Process.SYSTEM_UID);
            List<?> lockedTasks = (List<?>) XposedHelpers.getObjectField(controller, "mLockTaskModeTasks");
            if (!lockedTasks.contains(task)) {
                throw new IllegalStateException("Target task was rejected");
            }
            refreshBars(current);
            log("Entered task=" + XposedHelpers.getIntField(task, "mTaskId") + " package=" + packageName);
        }
        publishState();
        showMessage("引导式访问已开启，双音量键长按 5 秒退出");
    }

    @SuppressWarnings("unchecked")
    private void exit() {
        Session current = session;
        if (current == null) {
            return;
        }

        synchronized (XposedHelpers.getObjectField(activityService, "mGlobalLock")) {
            session = null;
            try {
                XposedHelpers.callMethod(current.controller, "clearLockedTasks", "guided access exit");
                XposedHelpers.callMethod(current.controller, "updateLockTaskPackages", current.userId,
                        current.packages == null ? new String[0] : current.packages);
                if (!current.hadPackages) {
                    ((SparseArray<String[]>) XposedHelpers.getObjectField(
                            current.controller, "mLockTaskPackages")).remove(current.userId);
                }
                XposedHelpers.callMethod(current.controller, "updateLockTaskFeatures",
                        current.userId, current.features);
                if (!current.hadFeatures) {
                    ((SparseIntArray) XposedHelpers.getObjectField(
                            current.controller, "mLockTaskFeatures")).delete(current.userId);
                }
                refreshBars(current);
                log("Exited package=" + current.packageName);
                showMessage("引导式访问已关闭");
            } catch (Throwable error) {
                logError("Exit restoration failed", error);
            }
        }
        publishState();
    }

    private boolean isVendorOverlay(Session current, Object window) {
        if (XposedHelpers.callMethod(window, "getDisplayContent") != current.display) {
            return false;
        }
        WindowManager.LayoutParams attrs = (WindowManager.LayoutParams)
                XposedHelpers.getObjectField(window, "mAttrs");
        return Control.SECURITY_CENTER_PACKAGE.equals(attrs.packageName)
                && (attrs.type == displayOverlayType
                || attrs.type == WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                || attrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }

    private AccessState accessState() {
        Session current = session;
        return new AccessState(ownerStartedMillis, stateRevision, current != null,
                current == null ? AccessState.NO_TASK : XposedHelpers.getIntField(current.task, "mTaskId"));
    }

    private void publishState() {
        stateRevision++;
        try {
            context.sendBroadcast(new Intent(Control.STATE_ACTION)
                    .setPackage(Control.SYSTEM_UI_PACKAGE).putExtras(Control.encode(accessState())));
        } catch (Throwable error) {
            logError("State publication failed", error);
        }
    }

    private void refreshBars(Session current) {
        Object insetsPolicy = XposedHelpers.callMethod(current.display, "getInsetsPolicy");
        XposedHelpers.callMethod(insetsPolicy, "abortTransient");
        Object focused = XposedHelpers.getObjectField(current.display, "mCurrentFocus");
        XposedHelpers.callMethod(insetsPolicy, "updateBarControlTarget", focused);
        if (focused != null) {
            XposedHelpers.callMethod(insetsPolicy, "onInsetsModified", focused);
        }
        Object wm = XposedHelpers.getObjectField(current.display, "mWmService");
        XposedHelpers.callMethod(wm, "requestTraversal");
    }

    private void resetChord() {
        synchronized (chord) {
            chord.reset();
            pendingVolumeEvents.clear();
            keyHandler.removeCallbacks(hold);
            keyHandler.removeCallbacks(deliverVolume);
        }
    }

    private String status() {
        Session current = session;
        return "enabled=" + enabled + " active=" + (current != null)
                + (current == null ? "" : " package=" + current.packageName
                + " task=" + XposedHelpers.getIntField(current.task, "mTaskId"));
    }

    private void showMessage(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable error) {
                logError("Toast failed", error);
            }
        });
    }

    private static void log(String message) {
        Log.i(TAG, message);
    }

    private static void logError(String message, Throwable error) {
        Log.e(TAG, message, error);
        XposedBridge.log(TAG + ": " + message);
        XposedBridge.log(error);
    }

    private static final class Session {
        final Object controller;
        final Object task;
        final Object display;
        final int userId;
        final String packageName;
        final String[] packages;
        final boolean hadPackages;
        final int features;
        final boolean hadFeatures;

        Session(Object controller, Object task, Object display, int userId, String packageName,
                String[] packages, boolean hadPackages, int features, boolean hadFeatures) {
            this.controller = controller;
            this.task = task;
            this.display = display;
            this.userId = userId;
            this.packageName = packageName;
            this.packages = packages;
            this.hadPackages = hadPackages;
            this.features = features;
            this.hadFeatures = hadFeatures;
        }
    }
}
