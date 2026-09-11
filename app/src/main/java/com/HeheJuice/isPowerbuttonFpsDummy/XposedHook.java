package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String FINGERPRINT_CLASS =
            "android.hardware.fingerprint.FingerprintManager";
    private static final String LAMBDA_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11$$ExternalSyntheticLambda0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": SystemUI loaded, installing fix...");

        // 1. Force isPowerbuttonFps() on FingerprintManager to always return false,
        //    regardless of the instance state (null fields, garbage, etc.).
        try {
            XposedHelpers.findAndHookMethod(FINGERPRINT_CLASS, lpparam.classLoader,
                    "isPowerbuttonFps", XC_MethodReplacement.returnConstant(false));
            XposedBridge.log(TAG + ": Hooked FingerprintManager.isPowerbuttonFps -> false");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": isPowerbuttonFps hook failed: " + t);
        }

        // 2. Hook the crash lambda and ensure mFingerprintManager is non-null
        //    by allocating a bare instance via Unsafe.
        try {
            Class<?> lambdaClass = XposedHelpers.findClass(LAMBDA_CLASS, lpparam.classLoader);

            XposedBridge.hookAllMethods(lambdaClass, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object lambda = param.thisObject;
                    Object observer = getField(lambda, "f$0");
                    if (observer == null) observer = getField(lambda, "arg$1");
                    if (observer == null) return;

                    Object centralSurfaces = getField(observer, "this$0");
                    if (centralSurfaces == null) return;

                    forcePatchFingerprintManager(centralSurfaces, lpparam.classLoader);
                }
            });

            XposedBridge.log(TAG + ": Lambda hook installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Lambda hook failed: " + t);
        }

        XposedBridge.log(TAG + ": Done.");
    }

    private static void forcePatchFingerprintManager(Object target, ClassLoader cl) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("mFingerprintManager");
                f.setAccessible(true);
                Object current = f.get(target);
                if (current == null) {
                    Object dummy = allocateWithoutConstructor(FINGERPRINT_CLASS, cl);
                    if (dummy != null) {
                        f.set(target, dummy);
                        XposedBridge.log(TAG + ": [" + c.getSimpleName()
                                + "] Set mFingerprintManager to Unsafe-allocated dummy.");
                    }
                }
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [" + c.getSimpleName()
                        + "] Patch failed: " + t);
                return;
            }
        }
    }

    /**
     * Allocate an instance of a class without invoking any constructor.
     * Works even for final classes and classes with private constructors.
     */
    private static Object allocateWithoutConstructor(String className, ClassLoader cl) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);

            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);

            Class<?> targetClass = Class.forName(className, false, cl);
            return allocateInstance.invoke(unsafe, targetClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Unsafe allocation failed: " + t);
            return null;
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }
}