# Standalone media build: only the XR renderer and SMB transport are runtime
# components. The former GameStream core and rooted evdev reader are no longer
# linked or packaged.
ROOT_LOCAL_PATH := $(call my-dir)
include $(ROOT_LOCAL_PATH)/libsmb2/Android.mk
include $(ROOT_LOCAL_PATH)/xr-renderer/Android.mk
