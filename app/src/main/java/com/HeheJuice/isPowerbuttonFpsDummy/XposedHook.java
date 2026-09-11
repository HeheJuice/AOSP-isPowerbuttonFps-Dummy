package com.HeheJuice.isPowerbuttonFpsDummy;

import java.lang.reflect.Field;
import java.util.Collections;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix-V9";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String FM_CLASS = "android.hardware.fingerprint.FingerprintManager";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": START");

        // ---- 1. Neutralise FingerprintManager methods (safe defaults) ---------
        try {
            Class<?> fm = XposedHelpers.findClass(FM_CLASS, lpparam.classLoader);
            hook(fm, "getSensorPropertiesInternal",
                 XC_MethodReplacement.returnConstant(Collections.emptyList()));
            hook(fm, "getSensorPropertiesInternal", String.class,
                 XC_MethodReplacement.returnConstant(Collections.emptyList()));
            hook(fm, "getFirstFingerprintSensor",
                 XC_MethodReplacement.returnConstant(null));
            hook(fm, "isPowerbuttonFps",
                 XC_MethodReplacement.returnConstant(false));
            XposedBridge.log(TAG + ": FM hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FM hook failed: " + t);
        }

        // ---- 2. Hook the exact method that propagates the NPE ----------------
        // The crash originates in a lambda inside CentralSurfacesImpl$11,
        // but it propagates through batchApplyWindowLayoutParams.
        // Swallow the exception there so SystemUI survives.
        try {
            Class<?> nswc = XposedHelpers.findClass(
                    "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(nswc, "batchApplyWindowLayoutParams",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param)
                            throws Throwable {
                        // Run the original method; if it throws, swallow it.
                        try {
                            // Let it proceed normally
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + ": swallowed in before: " + t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        if (param.hasThrowable()) {
                            Throwable t = param.getThrowable();
                            XposedBridge.log(TAG + ": swallowing "
                                    + t.getClass().getSimpleName()
                                    + ": " + t.getMessage());
                            param.setThrowable(null);
                            param.setResult(null);
                        }
                    }
                });
            XposedBridge.log(TAG + ": batchApplyWindowLayoutParams hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NSWC hook failed: " + t);
        }

        XposedBridge.log(TAG + ": READY");
    }

    private static void hook(Class<?> c, String name, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }

    private static void hook(Class<?> c, String name, Class<?> arg, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, arg, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }
}