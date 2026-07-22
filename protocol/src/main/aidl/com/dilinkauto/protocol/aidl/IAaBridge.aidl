package com.dilinkauto.protocol.aidl;

import com.dilinkauto.protocol.aidl.IAaSurfaceCallback;

interface IAaBridge {
    String ping(String msg);
    oneway void registerSurfaceCallback(IAaSurfaceCallback cb);
}
