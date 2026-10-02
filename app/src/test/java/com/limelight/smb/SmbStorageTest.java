package com.limelight.smb;

import org.junit.Test;

import static org.junit.Assert.*;

public class SmbStorageTest {
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
