package com.limelight.smb;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SmbProfileStoreTest {
    private SmbProfileStore.Profile profile(int port) {
        return new SmbProfileStore.Profile("nas", port, "photos", "", "user", "");
    }

    @Test
    public void legacyProfileMatchesOnlyTheDefaultPort() throws Exception {
        JSONObject legacy = new JSONObject("{\"host\":\"NAS\",\"share\":\"photos\","
                + "\"domain\":\"\",\"username\":\"user\"}");
        assertTrue(SmbProfileStore.same(legacy, profile(445)));
        assertFalse(SmbProfileStore.same(legacy, profile(1445)));
        legacy.put("port", 1445);
        assertTrue(SmbProfileStore.same(legacy, profile(1445)));
        assertFalse(SmbProfileStore.same(legacy, profile(2445)));
        assertFalse(SmbProfileStore.same(legacy, profile(445)));
    }

    @Test
    public void customPortIsVisibleInSavedTargetLabel() {
        assertEquals("nas/photos (user)", profile(445).label());
        assertEquals("nas:1445/photos (user)", profile(1445).label());
    }
}
