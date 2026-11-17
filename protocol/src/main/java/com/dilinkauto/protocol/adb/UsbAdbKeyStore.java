package com.dilinkauto.protocol.adb;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.util.function.Consumer;

/**
 * Manages ADB RSA key pair storage, discovery, migration, and generation for USB ADB connections.
 */
public class UsbAdbKeyStore {
    private static final String TAG = "UsbAdbKeyStore";
    public static final String KEY_FILE = "usb_adb_key";

    public static class KeyResult {
        private final KeyPair keyPair;
        private final String diagInfo;

        public KeyResult(KeyPair keyPair, String diagInfo) {
            this.keyPair = keyPair;
            this.diagInfo = diagInfo;
        }

        public KeyPair getKeyPair() {
            return keyPair;
        }

        public String getDiagInfo() {
            return diagInfo;
        }
    }

    public static KeyResult getOrCreateKeyPair(Context context, Consumer<String> logger, Consumer<String> warnLogger) {
        Consumer<String> log = logger != null ? logger : msg -> Log.i(TAG, msg);
        Consumer<String> logW = warnLogger != null ? warnLogger : msg -> Log.w(TAG, msg);

        // Priority order for key storage (most private first):
        // 1. getFilesDir — app-private, not readable by co-installed apps
        // 2. getExternalFilesDir — app-private on modern Android, survives updates
        // 3. /sdcard/DiLinkAuto/ — WORLD-READABLE shared storage. This key
        //    authenticates the car to the phone's ADB daemon and the phone keeps
        //    trusting it after "always allow", so a co-installed app holding
        //    READ_EXTERNAL_STORAGE could steal it and gain shell-UID access to
        //    the phone. Only used as an explicit opt-in for cross-device key
        //    portability (an operator who placed a key there manually), never
        //    chosen automatically.
        File extDir = context.getExternalFilesDir(null);
        File sdcardDir = new File(Environment.getExternalStorageDirectory(), "DiLinkAuto");
        log.accept("Key storage check: filesDir=" + (context.getFilesDir() != null ? context.getFilesDir().getAbsolutePath() : "null")
            + " extFilesDir=" + (extDir != null ? extDir.getAbsolutePath() : "null")
            + " canWrite=" + (extDir != null && extDir.canWrite())
            + " sdcard=" + sdcardDir.getAbsolutePath() + " exists=" + sdcardDir.exists());

        File keyDir = context.getFilesDir();
        if (keyDir == null || !keyDir.canWrite()) {
            keyDir = extDir;
            if (keyDir == null || !keyDir.canWrite()) {
                logW.accept("filesDir and externalFilesDir unavailable");
                keyDir = null;
            } else {
                log.accept("Using externalFilesDir for key storage: " + keyDir.getAbsolutePath());
            }
        } else {
            log.accept("Using filesDir for key storage: " + keyDir.getAbsolutePath());
        }
        if (keyDir == null) {
            throw new RuntimeException("No writable private storage for the ADB key pair");
        }
        keyDir.mkdirs();
        File privFile = new File(keyDir, KEY_FILE);
        File pubFile = new File(keyDir, KEY_FILE + ".pub");

        // Migration: check all other locations for existing keys
        if (!privFile.exists()) {
            log.accept("Key not found at " + privFile.getAbsolutePath() + " — searching other locations");
            File fallbackDir = context.getExternalFilesDir(null) != null ? context.getExternalFilesDir(null) : context.getFilesDir();
            File[][] searchDirs = {
                {new File(sdcardDir, KEY_FILE), new File(sdcardDir, KEY_FILE + ".pub")},
                {new File(fallbackDir, KEY_FILE), new File(fallbackDir, KEY_FILE + ".pub")},
                {new File(context.getFilesDir(), KEY_FILE), new File(context.getFilesDir(), KEY_FILE + ".pub")}
            };
            for (File[] pair : searchDirs) {
                if (pair[0].exists() && pair[1].exists() && !pair[0].equals(privFile)) {
                    try {
                        copyFile(pair[0], privFile);
                        copyFile(pair[1], pubFile);
                        log.accept("Migrated ADB key from " + pair[0].getParent() + " to " + keyDir.getAbsolutePath());
                        break;
                    } catch (Exception e) {
                        logW.accept("Key migration failed: " + e.getMessage());
                    }
                }
            }
        }

        log.accept("Key path: " + privFile.getAbsolutePath() + " exists=" + privFile.exists());

        if (privFile.exists() && pubFile.exists()) {
            try {
                byte[] privBytes = readFile(privFile);
                byte[] pubBytes = readFile(pubFile);
                // Keypair load/generate primitives live in AdbCrypto (shared with
                // TcpAdbConnection); only the storage policy above stays USB-specific.
                KeyPair kp = AdbCrypto.loadKeyPair(privBytes, pubBytes);
                String fp = AdbCrypto.fingerprint(kp.getPublic().getEncoded());
                String diag = "LOADED path=" + privFile.getAbsolutePath() + " fp=" + fp;
                log.accept("Loaded existing ADB key pair (fingerprint=" + fp + ")");
                return new KeyResult(kp, diag);
            } catch (Exception e) {
                logW.accept("Failed to load key pair, generating new: " + e.getMessage());
            }
        }

        try {
            KeyPair kp = AdbCrypto.generateKeyPair();
            writeFile(privFile, kp.getPrivate().getEncoded());
            writeFile(pubFile, kp.getPublic().getEncoded());
            String fp = AdbCrypto.fingerprint(kp.getPublic().getEncoded());
            String diag = "GENERATED path=" + privFile.getAbsolutePath() + " fp=" + fp;
            log.accept("Generated NEW ADB key pair (fingerprint=" + fp + ") — phone will ask for auth");
            return new KeyResult(kp, diag);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ADB key pair", e);
        }
    }

    public static byte[] readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = fis.read(data, offset, data.length - offset);
                if (n == -1) break;
                offset += n;
            }
            return data;
        }
    }

    public static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
