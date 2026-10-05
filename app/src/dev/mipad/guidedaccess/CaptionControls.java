package dev.mipad.guidedaccess;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class CaptionControls {
    private static final String TAG = "MiPadGuidedAccessCaption";
    private final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private volatile AccessState state = new AccessState(0, 0, false, AccessState.NO_TASK);
    private volatile Object viewModel;
    private boolean initialized;

    void install(ClassLoader loader) {
        try {
            Class<?> decorationClass = XposedHelpers.findClass(
                    "com.android.wm.shell.miuimultiwinswitch.miuiwindowdecor.MiuiBaseWindowDecoration",
                    loader);
            Class<?> windowDecorationClass = XposedHelpers.findClass(
                    "com.android.wm.shell.miuimultiwinswitch.miuiwindowdecor.MiuiWindowDecoration",
                    loader);
            Class<?> modelClass = XposedHelpers.findClass(
                    "com.android.wm.shell.miuimultiwinswitch.miuiwindowdecor.MiuiWindowDecorViewModel",
                    loader);
            XC_MethodHook hideCaption = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (locksDecoration(param.thisObject)) {
                        param.setResult(false);
                    }
                }
            };
            hooks.add(XposedHelpers.findAndHookMethod(decorationClass, "needTopCaption", hideCaption));
            hooks.add(XposedHelpers.findAndHookMethod(decorationClass, "needBottomCaption", hideCaption));
            hooks.add(XposedHelpers.findAndHookMethod(decorationClass, "shouldHideCaption",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (locksDecoration(param.thisObject)) {
                                param.setResult(true);
                            }
                        }
                    }));
            hooks.add(XposedHelpers.findAndHookMethod(windowDecorationClass, "handleCaptionClicked",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (locksDecoration(param.thisObject)) {
                                param.setResult(null);
                            }
                        }
                    }));
            hooks.addAll(XposedBridge.hookAllConstructors(modelClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    viewModel = param.thisObject;
                    refresh();
                }
            }));
            hooks.add(XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            initialize((Application) param.thisObject);
                        }
                    }));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) {
                hook.unhook();
            }
            hooks.clear();
            Log.e(TAG, "Caption hook installation failed", error);
        }
    }

    private void initialize(Context context) {
        if (initialized) {
            return;
        }
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    acceptState(intent.getExtras());
                }
            }, new IntentFilter(Control.STATE_ACTION), Control.PERMISSION,
                    handler, Context.RECEIVER_EXPORTED);
            initialized = true;
            Intent request = new Intent(Control.ACTION).setPackage(Control.SYSTEM_PACKAGE)
                    .putExtra(Control.COMMAND, Control.STATUS);
            context.sendOrderedBroadcast(request, null, new BroadcastReceiver() {
                @Override
                public void onReceive(Context ignored, Intent intent) {
                    acceptState(getResultExtras(false));
                }
            }, handler, Activity.RESULT_CANCELED, null, null);
        } catch (Throwable error) {
            Log.e(TAG, "Caption state initialization failed", error);
        }
    }

    private void acceptState(Bundle data) {
        try {
            AccessState next = Control.decode(data);
            if (next.isNewerThan(state)) {
                state = next;
                refresh();
                Log.i(TAG, "active=" + next.active + " task=" + next.taskId
                        + " revision=" + next.revision);
            }
        } catch (Throwable error) {
            Log.e(TAG, "Caption state update failed", error);
        }
    }

    private boolean locksDecoration(Object decoration) {
        AccessState current = state;
        if (!current.active) {
            return false;
        }
        ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo)
                XposedHelpers.getObjectField(decoration, "mTaskInfo");
        return task != null && current.locksTask(task.taskId);
    }

    private void refresh() {
        Object model = viewModel;
        if (model == null) {
            return;
        }
        try {
            Object executor = XposedHelpers.getObjectField(model, "mMainExecutor");
            XposedHelpers.callMethod(executor, "execute", (Runnable) () -> {
                try {
                    AccessState current = state;
                    if (current.active) {
                        SparseArray<?> decorations = (SparseArray<?>) XposedHelpers.getObjectField(
                                model, "mWindowDecorByTaskId");
                        Object decoration = decorations.get(current.taskId);
                        if (decoration != null) {
                            XposedHelpers.callMethod(decoration, "closeHandleMenuNoAnim");
                        }
                    }
                    XposedHelpers.callMethod(model, "relayoutDecorations");
                } catch (Throwable error) {
                    Log.e(TAG, "Caption layout update failed", error);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "Caption update scheduling failed", error);
        }
    }
}
