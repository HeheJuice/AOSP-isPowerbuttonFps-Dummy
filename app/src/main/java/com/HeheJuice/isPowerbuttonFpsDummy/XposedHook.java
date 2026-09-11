package com.HeheJuice.isPowerbuttonFpsDummy;

import android.hardware.fingerprint.FingerprintManager;

import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class XposedHook implements IXposedHookLoadPackage {

    private static final String TAG = "FPSDummyFix-A14";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String FINGERPRINT_CLASS =
            "android.hardware.fingerprint.FingerprintManager";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) return;
        XposedBridge.log(TAG + ": START (Android 14, no FPS)");

        try {
            Class<?> fm = XposedHelpers.findClass(FINGERPRINT_CLASS, lpparam.classLoader);

            // This is the leaf that threw the NPE.
            // AOSP 14: public List<FingerprintSensorPropertiesInternal> getSensorPropertiesInternal()
            XposedHelpers.findAndHookMethod(
                    fm,
                    "getSensorPropertiesInternal",
                    XC_MethodReplacement.returnConstant(Collections.emptyList())
            );

            // Some AOSP 14 builds also have an overload with opPackageName.
            // Hook it too if it exists.
            try {
                XposedHelpers.findAndHookMethod(
                        fm,
                        "getSensorPropertiesInternal",
                        String.class,
                        XC_MethodReplacement.returnConstant(Collections.emptyList())
                );
            } catch (NoSuchMethodError ignored) {}

            // With an empty list, AOSP returns null here. Returning null directly
            // short-circuits isPowerbuttonFps() without any internal calls.
            XposedHelpers.findAndHookMethod(
                    fm,
                    "getFirstFingerprintSensor",
                    XC_MethodReplacement.returnConstant(null)
            );

            // The actual decision SystemUI makes. false = "not a power-button FPS".
            XposedHelpers.findAndHookMethod(
                    fm,
                    "isPowerbuttonFps",
                    XC_MethodReplacement.returnConstant(false)
            );

            XposedBridge.log(TAG + ": FingerprintManager methods neutralised");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed: " + t);
        }

        XposedBridge.log(TAG + ": READY");
    }
}