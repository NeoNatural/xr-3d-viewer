package com.limelight.smb;

import com.limelight.media.RandomAccessSource;

import java.io.IOException;

/** High-throughput SMB2/3 random access backed by native libsmb2. */
final class NativeSmbRandomAccess implements RandomAccessSource {
    private static final boolean AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("smb2-jni");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        AVAILABLE = loaded;
    }

    static NativeSmbRandomAccess open(String uri, String domain, String username,
                                      String password) throws IOException {
        if (!AVAILABLE) throw new IOException("Native SMB2 library is unavailable");
        long handle = nativeOpen(uri, domain, username, password);
        if (handle == 0) throw new IOException("Native SMB2 open failed");
        return new NativeSmbRandomAccess(handle);
    }

    private long handle;
    private final long size;

    private NativeSmbRandomAccess(long handle) {
        this.handle = handle;
        size = nativeSize(handle);
    }

    @Override public long size() { return size; }

    @Override
    public synchronized int readAt(long position, byte[] buffer, int offset, int length)
            throws IOException {
        if (handle == 0) throw new IOException("Native SMB2 file is closed");
        return nativeReadAt(handle, position, buffer, offset, length);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    private static native long nativeOpen(String uri, String domain, String username,
                                          String password) throws IOException;
    private static native long nativeSize(long handle);
    private static native int nativeReadAt(long handle, long position, byte[] buffer,
                                           int offset, int length) throws IOException;
    private static native void nativeClose(long handle);
}
