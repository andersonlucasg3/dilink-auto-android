package com.dilinkauto.protocol.aidl;

import com.dilinkauto.protocol.aidl.IAaAppCallback;

interface IAaDaemon {
    oneway void setSurface(in android.view.Surface surface, int width, int height, int dpi);
    oneway void surfaceDestroyed();
    oneway void touch(int action, float xNorm, float yNorm);
    oneway void goBack();
    oneway void goHome();
    oneway void launchApp(String packageName);
    oneway void registerAppCallback(IAaAppCallback cb);
    oneway void shutdown();
}
