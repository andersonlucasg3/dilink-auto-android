package com.dilinkauto.protocol.aidl;

import com.dilinkauto.protocol.aidl.IAaAppCallback;

interface IAaDaemon {
    // ── VD1 (primary) — shortcuts for backward compatibility ──
    oneway void setSurface(in android.view.Surface surface, int width, int height, int dpi);
    oneway void surfaceDestroyed();
    oneway void touch(int action, float xNorm, float yNorm);
    oneway void goBack();
    oneway void goHome();
    oneway void launchApp(String packageName);
    oneway void registerAppCallback(IAaAppCallback cb);
    oneway void shutdown();

    // ── VD2 (secondary) — multi-display API (FASE 1) ──
    int createSecondaryDisplay(in android.view.Surface surface, int width, int height, int dpi);
    oneway void releaseSecondaryDisplay(int displayId);
    oneway void launchAppOnDisplay(int displayId, String packageName);
    oneway void goHomeOnDisplay(int displayId);
    oneway void goBackOnDisplay(int displayId);
}
