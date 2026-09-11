# FPSDummyFix

Fixes a SystemUI crash on Android 14 devices that have **no fingerprint sensor** while the system still requires 

## The Problem

On a device without fingerprint hardware, `FingerprintManager` is `null`. When the screen wakes, SystemUI calls:

```java
mFingerprintManager.isPowerbuttonFps();
```

...and crashes with a `NullPointerException`, killing `com.android.systemui`.

## The Fix

Two hooks, working together:

1. **Framework hooks** — every sensor-query method on `FingerprintManager` is replaced with a safe default:
   - `getSensorPropertiesInternal()` → empty list
   - `getFirstFingerprintSensor()` → `null`
   - `isPowerbuttonFps()` → `false`

2. **Crash-catcher hook** — the NPE propagates through `NotificationShadeWindowControllerImpl.batchApplyWindowLayoutParams()`. We hook that method and clear the throwable, so SystemUI keeps running.

## Scope

- **Package:** `com.android.systemui`
- **SystemUI Restart:** required after enabling