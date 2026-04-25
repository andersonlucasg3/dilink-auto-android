package com.dilinkauto.vdserver;

import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Fake Context for use in app_process.
 * Mimics com.android.shell to get shell-level permissions.
 * Based on scrcpy's FakeContext + Workarounds (Apache 2.0 license).
 *
 * Uses ActivityThread to obtain a real system Context as the base,
 * so getSystemService() returns real service instances (UserManager, etc.).
 */
public class FakeContext extends ContextWrapper {

    private static final String PACKAGE_NAME = "com.android.shell";

    private static final Object sActivityThread;
    private static final Class<?> sActivityThreadClass;

    static {
        try {
            // Ensure a Looper exists — ActivityThread and system services may need one
            if (android.os.Looper.getMainLooper() == null) {
                android.os.Looper.prepareMainLooper();
            }

            sActivityThreadClass = Class.forName("android.app.ActivityThread");

            // Create an ActivityThread instance
            Constructor<?> ctor = sActivityThreadClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            sActivityThread = ctor.newInstance();

            // Set it as the current activity thread
            Field sCurrentField = sActivityThreadClass.getDeclaredField("sCurrentActivityThread");
            sCurrentField.setAccessible(true);
            sCurrentField.set(null, sActivityThread);

            // Mark as system thread so getSystemContext() works
            Field mSystemThreadField = sActivityThreadClass.getDeclaredField("mSystemThread");
            mSystemThreadField.setAccessible(true);
            mSystemThreadField.setBoolean(sActivityThread, true);

            // On Android 12+, fill ConfigurationController to avoid NPE in DisplayManagerGlobal
            if (Build.VERSION.SDK_INT >= 31) {
                fillConfigurationController();
            }

            // Fill app info so framework code that checks package name works
            fillAppInfo();

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize ActivityThread for FakeContext", e);
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> configControllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> activityThreadInternalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = configControllerClass.getDeclaredConstructor(activityThreadInternalClass);
            ctor.setAccessible(true);
            Object configController = ctor.newInstance(sActivityThread);

            Field field = sActivityThreadClass.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(sActivityThread, configController);
        } catch (Throwable t) {
            System.err.println("[FakeContext] fillConfigurationController failed (non-fatal): " + t.getMessage());
        }
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor = appBindDataClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object appBindData = ctor.newInstance();

            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = PACKAGE_NAME;

            Field appInfoField = appBindDataClass.getDeclaredField("appInfo");
            appInfoField.setAccessible(true);
            appInfoField.set(appBindData, appInfo);

            Field mBoundAppField = sActivityThreadClass.getDeclaredField("mBoundApplication");
            mBoundAppField.setAccessible(true);
            mBoundAppField.set(sActivityThread, appBindData);
        } catch (Throwable t) {
            System.err.println("[FakeContext] fillAppInfo failed (non-fatal): " + t.getMessage());
        }
    }

    private static Context getSystemContext() {
        try {
            Method method = sActivityThreadClass.getDeclaredMethod("getSystemContext");
            return (Context) method.invoke(sActivityThread);
        } catch (Exception e) {
            System.err.println("[FakeContext] getSystemContext failed: " + e.getMessage());
            return null;
        }
    }

    private static final FakeContext INSTANCE = new FakeContext();

    public static FakeContext get() {
        return INSTANCE;
    }

    private FakeContext() {
        super(getSystemContext());
    }

    @Override
    public String getPackageName() {
        return PACKAGE_NAME;
    }

    @Override
    public String getOpPackageName() {
        return PACKAGE_NAME;
    }

    @TargetApi(31)
    @Override
    public AttributionSource getAttributionSource() {
        AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
        builder.setPackageName(PACKAGE_NAME);
        return builder.build();
    }

    @Override
    public Context getApplicationContext() {
        return this;
    }

    @Override
    public Context createPackageContext(String packageName, int flags) {
        return this;
    }

    @Override
    public ContentResolver getContentResolver() {
        // Return the system context's content resolver if available
        Context base = getBaseContext();
        if (base != null) {
            return base.getContentResolver();
        }
        return null;
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        return PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public Object getSystemService(String name) {
        // Delegate to the real system context — this returns real service instances
        // (UserManager, DisplayManager, WindowManager, etc.)
        return super.getSystemService(name);
    }

    @Override
    public String getSystemServiceName(Class<?> serviceClass) {
        if (serviceClass == DisplayManager.class) {
            return Context.DISPLAY_SERVICE;
        }
        if (serviceClass == android.view.WindowManager.class) {
            return Context.WINDOW_SERVICE;
        }
        if (serviceClass == android.os.UserManager.class) {
            return Context.USER_SERVICE;
        }
        // Delegate to base context for anything else
        Context base = getBaseContext();
        if (base != null) {
            return base.getSystemServiceName(serviceClass);
        }
        return null;
    }
}
