package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";

    // The exact lambda that crashes (from the stack trace)
    private static final String CRASH_LAMBDA =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11$$ExternalSyntheticLambda0";

    // Fallback: the outer class field name(s) to patch
    private static final String CENTRAL_SURFACES_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": SystemUI loaded, installing fix...");

        // 1. PRIMARY FIX: no-op the crashing lambda entirely.
        //    The lambda only runs FPS-specific window layout logic, which is
        //    meaningless on a device with no FPS sensor.
        try {
            Class<?> lambdaClass = XposedHelpers.findClass(
                    CRASH_LAMBDA, lpparam.classLoader);

            XposedBridge.hookAllMethods(lambdaClass, "run",
                    XC_MethodReplacement.DO_NOTHING);

            XposedBridge.log(TAG + ": Lambda neutralized -> " + CRASH_LAMBDA);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Could not hook lambda (will rely on field patch): " + t);
        }

        // 2. SECONDARY FIX: patch every FingerprintManager-typed field on
        //    CentralSurfacesImpl with a proxy that returns false for
        //    isPowerbuttonFps(), in case some other code path hits the NPE.
        try {
            Class<?> centralSurfaces = XposedHelpers.findClass(
                    CENTRAL_SURFACES_CLASS, lpparam.classLoader);

            XposedBridge.hookAllConstructors(centralSurfaces, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    patchAllFingerprintFields(param.thisObject, centralSurfaces,
                            lpparam.classLoader, "constructor");
                }
            });

            XposedBridge.hookAllMethods(centralSurfaces, "onStartedWakingUp",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    patchAllFingerprintFields(param.thisObject, centralSurfaces,
                            lpparam.classLoader, "onStartedWakingUp");
                }
            });

            XposedBridge.log(TAG + ": Field-patch hooks installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Field-patch hooks failed: " + t);
        }

        XposedBridge.log(TAG + ": Done.");
    }

    private static void patchAllFingerprintFields(Object instance, Class<?> clazz,
                                                  ClassLoader cl, String origin) {
        Class<?> current = clazz;
        while (current != null) {
            for (Field f : current.getDeclaredFields()) {
                if (!FingerprintManager.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(instance);
                    if (val == null) {
                        f.set(instance, createDummyFingerprintManager(cl));
                        XposedBridge.log(TAG + ": [" + origin + "] Patched "
                                + current.getSimpleName() + "." + f.getName());
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": [" + origin + "] Patch failed for "
                            + f.getName() + ": " + t);
                }
            }
            current = current.getSuperclass();
        }
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