LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := smb2-jni
SMB2_LIB_SOURCES := $(filter-out vendor/lib/aes_apple.c vendor/lib/krb5-wrapper.c, \
    $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/vendor/lib/*.c)))
LOCAL_SRC_FILES := smb2_jni.c $(SMB2_LIB_SOURCES)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/vendor/include \
                    $(LOCAL_PATH)/vendor/include/smb2 \
                    $(LOCAL_PATH)/vendor/lib
LOCAL_CFLAGS := -O3 -fvisibility=hidden -D_FILE_OFFSET_BITS=64 \
                '-D_U_=__attribute__((unused))' \
                -DHAVE_ARPA_INET_H -DHAVE_FCNTL_H -DHAVE_INTTYPES_H \
                -DHAVE_NETDB_H -DHAVE_NETINET_IN_H -DHAVE_NETINET_TCP_H \
                -DHAVE_POLL_H -DHAVE_SOCKADDR_STORAGE -DHAVE_STRUCT_ADDRINFO \
                -DHAVE_STRUCT_IOVEC -DHAVE_LINGER -DHAVE_STDINT_H \
                -DHAVE_STDIO_H -DHAVE_STDLIB_H -DHAVE_STRINGS_H \
                -DHAVE_STRING_H -DHAVE_SYS_IOCTL_H -DHAVE_SYS_POLL_H \
                -DHAVE_SYS_SOCKET_H -DHAVE_SYS_STAT_H -DHAVE_SYS_TYPES_H \
                -DHAVE_SYS_UIO_H -DHAVE_SYS_TIME_H -DHAVE_TIME_H \
                -DHAVE_UNISTD_H -DHAVE_ERRNO_H -DSTDC_HEADERS
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
