package com.limelight.smb;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Saved NAS targets. Passwords are encrypted with an app-owned Android Keystore key. */
final class SmbProfileStore {
    private static final String PREFS = "smb_targets";
    private static final String TARGETS = "targets";
    private static final String KEY_ALIAS = "moonlight_xr_smb_passwords";

    static final class Profile {
        final String host, share, domain, username, password;

        Profile(String host, String share, String domain, String username, String password) {
            this.host = host;
            this.share = share;
            this.domain = domain;
            this.username = username;
            this.password = password;
        }

        String label() { return host + "/" + share + " (" + username + ")"; }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Profile> load(Context context) throws IOException {
        List<Profile> result = new ArrayList<>();
        try {
            JSONArray targets = new JSONArray(prefs(context).getString(TARGETS, "[]"));
            for (int i = 0; i < targets.length(); i++) {
                JSONObject item = targets.getJSONObject(i);
                result.add(new Profile(item.getString("host"), item.getString("share"),
                        item.optString("domain"), item.optString("username"),
                        decrypt(item.getString("password"))));
            }
            return result;
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException("Cannot read saved SMB targets", e);
        }
    }

    static void save(Context context, Profile profile) throws IOException {
        try {
            JSONArray old = new JSONArray(prefs(context).getString(TARGETS, "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!same(item, profile)) updated.put(item);
            }
            JSONObject item = new JSONObject();
            item.put("host", profile.host);
            item.put("share", profile.share);
            item.put("domain", profile.domain);
            item.put("username", profile.username);
            item.put("password", encrypt(profile.password));
            updated.put(item);
            if (!prefs(context).edit().putString(TARGETS, updated.toString()).commit()) {
                throw new IOException("Could not save SMB target");
            }
        } catch (JSONException | GeneralSecurityException e) {
            throw new IOException("Could not save SMB target", e);
        }
    }

    static void remove(Context context, Profile profile) throws IOException {
        try {
            JSONArray old = new JSONArray(prefs(context).getString(TARGETS, "[]"));
            JSONArray updated = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.getJSONObject(i);
                if (!same(item, profile)) updated.put(item);
            }
            if (!prefs(context).edit().putString(TARGETS, updated.toString()).commit()) {
                throw new IOException("Could not remove SMB target");
            }
        } catch (JSONException e) {
            throw new IOException("Could not remove SMB target", e);
        }
    }

    private static boolean same(JSONObject item, Profile profile) {
        return item.optString("host").equalsIgnoreCase(profile.host)
                && item.optString("share").equalsIgnoreCase(profile.share)
                && item.optString("domain").equalsIgnoreCase(profile.domain)
                && item.optString("username").equals(profile.username);
    }

    private static SecretKey key() throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        try {
            store.load(null);
        } catch (java.io.IOException | java.security.cert.CertificateException e) {
            throw new GeneralSecurityException(e);
        }
        SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws GeneralSecurityException {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw new GeneralSecurityException("Invalid saved password");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.DEFAULT)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.DEFAULT)),
                StandardCharsets.UTF_8);
    }
}
