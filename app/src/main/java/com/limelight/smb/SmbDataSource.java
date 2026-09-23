package com.limelight.smb;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;

import com.limelight.LimeLog;
import com.limelight.media.RandomAccessSource;

import java.io.EOFException;
import java.io.IOException;

/** Media3 byte source backed by an SMB random-access handle. */
@OptIn(markerClass = UnstableApi.class)
public final class SmbDataSource extends BaseDataSource {
    private static final int READ_AHEAD_BYTES = 1024 * 1024;
    private static final int PROBE_READ_BYTES = 128 * 1024;
    interface Opener {
        RandomAccessSource open(String uri) throws IOException;
    }

    public static final class Factory implements DataSource.Factory {
        private final Opener opener;

        public Factory(SmbStorage storage) {
            opener = storage::openVideoRandomAccess;
        }

        @Override
        public DataSource createDataSource() {
            return new SmbDataSource(opener);
        }
    }

    private final Opener opener;
    private RandomAccessSource source;
    private Uri uri;
    private long position;
    private long bytesRemaining;
    private boolean opened;
    private final byte[] readAhead = new byte[READ_AHEAD_BYTES];
    private long readAheadPosition;
    private int readAheadLength;
    private long networkBytes;
    private long networkReadNs;

    SmbDataSource(Opener opener) {
        super(true);
        this.opener = opener;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        RandomAccessSource next = opener.open(dataSpec.uri.toString());
        boolean success = false;
        try {
            long size = next.size();
            if (dataSpec.position < 0 || dataSpec.position > size) {
                throw new EOFException("SMB position " + dataSpec.position
                        + " is outside a " + size + " byte file");
            }
            source = next;
            uri = dataSpec.uri;
            position = dataSpec.position;
            readAheadPosition = position;
            readAheadLength = 0;
            networkBytes = 0;
            networkReadNs = 0;
            long available = size - position;
            bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                    ? available : Math.min(dataSpec.length, available);
            opened = true;
            transferStarted(dataSpec);
            success = true;
            return bytesRemaining;
        } finally {
            if (!success) next.close();
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        RandomAccessSource current = source;
        if (current == null) throw new IOException("SMB source is not open");
        if (position < readAheadPosition || position >= readAheadPosition + readAheadLength) {
            boolean sequential = readAheadLength > 0
                    && position == readAheadPosition + readAheadLength;
            int target = sequential ? READ_AHEAD_BYTES
                    : Math.max(PROBE_READ_BYTES, length);
            int fillLength = (int)Math.min(Math.min(target, readAhead.length), bytesRemaining);
            long startedNs = System.nanoTime();
            int filled = current.readAt(position, readAhead, 0, fillLength);
            long elapsedNs = System.nanoTime() - startedNs;
            if (filled > 0) {
                networkBytes += filled;
                networkReadNs += elapsedNs;
            }
            if (elapsedNs >= 1_000_000_000L) {
                LimeLog.warning("Slow SMB video read: " + filled + " bytes in "
                        + elapsedNs / 1_000_000 + " ms at offset " + position);
            }
            if (filled < 0) {
                bytesRemaining = 0;
                return C.RESULT_END_OF_INPUT;
            }
            readAheadPosition = position;
            readAheadLength = filled;
        }
        int bufferedOffset = (int)(position - readAheadPosition);
        int requested = (int)Math.min(Math.min(length, bytesRemaining),
                readAheadLength - bufferedOffset);
        if (requested <= 0) {
            bytesRemaining = 0;
            return C.RESULT_END_OF_INPUT;
        }
        System.arraycopy(readAhead, bufferedOffset, buffer, offset, requested);
        position += requested;
        bytesRemaining -= requested;
        bytesTransferred(requested);
        return requested;
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() throws IOException {
        uri = null;
        RandomAccessSource current = source;
        source = null;
        position = 0;
        bytesRemaining = 0;
        readAheadLength = 0;
        try {
            if (current != null) current.close();
        } finally {
            if (networkBytes > 0 && networkReadNs > 0) {
                double mibPerSecond = networkBytes * 1_000_000_000.0
                        / networkReadNs / (1024.0 * 1024.0);
                LimeLog.info(String.format(java.util.Locale.ROOT,
                        "SMB video reads: %.1f MiB at %.1f MiB/s", 
                        networkBytes / (1024.0 * 1024.0), mibPerSecond));
            }
            if (opened) {
                opened = false;
                transferEnded();
            }
        }
    }
}
