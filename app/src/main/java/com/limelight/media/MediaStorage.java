package com.limelight.media;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** Source independent directory and random access operations. Run off the UI thread. */
public interface MediaStorage extends Closeable {
    List<MediaEntry> list(String uri) throws IOException;
    MediaEntry stat(String uri) throws IOException;
    RandomAccessSource openRandomAccess(String uri) throws IOException;
}
