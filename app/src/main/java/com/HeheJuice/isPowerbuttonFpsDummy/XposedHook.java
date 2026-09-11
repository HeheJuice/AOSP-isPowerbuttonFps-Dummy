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

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": SystemUI loaded, applying fix...");

        try {
            Class<?> centralSurfaces = XposedHelpers.findClass(
                    CENTRAL_SURFACES_CLASS, lpparam.classLoader);

            // 1. Replace the mFingerprintManager field with a dummy proxy
            //    as soon as CentralSurfacesImpl is constructed.
            XposedBridge.hookAllConstructors(centralSurfaces, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object instance = param.thisObject;
                        Field fpsField = findField(centralSurfaces, "mFingerprintManager");
                        if (fpsField == null) {
                            XposedBridge.log(TAG + ": mFingerprintManager field not found.");
                            return;
                        }
                        fpsField.setAccessible(true);
                        Object current = fpsField.get(instance);

                        if (current == null) {
                            Object dummy = createDummyFingerprintManager(lpparam.classLoader);
                            fpsField.set(instance, dummy);
                            XposedBridge.log(TAG + ": Injected dummy FingerprintManager (null).");
                        } else {
                            XposedBridge.log(TAG + ": FingerprintManager already present, no patch needed.");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Constructor hook failed: " + t);
                    }
                }
            });

            // 2. Belt-and-braces: if the field ever becomes null later, patch it on the fly
            //    during onStartedWakingUp (the method where the crash happens).
            XposedBridge.hookAllMethods(centralSurfaces, "onStartedWakingUp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object instance = param.thisObject;
                        Field fpsField = findField(centralSurfaces, "mFingerprintManager");
                        if (fpsField == null) return;
                        fpsField.setAccessible(true);
                        if (fpsField.get(instance) == null) {
                            fpsField.set(instance, createDummyFingerprintManager(lpparam.classLoader));
                            XposedBridge.log(TAG + ": Patched null FingerprintManager in onStartedWakingUp.");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": onStartedWakingUp hook failed: " + t);
                    }
                }
            });

            XposedBridge.log(TAG + ": Hooks installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to install hooks: " + t);
        }
    }

    /**
     * Walk the class hierarchy to find a field (handles inherited fields).
     */
    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                Field f = current.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Creates a dynamic proxy that implements FingerprintManager and returns
     * false for isPowerbuttonFps(), null for everything else.
     */
    private static Object createDummyFingerprintManager(ClassLoader classLoader) {
        return Proxy.newProxyInstance(
                classLoader,
                new Class<?>[]{ FingerprintManager.class },
                (proxy, method, args) -> {
                    String name = method.getName();
                    Class<?> ret = method.getReturnType();

                    if ("isPowerbuttonFps".equals(name)) {
                        return false;  // safe: device has no FPS
                    }
                    if ("isHardwareDetected".equals(name)) {
                        return false;
                    }
                    if ("hasEnrolledFingerprints".equals(name)) {
                        return false;
                    }
                    if ("toString".equals(name)) {
                        return "FingerprintManagerDummy";
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(name)) {
                        return proxy == args[0];
                    }

                    // Return primitive-safe defaults to avoid unboxing NPEs.
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