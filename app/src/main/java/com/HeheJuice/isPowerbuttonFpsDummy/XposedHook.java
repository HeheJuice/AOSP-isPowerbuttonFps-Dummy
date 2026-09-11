package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix-V8";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String FM_CLASS = "android.hardware.fingerprint.FingerprintManager";
    private static final String CS_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";

    private static Object sDummy;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": START");

        // ---- 1. Neutralise all FingerprintManager entry points ---------------
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
            hook(fm, "isHardwareDetected",
                 XC_MethodReplacement.returnConstant(false));
            hook(fm, "hasEnrolledFingerprints",
                 XC_MethodReplacement.returnConstant(false));
            XposedBridge.log(TAG + ": FM hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FM hook failed: " + t);
        }

        // ---- 2. Allocate a bare dummy ----------------------------------------
        sDummy = unsafeAllocate(FM_CLASS, lpparam.classLoader);
        XposedBridge.log(TAG + ": dummy=" + sDummy);
        if (sDummy == null) return;

        // ---- 3. Hook CentralSurfacesImpl --------------------------------------
        try {
            Class<?> cs = XposedHelpers.findClass(CS_CLASS, lpparam.classLoader);

            // 3a. After construction, ensure field is not null.
            XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    patchCs(p.thisObject, "ctor");
                }
            });

            // 3b. onStartedWakingUp can take an int (WakeReason) on A14.
            //     Hook every overload.
            for (Method m : cs.getDeclaredMethods()) {
                if (!m.getName().equals("onStartedWakingUp")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        patchCs(p.thisObject, "wake");
                    }
                });
            }

            // 3c. Also hook start() — Dagger field injection may happen here.
            for (Method m : cs.getDeclaredMethods()) {
                if (!m.getName().equals("start")) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        patchCs(p.thisObject, "start");
                    }
                });
            }

            XposedBridge.log(TAG + ": CS hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CS hook failed: " + t);
        }

        XposedBridge.log(TAG + ": READY");
    }

    /** Find any FingerprintManager-typed field on target and set it to the dummy. */
    private static void patchCs(Object target, String from) {
        if (target == null) return;
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().equals(FM_CLASS)) continue;
                try {
                    f.setAccessible(true);
                    Object cur = f.get(target);
                    if (cur == null) {
                        f.set(target, sDummy);
                        XposedBridge.log(TAG + "[" + from + "]: set " + f.getName());
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "[" + from + "]: " + f.getName() + " -> " + t);
                }
            }
            c = c.getSuperclass();
        }
    }

    private static void hook(Class<?> c, String name, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }

    private static void hook(Class<?> c, String name, Class<?> arg, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, arg, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }

    private static Object unsafeAllocate(String className, ClassLoader cl) {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field uf = uc.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            Method alloc = uc.getMethod("allocateInstance", Class.class);
            Class<?> target = Class.forName(className, false, cl);
            return alloc.invoke(unsafe, target);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": unsafeAllocate failed: " + t);
            return null;
        }
    }
}