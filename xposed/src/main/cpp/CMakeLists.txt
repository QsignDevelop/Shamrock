cmake_minimum_required(VERSION 3.22.1)

# NOTE: project name MUST differ from app/src/main/cpp/CMakeLists.txt which
# also declares project("shamrock") and builds libshamrock.so. Keeping the
# same name would yield two libraries with identical SONAME and the wrong
# one would be packaged into the APK. Use "shamrocknt" — for "9.2.90 NT".
project("shamrocknt")

# ---------------------------------------------------------------------------
# Compiler / link flags
# ---------------------------------------------------------------------------
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)

# Release defaults: small + fast. -fno-rtti / -fno-exceptions cuts ~200KB.
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(
        -Os
        -fno-rtti
        -fno-exceptions
        -fdata-sections
        -ffunction-sections
        -fno-stack-protector
        -fomit-frame-pointer
    )
    add_link_options(
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,max-page-size=16384
    )
endif()

# Hide all symbols by default; only JNI_OnLoad and Java_* are exposed.
add_compile_options(-fvisibility=hidden)

# ---------------------------------------------------------------------------
# Sources
# ---------------------------------------------------------------------------
# Main JNI library: libshamrock.so
# Loaded from Kotlin via System.loadLibrary("shamrock").
add_library(${CMAKE_PROJECT_NAME} SHARED
    shamrock_native.cpp     # JNI_OnLoad + entry table
    dynscan_native.cpp      # runtime RegisterNatives capture
    sign_native.cpp         # FEKit getSign hook + JNI bridge
    anti_detect_native.cpp  # QSec.detectMethod / ArtTiHook bypass
    hide_native.cpp         # /proc/self/maps + dlopen cleansing
    xposed.cpp              # legacy stub, kept for compat
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# ---------------------------------------------------------------------------
# ShadowHook (inline hook engine, from ByteDance)
#
# Resolved via Android Gradle Prefab — the host build.gradle.kts must declare
# `implementation("com.bytedance.android:shadowhook:1.0.10")` and have
# `buildFeatures { prefab = true }` set, then this find_package call finds
# the headers + prebuilt .so transparently.
#
# Falls back gracefully if not available: sign_native.cpp checks
# __has_include(<shadowhook.h>) so the build still produces a working
# library, just with reduced inline-hook capability.
# ---------------------------------------------------------------------------
find_package(shadowhook QUIET CONFIG)
if(shadowhook_FOUND)
    target_link_libraries(${CMAKE_PROJECT_NAME} shadowhook::shadowhook)
    target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE SHAMROCK_HAS_SHADOWHOOK=1)
    message(STATUS "Shamrock: ShadowHook integrated (inline hooks ENABLED)")
else()
    message(WARNING "Shamrock: ShadowHook NOT found — building with reduced functionality."
                    " Add `implementation(\"com.bytedance.android:shadowhook:1.0.10\")` to xposed/build.gradle.kts.")
endif()

# ---------------------------------------------------------------------------
# System libraries
# ---------------------------------------------------------------------------
# NOTE: do NOT link libandroid — when this .so is dlopen'd inside QQ via the
# LSPosed module classloader, the com_android_art namespace cannot resolve
# libandroid.so and the load fails. We only use <android/log.h> which is
# served by liblog.so.
target_link_libraries(${CMAKE_PROJECT_NAME}
    log
    dl
)
