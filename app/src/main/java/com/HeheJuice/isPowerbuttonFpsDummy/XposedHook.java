package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix-V4";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String CENTRAL_SURFACES_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";
    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11";
    private static final String FINGERPRINT_CLASS =
            "android.hardware.fingerprint.FingerprintManager";

    private static Object sDummy;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": ===== V4 START =====");

        // 1. Allocate a real FingerprintManager instance without calling its ctor
        sDummy = unsafeAllocate(FINGERPRINT_CLASS, lpparam.classLoader);
        XposedBridge.log(TAG + ": dummy = " + sDummy);

        if (sDummy == null) {
            XposedBridge.log(TAG + ": FATAL - Unsafe allocation failed, cannot patch.");
            return;
        }

        // 2. Hook CentralSurfacesImpl ctor -> set field immediately
        try {
            Class<?> cs = XposedHelpers.findClass(CENTRAL_SURFACES_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    setField(param.thisObject, "ctor");
                }
            });
            XposedBridge.log(TAG + ": ctor hooked");

            // 3. Hook onStartedWakingUp directly
            XposedBridge.hookAllMethods(cs, "onStartedWakingUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    setField(param.thisObject, "onStartedWakingUp");
                }
            });
            XposedBridge.log(TAG + ": onStartedWakingUp hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CS hook failed: " + t);
        }

        // 4. Hook the observer class ($11) constructor too
        try {
            Class<?> ob = XposedHelpers.findClass(OBSERVER_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(ob, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object outer = getField(param.thisObject, "this$0");
                    setField(outer, "observer-ctor");
                }
            });
            XposedBridge.log(TAG + ": observer ctor hooked");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": observer hook failed: " + t);
        }

        XposedBridge.log(TAG + ": ===== V4 READY =====");
    }

    private static void setField(Object target, String from) {
        if (target == null) return;
        try {
            Field f = findField(target.getClass(), "mFingerprintManager");
            if (f == null) {
                XposedBridge.log(TAG + "[" + from + "]: field not found on "
                        + target.getClass().getSimpleName());
                return;
            }
            f.setAccessible(true);
            Object prev = f.get(target);
            if (prev == null) {
                f.set(target, sDummy);
                XposedBridge.log(TAG + "[" + from + "]: PATCHED mFingerprintManager "
                        + "on " + target.getClass().getSimpleName());
            } else {
                XposedBridge.log(TAG + "[" + from + "]: already set ("
                        + prev.getClass().getSimpleName() + ")");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + from + "]: " + t);
        }
    }

    private static Field findField(Class<?> c, String name) {
        while (c != null && c != Object.class) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
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