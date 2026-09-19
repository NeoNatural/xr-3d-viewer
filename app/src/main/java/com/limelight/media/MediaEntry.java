package com.limelight.media;

/** Metadata for one item in a local or remote media directory. */
public final class MediaEntry {
    public final String uri;
    public final String name;
    public final boolean directory;
    public final long size;
    public final long modifiedTime;

    public MediaEntry(String uri, String name, boolean directory, long size, long modifiedTime) {
        this.uri = uri;
        this.name = name;
        this.directory = directory;
        this.size = size;
        this.modifiedTime = modifiedTime;
    }
}
