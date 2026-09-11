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
    private static final String LAMBDA_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11$$ExternalSyntheticLambda0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": SystemUI loaded, installing fix...");

        try {
            Class<?> lambdaClass = XposedHelpers.findClass(LAMBDA_CLASS, lpparam.classLoader);

            XposedBridge.hookAllMethods(lambdaClass, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Walk: lambda -> f$0 (observer) -> this$0 (CentralSurfacesImpl)
                    // and force mFingerprintManager on CentralSurfacesImpl to be a dummy.
                    Object lambda = param.thisObject;
                    Object observer = getField(lambda, "f$0");
                    if (observer == null) {
                        // Fallback for non-R8 builds
                        observer = getField(lambda, "arg$1");
                    }
                    if (observer == null) {
                        XposedBridge.log(TAG + ": Could not find observer field on lambda.");
                        return;
                    }

                    Object centralSurfaces = getField(observer, "this$0");
                    if (centralSurfaces == null) {
                        XposedBridge.log(TAG + ": Could not find this$0 on observer.");
                        return;
                    }

                    boolean patched = forcePatchFingerprintManager(
                            centralSurfaces, "CentralSurfacesImpl");
                    if (!patched) {
                        // As a last resort, walk all fields of the observer's outer
                        // class recursively looking for a FingerprintManager field.
                        walkAndPatch(centralSurfaces, 0);
                    }
                }
            });

            XposedBridge.log(TAG + ": Lambda hook installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Lambda hook failed: " + t);
        }

        XposedBridge.log(TAG + ": Done.");
    }

    private static boolean forcePatchFingerprintManager(Object target, String origin) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField("mFingerprintManager");
                f.setAccessible(true);
                Object current = f.get(target);
                Object dummy = createDummy(target.getClass().getClassLoader());
                f.set(target, dummy);
                XposedBridge.log(TAG + ": [" + origin + "] Force-set mFingerprintManager (was "
                        + (current == null ? "null" : current.getClass().getSimpleName()) + ")");
                return true;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": [" + origin + "] Force-set failed: " + t);
                return false;
            }
        }
        return false;
    }

    private static void walkAndPatch(Object obj, int depth) {
        if (obj == null || depth > 4) return;
        Class<?> c = obj.getClass();
        if (!c.getName().startsWith("com.android.systemui")
                && !c.getName().startsWith("com.android.internal")) {
            return;
        }
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (FingerprintManager.class.isAssignableFrom(f.getType())) {
                        if (val == null) {
                            f.set(obj, createDummy(c.getClassLoader()));
                            XposedBridge.log(TAG + ": [walk d" + depth + "] Patched "
                                    + c.getSimpleName() + "." + f.getName());
                        }
                    } else if (val != null && isInteresting(f.getName())) {
                        walkAndPatch(val, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static boolean isInteresting(String name) {
        return name.startsWith("arg$") || name.startsWith("f$")
                || name.equals("this$0");
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

    private static Object createDummy(ClassLoader cl) {
        return Proxy.newProxyInstance(
                cl,
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