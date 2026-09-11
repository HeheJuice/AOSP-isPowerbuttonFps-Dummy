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
    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11";
    private static final String LAMBDA_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11$$ExternalSyntheticLambda0";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": SystemUI loaded, installing diagnostic fix...");

        try {
            Class<?> lambda = XposedHelpers.findClass(LAMBDA_CLASS, lpparam.classLoader);

            XposedBridge.hookAllMethods(lambda, "run", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object self = param.thisObject;

                    // 1. Enumerate the lambda's captured fields
                    dumpFields("lambda", self, true);

                    // 2. Patch every FingerprintManager field we can find,
                    //    walking the lambda's captures and their outers recursively.
                    deepPatch(self, 0);
                }
            });

            XposedBridge.log(TAG + ": Lambda hook installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Lambda hook failed: " + t);
        }

        // Also try the observer class in case it has its own FPS field
        try {
            Class<?> observer = XposedHelpers.findClass(OBSERVER_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(observer, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    dumpFields("observer-ctor", param.thisObject, true);
                    deepPatch(param.thisObject, 0);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Observer ctor hook failed: " + t);
        }

        XposedBridge.log(TAG + ": Done.");
    }

    /**
     * Recursively walk captures / outer references up to depth 3, patching
     * any null FingerprintManager-typed field and dumping all fields seen.
     */
    private static void deepPatch(Object obj, int depth) {
        if (obj == null || depth > 3) return;
        Class<?> c = obj.getClass();

        // Only walk our own packages
        String pkg = c.getName();
        if (!pkg.startsWith("com.android.systemui")
                && !pkg.startsWith("com.android.internal")) {
            return;
        }

        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);

                    // Dump every field so we can see what the lambda holds
                    XposedBridge.log(TAG + ": " + indent(depth) + c.getSimpleName()
                            + "." + f.getName() + " : " + f.getType().getSimpleName()
                            + " = " + (val == null ? "null" : val.getClass().getSimpleName()));

                    // Patch null FingerprintManager
                    if (FingerprintManager.class.isAssignableFrom(f.getType()) && val == null) {
                        f.set(obj, createDummy());
                        XposedBridge.log(TAG + ": " + indent(depth) + ">> PATCHED "
                                + f.getName());
                    }

                    // Recurse into captured objects of interest
                    if (val != null && (f.getName().startsWith("arg$")
                            || f.getName().equals("this$0")
                            || f.getName().contains("fingerprint")
                            || f.getName().contains("Fingerprint"))) {
                        deepPatch(val, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static String indent(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    private static void dumpFields(String tag, Object obj, boolean recurse) {
        if (obj == null) return;
        XposedBridge.log(TAG + ": --- dump [" + tag + "] " + obj.getClass().getName());
        Class<?> c = obj.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    XposedBridge.log(TAG + ":     " + c.getSimpleName() + "."
                            + f.getName() + " : " + f.getType().getSimpleName()
                            + " = " + (val == null ? "null" : "instance"));
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
    }

    private static Object createDummy() {
        return Proxy.newProxyInstance(
                XposedHook.class.getClassLoader(),
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