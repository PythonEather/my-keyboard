LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := materialboard_quickjs
LOCAL_SRC_FILES := plugin_quickjs.c quickjs/quickjs.c quickjs/cutils.c quickjs/dtoa.c \
    quickjs/libregexp.c quickjs/libunicode.c
LOCAL_CFLAGS := -D_GNU_SOURCE -DCONFIG_VERSION=\"2026-06-04\" -fvisibility=hidden -O2 -DNDEBUG
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := materialboard_lua
LOCAL_SRC_FILES := plugin_lua.c \
    lua/lapi.c lua/lcode.c lua/lctype.c lua/ldebug.c lua/ldo.c lua/ldump.c lua/lfunc.c lua/lgc.c lua/llex.c \
    lua/lmem.c lua/lobject.c lua/lopcodes.c lua/lparser.c lua/lstate.c lua/lstring.c lua/ltable.c lua/ltm.c \
    lua/lundump.c lua/lvm.c lua/lzio.c lua/lauxlib.c lua/lbaselib.c lua/lcorolib.c lua/lmathlib.c lua/lstrlib.c \
    lua/ltablib.c lua/lutf8lib.c
LOCAL_CFLAGS := -std=gnu99 -fvisibility=hidden -O2 -DNDEBUG -DLUA_USE_POSIX
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
