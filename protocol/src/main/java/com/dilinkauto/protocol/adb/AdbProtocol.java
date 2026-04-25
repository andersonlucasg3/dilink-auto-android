package com.dilinkauto.protocol.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * ADB protocol constants and message serialization.
 * Based on AOSP system/adb/protocol.h and AdbTest sample.
 *
 * Wire format (little-endian):
 * ┌──────────────────────────────────────────────────┐
 * │ command    (4 bytes)  — A_CNXN, A_AUTH, etc.     │
 * │ arg0       (4 bytes)  — first argument            │
 * │ arg1       (4 bytes)  — second argument           │
 * │ data_len   (4 bytes)  — payload length            │
 * │ data_crc   (4 bytes)  — payload checksum          │
 * │ magic      (4 bytes)  — command ^ 0xFFFFFFFF      │
 * ├──────────────────────────────────────────────────┤
 * │ data       (N bytes)  — payload (if data_len > 0) │
 * └──────────────────────────────────────────────────┘
 */
public class AdbProtocol {

    // Commands
    public static final int A_CNXN = 0x4e584e43; // CONNECT
    public static final int A_AUTH = 0x48545541; // AUTH
    public static final int A_OPEN = 0x4e45504f; // OPEN
    public static final int A_OKAY = 0x59414b4f; // READY
    public static final int A_CLSE = 0x45534c43; // CLOSE
    public static final int A_WRTE = 0x45545257; // WRITE

    // Auth types
    public static final int AUTH_TOKEN = 1;
    public static final int AUTH_SIGNATURE = 2;
    public static final int AUTH_RSAPUBLICKEY = 3;

    // Protocol version
    public static final int A_VERSION = 0x01000000;
    public static final int MAX_PAYLOAD = 256 * 1024; // 256KB (modern ADB)

    public static final int HEADER_SIZE = 24;

    /** Encode an ADB message header + optional data into a byte array */
    public static byte[] encode(int command, int arg0, int arg1, byte[] data) {
        int dataLen = (data != null) ? data.length : 0;
        byte[] result = new byte[HEADER_SIZE + dataLen];
        ByteBuffer buf = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(dataLen);
        buf.putInt(dataLen > 0 ? checksum(data) : 0);
        buf.putInt(command ^ 0xFFFFFFFF);
        if (dataLen > 0) {
            buf.put(data);
        }
        return result;
    }

    public static byte[] encodeConnect() {
        byte[] banner = "host::\0".getBytes();
        return encode(A_CNXN, A_VERSION, MAX_PAYLOAD, banner);
    }

    public static byte[] encodeAuth(int type, byte[] data) {
        return encode(A_AUTH, type, 0, data);
    }

    public static byte[] encodeOpen(int localId, String destination) {
        byte[] dest = (destination + "\0").getBytes();
        return encode(A_OPEN, localId, 0, dest);
    }

    public static byte[] encodeWrite(int localId, int remoteId, byte[] data) {
        return encode(A_WRTE, localId, remoteId, data);
    }

    public static byte[] encodeOkay(int localId, int remoteId) {
        return encode(A_OKAY, localId, remoteId, null);
    }

    public static byte[] encodeClose(int localId, int remoteId) {
        return encode(A_CLSE, localId, remoteId, null);
    }

    /** Parse a 24-byte ADB header. Returns null if invalid. */
    public static int[] parseHeader(byte[] header) {
        if (header.length < HEADER_SIZE) return null;
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int dataLen = buf.getInt();
        int dataCrc = buf.getInt();
        int magic = buf.getInt();
        if (magic != (command ^ 0xFFFFFFFF)) return null;
        return new int[]{command, arg0, arg1, dataLen, dataCrc};
    }

    public static int checksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return sum;
    }
}
