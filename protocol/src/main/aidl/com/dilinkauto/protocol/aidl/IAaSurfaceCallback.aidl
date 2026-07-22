package com.dilinkauto.protocol.aidl;

interface IAaSurfaceCallback {
    oneway void onSurface(in android.view.Surface surface, int width, int height, int dpi);
    oneway void onSurfaceDestroyed();
}
