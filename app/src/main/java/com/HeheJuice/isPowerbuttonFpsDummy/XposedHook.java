package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix-V5";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String CENTRAL_SURFACES_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl";
    private static final String OBSERVER_CLASS =
            "com.android.systemui.statusbar.phone.CentralSurfacesImpl$11";
    private static final String FINGERPRINT_CLASS =
            "android.hardware.fingerprint.FingerprintManager";

    private static Object sDummy;
    private static Object sProviderProxy;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": START");

        // 1. Bare FingerprintManager instance via Unsafe (no constructor)
        sDummy = unsafeAllocate(FINGERPRINT_CLASS, lpparam.classLoader);
        XposedBridge.log(TAG + ": dummy=" + sDummy);
        if (sDummy == null) return;

        // 2. Provider proxy whose get() returns the dummy
        try {
            Class<?> providerInterface = Class.forName(
                    "javax.inject.Provider", false, lpparam.classLoader);
            sProviderProxy = Proxy.newProxyInstance(
                    lpparam.classLoader,
                    new Class<?>[]{providerInterface},
                    (proxy, method, args) -> {
                        String n = method.getName();
                        if ("get".equals(n)) return sDummy;
                        if ("toString".equals(n)) return "FPSProviderDummy";
                        if ("hashCode".equals(n)) return System.identityHashCode(proxy);
                        if ("equals".equals(n)) return proxy == args[0];
                        return null;
                    });
            XposedBridge.log(TAG + ": providerProxy=" + sProviderProxy);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": provider proxy failed: " + t);
            return;
        }

        // 3. Hook CentralSurfacesImpl ctor + onStartedWakingUp
        try {
            Class<?> cs = XposedHelpers.findClass(CENTRAL_SURFACES_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(cs, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    patch(p.thisObject, "ctor");
                }
            });
            XposedBridge.hookAllMethods(cs, "onStartedWakingUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    patch(p.thisObject, "onStartedWakingUp");
                }
            });
            XposedBridge.log(TAG + ": CS hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": CS hook failed: " + t);
        }

        // 4. Hook observer ($11) — this is the exact class in the stack trace
        try {
            Class<?> ob = XposedHelpers.findClass(OBSERVER_CLASS, lpparam.classLoader);
            XposedBridge.hookAllConstructors(ob, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    patch(getField(p.thisObject, "this$0"), "observer-ctor");
                }
            });
            XposedBridge.hookAllMethods(ob, "onStartedWakingUp", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    patch(getField(p.thisObject, "this$0"), "observer-onStartedWakingUp");
                }
            });
            XposedBridge.log(TAG + ": observer hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": observer hook failed: " + t);
        }

        XposedBridge.log(TAG + ": READY");
    }

    private static void patch(Object target, String from) {
        if (target == null) return;
        try {
            Field f = findField(target.getClass(), "mFingerprintManager");
            if (f == null) {
                XposedBridge.log(TAG + "[" + from + "]: field not found");
                return;
            }
            f.setAccessible(true);
            Object cur = f.get(target);
            Class<?> ft = f.getType();

            if (ft.isInstance(sProviderProxy)) {
                if (cur == null || !isDummyProvider(cur)) {
                    f.set(target, sProviderProxy);
                    XposedBridge.log(TAG + "[" + from + "]: set Provider proxy");
                }
            } else if (ft.isInstance(sDummy)) {
                if (cur == null) {
                    f.set(target, sDummy);
                    XposedBridge.log(TAG + "[" + from + "]: set direct dummy");
                }
            } else {
                XposedBridge.log(TAG + "[" + from + "]: unknown field type " + ft.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "[" + from + "]: " + t);
        }
    }

    private static boolean isDummyProvider(Object provider) {
        try {
            Method g = provider.getClass().getMethod("get");
            return g.invoke(provider) == sDummy;
        } catch (Throwable t) { return false; }
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