package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    private static final String CENTRAL_SURFACES_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";

    // The anonymous observer + its lambda from the stack trace
    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11";
    private static final String LAMBDA_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11$$ExternalSyntheticLambda0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": SystemUI loaded, installing fix...");

        // 1. Patch fields on CentralSurfacesImpl (safe, harmless)
        try {
            Class<?> centralSurfaces = XposedHelpers.findClass(
                    CENTRAL_SURFACES_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(centralSurfaces, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    patchFingerprintFields(param.thisObject, "CentralSurfacesImpl-ctor");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CentralSurfacesImpl hook failed: " + t);
        }

        // 2. Patch fields on the anonymous observer class (this is where
        //    mFingerprintManager actually lives on some builds)
        try {
            Class<?> observer = XposedHelpers.findClass(
                    OBSERVER_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(observer, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    patchFingerprintFields(param.thisObject, "Observer-ctor");
                    // Also patch outer class reached via this$0
                    patchOuterReference(param.thisObject, "Observer-ctor-outer");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Observer hook skipped: " + t);
        }

        // 3. THE REAL FIX: hook the lambda's run() and patch its captured
        //    fields BEFORE it executes, then let it run normally.
        try {
            Class<?> lambda = XposedHelpers.findClass(
                    LAMBDA_CLASS, lpparam.classLoader);

            XposedBridge.hookAllMethods(lambda, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object lambdaInstance = param.thisObject;
                    Class<?> c = lambdaInstance.getClass();

                    // Walk the lambda's captured fields (arg$1, arg$2, ...)
                    while (c != null && c != Object.class) {
                        for (Field f : c.getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                Object captured = f.get(lambdaInstance);
                                if (captured == null) continue;

                                // Patch FingerprintManager fields on the captured object
                                patchFingerprintFields(captured,
                                        "lambda-capture:" + f.getName());

                                // And one level deeper, to reach the outer
                                // CentralSurfacesImpl$11 -> CentralSurfacesImpl
                                patchOuterReference(captured,
                                        "lambda-capture-outer:" + f.getName());
                            } catch (Throwable ignored) {}
                        }
                        c = c.getSuperclass();
                    }
                }
            });

            XposedBridge.log(TAG + ": Lambda hook installed (runs normally, fields pre-patched).");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Lambda hook failed: " + t);
        }

        XposedBridge.log(TAG + ": Done.");
    }

    /**
     * Finds all FingerprintManager-typed fields on the given object (walking
     * the class hierarchy) and replaces null ones with a safe proxy.
     */
    private static void patchFingerprintFields(Object instance, String origin) {
        if (instance == null) return;
        Class<?> c = instance.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!FingerprintManager.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(instance) == null) {
                        f.set(instance, createDummyFingerprintManager(
                                instance.getClass().getClassLoader()));
                        XposedBridge.log(TAG + ": [" + origin + "] Patched "
                                + c.getSimpleName() + "." + f.getName());
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    /**
     * If the object has a synthetic this$0 field, patch FingerprintManager
     * fields on the outer object too. Covers CentralSurfacesImpl$11 -> CentralSurfacesImpl.
     */
    private static void patchOuterReference(Object instance, String origin) {
        if (instance == null) return;
        try {
            Field outer = instance.getClass().getDeclaredField("this$0");
            outer.setAccessible(true);
            Object outerObj = outer.get(instance);
            if (outerObj != null) {
                patchFingerprintFields(outerObj, origin);
            }
        } catch (NoSuchFieldException ignored) {
            // Not an anonymous inner class; nothing to do
        } catch (Throwable ignored) {}
    }

    private static Object createDummyFingerprintManager(ClassLoader classLoader) {
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{ FingerprintManager.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    Class<?> ret = method.getReturnType();

                    if ("isPowerbuttonFps".equals(name)) return false;
                    if ("isHardwareDetected".equals(name)) return false;
                    if ("hasEnrolledFingerprints".equals(name)) return false;
                    if ("toString".equals(name)) return "FingerprintManagerDummy";
                    if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                    if ("equals".equals(name)) return proxy == args[0];

                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == short.class) return (short) 0;
                    if (ret == byte.class) return (byte) 0;
                    if (ret == char.class) return (char) 0;
                    if (ret == float.class) return 0f;
                    if (ret == double.class) return 0d;
                    return null;
                }
        );
    }
}