package com.limelight.smb;

import com.limelight.LimeLog;
import com.limelight.media.MediaEntry;
import com.limelight.media.MediaDirectoryOrder;
import com.limelight.media.MediaStorage;
import com.limelight.media.RandomAccessSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbRandomAccessFile;

/** A direct SMB client backed by NOVA's multi-credit jcifs-ng fork. */
public final class SmbStorage implements MediaStorage {
    private final String rootUri;
    private final CIFSContext context;
    private final String domain;
    private final String username;
    private final String password;

    public SmbStorage(String host, String share, String domain, String username, String password)
            throws IOException {
        if (!simpleComponent(host) || !simpleComponent(share)) {
            throw new IllegalArgumentException("Invalid SMB host or share");
        }
        rootUri = "smb://" + host + "/" + share + "/";
        this.domain = domain;
        this.username = username;
        this.password = password;
        Properties properties = new Properties();
        // This NOVA jcifs build supports 1 MiB SMB2/3 multi-credit transfers.
        // Video must keep large reads enabled; disabling them reduces playback
        // to a stream of small round trips and cannot sustain normal bitrates.
        properties.setProperty("jcifs.smb.client.useLargeReadWrite", "true");
        // Fail a genuinely lost request promptly rather than freezing playback
        // for jcifs' default thirty seconds.
        properties.setProperty("jcifs.smb.client.responseTimeout", "5000");
        properties.setProperty("jcifs.smb.client.soTimeout", "10000");
        properties.setProperty("jcifs.smb.client.connTimeout", "5000");
        properties.setProperty("jcifs.smb.client.sessionTimeout", "10000");
        properties.setProperty("jcifs.smb.client.maxRequestRetries", "1");
        properties.setProperty("jcifs.smb.client.tcpNoDelay", "true");
        // No credentials in the URL or global properties. Keep one context for the session.
        context = new BaseContext(new PropertyConfiguration(properties)).withCredentials(
                new NtlmPasswordAuthenticator(domain, username, password));
    }

    public String rootUri() {
        return rootUri;
    }

    /** Sequential image reads keep the SMB file handle open until the decoder closes it. */
    public InputStream openInputStream(String uri) throws IOException {
        try (SmbFile ignored = file(uri)) {
            return new SmbFileInputStream(uri, context);
        }
    }

    @Override
    public List<MediaEntry> list(String uri) throws IOException {
        try (SmbFile directory = file(uri)) {
            if (!directory.isDirectory()) {
                throw new IOException("Not an SMB directory: " + uri);
            }
            SmbFile[] children = directory.listFiles();
            List<MediaEntry> entries = new ArrayList<>(children.length);
            try {
                for (SmbFile child : children) {
                    entries.add(entry(child));
                }
            } finally {
                for (SmbFile child : children) {
                    child.close();
                }
            }
            MediaDirectoryOrder.newestFirst(entries);
            return entries;
        }
    }

    @Override
    public MediaEntry stat(String uri) throws IOException {
        try (SmbFile item = file(uri)) {
            return entry(item);
        }
    }

    @Override
    public RandomAccessSource openRandomAccess(String uri) throws IOException {
        SmbFile item = file(uri);
        SmbRandomAccessFile access = null;
        try {
            access = item.openRandomAccess("r");
            return new SmbAccess(item, access);
        } catch (IOException | RuntimeException e) {
            if (access != null) {
                try {
                    access.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            item.close();
            throw e;
        }
    }

    /** Native SMB2/3 path used for sustained high-bitrate video reads. */
    public RandomAccessSource openVideoRandomAccess(String uri) throws IOException {
        try {
            RandomAccessSource source = NativeSmbRandomAccess.open(uri, domain, username, password);
            LimeLog.info("Native libsmb2 video source opened");
            return source;
        } catch (IOException error) {
            LimeLog.warning("Native SMB2 open failed; using jcifs fallback: "
                    + error.getMessage());
            return openRandomAccess(uri);
        }
    }

    private SmbFile file(String uri) throws MalformedURLException {
        if (uri == null || !uri.startsWith(rootUri)) {
            throw new IllegalArgumentException("SMB URI is outside the configured share");
        }
        SmbFile file = new SmbFile(uri, context);
        if (!file.getCanonicalPath().startsWith(rootUri)) {
            file.close();
            throw new IllegalArgumentException("SMB URI escapes the configured share");
        }
        return file;
    }

    private static MediaEntry entry(SmbFile file) throws IOException {
        boolean directory = file.isDirectory();
        return new MediaEntry(file.getCanonicalPath(), file.getName(), directory,
                directory ? 0 : file.length(), file.lastModified());
    }

    private static boolean simpleComponent(String text) {
        return text != null && !text.isEmpty() && !text.contains("/")
                && !text.contains("\\") && !text.contains("@") && !text.contains(":");
    }

    @Override
    public void close() throws IOException {
        context.close();
    }

    private static final class SmbAccess implements RandomAccessSource {
        private final SmbFile file;
        private final SmbRandomAccessFile access;
        private final long size;
        private long position;

        SmbAccess(SmbFile file, SmbRandomAccessFile access) throws IOException {
            this.file = file;
            this.access = access;
            size = access.length();
        }

        @Override public long size() { return size; }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int length)
                throws IOException {
            if (position < 0) {
                throw new IllegalArgumentException("Negative SMB file offset");
            }
            if (this.position != position) access.seek(position);
            int read = access.read(buffer, offset, length);
            if (read > 0) this.position = position + read;
            return read;
        }

        @Override
        public void close() throws IOException {
            try {
                access.close();
            } finally {
                file.close();
            }
        }
    }
}
