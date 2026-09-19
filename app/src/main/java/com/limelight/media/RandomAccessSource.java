package com.limelight.media;

import java.io.Closeable;
import java.io.IOException;

/** Byte access used later by the SMB video data source. */
public interface RandomAccessSource extends Closeable {
    long size();
    int readAt(long position, byte[] buffer, int offset, int length) throws IOException;
}
