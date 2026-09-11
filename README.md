# AOSP-isPowerbuttonFps-Dummy

修復 Android 14 裝置上 SystemUI 的崩潰問題：這些裝置**沒有指紋感應器**，但系統仍然要求 

## 本模块是針對已 Root 並安装了 `AOSP 14 2023-10-05` 版本 `Framework`和`SystemUI`的 Famue BF66 設計
其他 Android 14 + 設备也可使用

## 問題

在沒有指紋硬體的裝置上，`FingerprintManager` 為 `null`。當螢幕喚醒時，SystemUI 會呼叫：

```java
mFingerprintManager.isPowerbuttonFps();
```

……並因 `NullPointerException` 崩潰，導致 `com.android.systemui` 被終止。

## 修復方式

兩個 hook 協同運作：

1. **框架 hook** — `FingerprintManager` 上每個感應器查詢方法都會被替換為安全預設值：
   - `getSensorPropertiesInternal()` → 空清單
   - `getFirstFingerprintSensor()` → `null`
   - `isPowerbuttonFps()` → `false`

2. **崩潰捕捉 hook** — 這個 NPE 會經由 `NotificationShadeWindowControllerImpl.batchApplyWindowLayoutParams()` 傳播。我們 hook 該方法並清除 throwable，讓 SystemUI 繼續運作。

## 範圍

- **套件：** `com.android.systemui`
- **SystemUI 重新啟動：** 啟用後必須重新啟動