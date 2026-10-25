package com.limelight.smb;

import org.junit.Test;

import static org.junit.Assert.*;

public class SmbStorageTest {
    @Test
    public void customPortSurvivesCanonicalPathsAndChildUrls() throws Exception {
        try (SmbStorage storage = new SmbStorage("phone", 1445, "photos", "", "", "");
             SmbStorage server = new SmbStorage("phone", 1445, "", "", "", "");
             jcifs.smb.SmbFile root = new jcifs.smb.SmbFile(storage.rootUri());
             jcifs.smb.SmbFile child = new jcifs.smb.SmbFile(root, "image.jpg")) {
            assertEquals("smb://phone:1445/photos/", storage.rootUri());
            assertEquals(storage.rootUri(), root.getCanonicalPath());
            assertEquals("smb://phone:1445/photos/image.jpg", child.getCanonicalPath());
            assertEquals(1445, child.getURL().getPort());
            assertEquals("smb://phone:1445/", server.rootUri());
            assertTrue(server.isShareList(server.rootUri()));
            assertFalse(storage.isShareList(storage.rootUri()));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.stat("smb://phone/photos/image.jpg"));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.stat("smb://phone:2445/photos/image.jpg"));
            assertThrows(IllegalArgumentException.class,
                    () -> storage.stat("smb://phone:1445/photos/../other/image.jpg"));
        }
    }

    @Test
    public void explicitDefaultPortPreservesLegacyUri() throws Exception {
        try (SmbStorage storage = new SmbStorage("nas", 445, "photos", "", "", "")) {
            assertEquals("smb://nas/photos/", storage.rootUri());
        }
    }

    @Test
    public void validatesPortBeforeConnecting() throws Exception {
        assertEquals(445, SmbStorage.portFromInput("  "));
        assertEquals(1445, SmbStorage.portFromInput(" 1445 "));
        assertEquals(1, SmbStorage.portFromInput("1"));
        assertEquals(65535, SmbStorage.portFromInput("65535"));
        for (String invalid : new String[]{"0", "-1", "+445", "65536", "1.5", "abc",
                "1445/photo", "999999999999999"}) {
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> SmbStorage.portFromInput(invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new SmbStorage("nas", 0, "photos", "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new SmbStorage("nas", 65536, "photos", "", "", ""));
    }

    @Test
    public void blankShareUsesServerRootAndExplicitShareKeepsItsRoot() throws Exception {
        try (SmbStorage server = new SmbStorage("nas", "", "", "", "");
             SmbStorage share = new SmbStorage("nas", "photos", "", "", "")) {
            assertEquals("smb://nas/", server.rootUri());
            assertTrue(server.isShareList(server.rootUri()));
            assertFalse(server.isShareList("smb://nas/photos/"));
            assertEquals("smb://nas/photos/", share.rootUri());
            assertFalse(share.isShareList(share.rootUri()));
        }
    }

    @Test
    public void serverScopeRejectsOtherHostsAndCanonicalTraversal() throws Exception {
        try (SmbStorage server = new SmbStorage("nas", "", "", "", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> server.stat("smb://other/photos/image.jpg"));
            assertThrows(IllegalArgumentException.class,
                    () -> server.stat("smb://nas.evil/photos/image.jpg"));
        }
        try (SmbStorage share = new SmbStorage("nas", "photos", "", "", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> share.stat("smb://nas/videos/image.jpg"));
            assertThrows(IllegalArgumentException.class,
                    () -> share.stat("smb://nas/photos/../videos/image.jpg"));
        }
    }
}
