package com.dilinkauto.protocol.aidl;

interface IAaAppCallback {
    oneway void onDisplayReady(int displayId);
    oneway void onSecondaryDisplayReady(int displayId);
    oneway void onError(String message);
    oneway void onDisplayStackEmpty(int displayId);
}
