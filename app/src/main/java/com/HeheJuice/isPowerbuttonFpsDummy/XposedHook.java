package com.HeheJuice.isPowerbuttonFpsDummy;

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

    private static final String TAG = "FPSDummyFix-V7";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String FINGERPRINT_CLASS =
            "android.hardware.fingerprint.FingerprintManager";
    private static final String CENTRAL_SURFACES_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";

    private static Object sDummy;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": START");

        // ---- 1. Neutralise FingerprintManager methods --------------------------
        // These make ANY FingerprintManager reference (real or dummy) safe to call.
        try {
            Class<?> fm = XposedHelpers.findClass(FINGERPRINT_CLASS, lpparam.classLoader);

            hookAny(fm, "getSensorPropertiesInternal",
                    XC_MethodReplacement.returnConstant(Collections.emptyList()));
            hookAny(fm, "getSensorPropertiesInternal", String.class,
                    XC_MethodReplacement.returnConstant(Collections.emptyList()));
            hookAny(fm, "getFirstFingerprintSensor",
                    XC_MethodReplacement.returnConstant(null));
            hookAny(fm, "isPowerbuttonFps",
                    XC_MethodReplacement.returnConstant(false));

            XposedBridge.log(TAG + ": FM hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": FM hook failed: " + t);
        }

        // ---- 2. Allocate a bare dummy instance --------------------------------
        sDummy = unsafeAllocate(FINGERPRINT_CLASS, lpparam.classLoader);
        XposedBridge.log(TAG + ": dummy=" + sDummy);
        if (sDummy == null) return;

        // ---- 3. Make sure mFingerprintManager is never null -------------------
        try {
            Class<?> cs = XposedHelpers.findClass(CENTRAL_SURFACES_CLASS, lpparam.classLoader);

            // 3a. Outer class ctor, in case the field is field-injected synchronously.
            XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    forceNonNull(p.thisObject, "CS-ctor");
                }
            });

            // 3b. Every inner observer of CentralSurfacesImpl that reacts to wake.
            //     This covers CentralSurfacesImpl$11 and any equivalent in other builds.
            int observerCount = 0;
            for (Class<?> inner : cs.getDeclaredClasses()) {
                if (findField(inner, "this$0") == null) continue;
                boolean hasWake = false;
                for (Method m : inner.getDeclaredMethods()) {
                    if (m.getName().equals("onStartedWakingUp")) { hasWake = true; break; }
                }
                if (!hasWake) continue;

                XposedBridge.hookAllConstructors(inner, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        forceNonNull(getField(p.thisObject, "this$0"), "inner-ctor");
                    }
                });
                XposedBridge.hookAllMethods(inner, "onStartedWakingUp", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        forceNonNull(getField(p.thisObject, "this$0"), "inner-wake");
                    }
                });
                observerCount++;
                XposedBridge.log(TAG + ": hooked inner " + inner.getName());
            }

            XposedBridge.log(TAG + ": CS hooks installed, observers=" + observerCount);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CS hook failed: " + t);
        }

        XposedBridge.log(TAG + ": READY");
    }

    // ---- helpers -------------------------------------------------------------

    private static void forceNonNull(Object target, String from) {
        if (target == null) return;
        Field f = findField(target.getClass(), "mFingerprintManager");
        if (f == null) {
            XposedBridge.log(TAG + "[" + from + "]: field not found on " + target.getClass());
            return;
        }
        try {
            f.setAccessible(true);
            Object cur = f.get(target);
            if (cur == null) {
                f.set(target, sDummy);
                XposedBridge.log(TAG + "[" + from + "]: set dummy");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + from + "]: " + t);
        }
    }

    private static void hookAny(Class<?> c, String name, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }

    private static void hookAny(Class<?> c, String name, Class<?> arg, Object replacement) {
        try { XposedHelpers.findAndHookMethod(c, name, arg, (XC_MethodHook) replacement); }
        catch (Throwable ignored) {}
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try { f.setAccessible(true); return f.get(obj); }
        catch (Throwable t) { return null; }
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