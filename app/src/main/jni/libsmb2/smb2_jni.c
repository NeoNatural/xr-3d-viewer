#include <jni.h>
#include <stdbool.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "smb2/smb2.h"
#include "smb2/libsmb2.h"

struct native_smb_file {
    struct smb2_context *context;
    struct smb2_url *url;
    struct smb2fh *file;
    uint64_t size;
    bool connected;
};

static void throw_io(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message ? message : "Native SMB error");
}

static const char *get_utf(JNIEnv *env, jstring value) {
    return value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
}

static void release_utf(JNIEnv *env, jstring value, const char *chars) {
    if (value && chars) (*env)->ReleaseStringUTFChars(env, value, chars);
}

static int hex_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

/* jcifs canonical paths percent-encode spaces and UTF-8 filename bytes. */
static bool decode_path(char *path) {
    char *source = path;
    char *target = path;
    while (*source) {
        if (*source == '%') {
            int high = hex_value(source[1]);
            int low = source[1] && source[2] ? hex_value(source[2]) : -1;
            if (high < 0 || low < 0) return false;
            *target++ = (char)((high << 4) | low);
            source += 3;
        } else {
            *target++ = *source++;
        }
    }
    *target = '\0';
    return true;
}

static void throw_smb_io(JNIEnv *env, struct smb2_context *context, const char *stage) {
    char message[512];
    const char *detail = context ? smb2_get_error(context) : NULL;
    snprintf(message, sizeof(message), "libsmb2 %s failed: %s", stage,
             detail && detail[0] ? detail : "unknown error");
    throw_io(env, message);
}

JNIEXPORT jlong JNICALL
Java_com_limelight_smb_NativeSmbRandomAccess_nativeOpen(
        JNIEnv *env, jclass clazz, jstring uri_value, jstring domain_value,
        jstring user_value, jstring password_value) {
    (void)clazz;
    const char *uri = get_utf(env, uri_value);
    const char *domain = get_utf(env, domain_value);
    const char *user = get_utf(env, user_value);
    const char *password = get_utf(env, password_value);
    struct native_smb_file *handle = calloc(1, sizeof(*handle));
    if (!uri || !handle) {
        throw_io(env, "Unable to allocate native SMB handle");
        goto fail;
    }
    handle->context = smb2_init_context();
    if (!handle->context) {
        throw_io(env, "Unable to initialize native SMB context");
        goto fail;
    }
    smb2_set_timeout(handle->context, 10);
    smb2_set_security_mode(handle->context, SMB2_NEGOTIATE_SIGNING_ENABLED);
    if (domain && domain[0]) smb2_set_domain(handle->context, domain);
    if (user) smb2_set_user(handle->context, user);
    if (password) smb2_set_password(handle->context, password);
    handle->url = smb2_parse_url(handle->context, uri);
    if (!handle->url) {
        throw_smb_io(env, handle->context, "URL parsing");
        goto fail;
    }
    if (!handle->url->share || !decode_path((char *)handle->url->share)
            || !handle->url->path || !decode_path((char *)handle->url->path)) {
        throw_io(env, "libsmb2 URL parsing failed: invalid encoded file path");
        goto fail;
    }
    if (smb2_connect_share(handle->context, handle->url->server,
                           handle->url->share, user) != 0) {
        throw_smb_io(env, handle->context, "share connection");
        goto fail;
    }
    handle->connected = true;
    handle->file = smb2_open(handle->context, handle->url->path, O_RDONLY);
    if (!handle->file) {
        throw_smb_io(env, handle->context, "file open");
        goto fail;
    }
    struct smb2_stat_64 stat;
    if (smb2_fstat(handle->context, handle->file, &stat) != 0) {
        throw_smb_io(env, handle->context, "file stat");
        goto fail;
    }
    handle->size = stat.smb2_size;
    release_utf(env, password_value, password);
    release_utf(env, user_value, user);
    release_utf(env, domain_value, domain);
    release_utf(env, uri_value, uri);
    return (jlong)(intptr_t)handle;

fail:
    if (handle) {
        if (handle->file) smb2_close(handle->context, handle->file);
        if (handle->connected) smb2_disconnect_share(handle->context);
        if (handle->url) smb2_destroy_url(handle->url);
        if (handle->context) smb2_destroy_context(handle->context);
        free(handle);
    }
    release_utf(env, password_value, password);
    release_utf(env, user_value, user);
    release_utf(env, domain_value, domain);
    release_utf(env, uri_value, uri);
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_limelight_smb_NativeSmbRandomAccess_nativeSize(
        JNIEnv *env, jclass clazz, jlong pointer) {
    (void)env; (void)clazz;
    struct native_smb_file *handle = (void *)(intptr_t)pointer;
    return handle ? (jlong)handle->size : 0;
}

JNIEXPORT jint JNICALL
Java_com_limelight_smb_NativeSmbRandomAccess_nativeReadAt(
        JNIEnv *env, jclass clazz, jlong pointer, jlong position,
        jbyteArray output, jint offset, jint length) {
    (void)clazz;
    struct native_smb_file *handle = (void *)(intptr_t)pointer;
    if (!handle || !output || position < 0 || offset < 0 || length < 0
            || offset + length > (*env)->GetArrayLength(env, output)) {
        throw_io(env, "Invalid native SMB read");
        return -1;
    }
    uint8_t *buffer = malloc((size_t)length);
    if (!buffer) {
        throw_io(env, "Unable to allocate native SMB read buffer");
        return -1;
    }
    int read = smb2_pread(handle->context, handle->file, buffer,
                          (uint32_t)length, (uint64_t)position);
    if (read < 0) {
        throw_io(env, smb2_get_error(handle->context));
    } else if (read > 0) {
        (*env)->SetByteArrayRegion(env, output, offset, read, (jbyte *)buffer);
    }
    free(buffer);
    return read;
}

JNIEXPORT void JNICALL
Java_com_limelight_smb_NativeSmbRandomAccess_nativeClose(
        JNIEnv *env, jclass clazz, jlong pointer) {
    (void)env; (void)clazz;
    struct native_smb_file *handle = (void *)(intptr_t)pointer;
    if (!handle) return;
    if (handle->file) smb2_close(handle->context, handle->file);
    if (handle->connected) smb2_disconnect_share(handle->context);
    if (handle->url) smb2_destroy_url(handle->url);
    if (handle->context) smb2_destroy_context(handle->context);
    free(handle);
}
