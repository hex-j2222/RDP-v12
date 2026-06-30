cmake_minimum_required(VERSION 3.22.1)
project(hexrdp_native)

set(CMAKE_C_STANDARD 11)

# ════════════════════════════════════════════════════════════════════════
# HexRDP JNI Bridge
# ════════════════════════════════════════════════════════════════════════
#
# الاستراتيجية: نستخدم FreeRDP و OpenSSL كـ prebuilt libraries جاهزة.
# FreeRDP يُبنى مسبقاً في CI عبر cmake مباشرة (انظر main.yml خطوة
# "Build FreeRDP prebuilt"). هذا يتجنب تماماً مشاكل add_subdirectory
# مع NDK toolchain (SWScale REQUIRED، OpenSSL scope، إلخ).

set(FREERDP_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP)
set(FREERDP_PREBUILT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/freerdp-prebuilt)
set(FREERDP_ABI_DIR ${FREERDP_PREBUILT_ROOT}/${ANDROID_ABI})

# OpenSSL prebuilt — مطلوب فقط عندما يكون FreeRDP prebuilt موجوداً
# في CI: ANDROID_OPENSSL_ROOT مُصدَّر قبل gradlew
# في البناء المحلي بدون FreeRDP prebuilt: لا يُبنى الجسر الأصلي ولا حاجة لـ OpenSSL هنا،
# لكن تنبيه: لا يوجد "Kotlin fallback" يُشغِّل RDP بديلاً — RdpRemoteAdapter.kt
# يعتمد حصرياً على هذا الجسر الأصلي؛ بدونه تفشل اتصالات RDP (انظر SETUP.md).
if(DEFINED ANDROID_OPENSSL_ROOT AND NOT ANDROID_OPENSSL_ROOT STREQUAL "")
    set(OPENSSL_ABI_DIR "${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}")
else()
    set(OPENSSL_ABI_DIR "")
    message(STATUS "HexRDP: ANDROID_OPENSSL_ROOT not set — native RDP bridge will be skipped (see SETUP.md)")
endif()

# ── الحالة 1: FreeRDP prebuilt موجود → بناء JNI bridge كامل ──────────
if(EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.so" OR
   EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.a")

    message(STATUS "HexRDP: FreeRDP prebuilt found at ${FREERDP_ABI_DIR}")

    # OpenSSL
    add_library(OpenSSL::Crypto STATIC IMPORTED)
    set_target_properties(OpenSSL::Crypto PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libcrypto.a")

    add_library(OpenSSL::SSL STATIC IMPORTED)
    set_target_properties(OpenSSL::SSL PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libssl.a")

    # WinPR — السكربت الرسمي يبني .so
    add_library(winpr SHARED IMPORTED)
    set_target_properties(winpr PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libwinpr3.so")

    # FreeRDP
    add_library(freerdp SHARED IMPORTED)
    set_target_properties(freerdp PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp3.so")

    add_library(freerdp-client SHARED IMPORTED)
    set_target_properties(freerdp-client PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp-client3.so")

    # JNI bridge
    add_library(hexrdp_jni SHARED hexrdp_jni.c)

    target_include_directories(hexrdp_jni PRIVATE
        # FreeRDP 3.x installs headers under versioned subdirectories
        # (include/freerdp3/freerdp/*.h, include/winpr3/winpr/*.h) — this is
        # the official upstream layout (lets FreeRDP2/FreeRDP3 coexist), see
        # https://www.linuxfromscratch.org/blfs/view/git/xsoft/freerdp.html
        # ("Installed Directories: /usr/include/{freerdp3,...,winpr3}").
        # Pointing at ${FREERDP_ABI_DIR}/include alone (without the freerdp3/
        # winpr3 subfolders) causes: fatal error: 'freerdp/freerdp.h' file not found
        ${FREERDP_ABI_DIR}/include/freerdp3
        ${FREERDP_ABI_DIR}/include/winpr3
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(hexrdp_jni
        freerdp-client
        freerdp
        winpr
        OpenSSL::SSL
        OpenSSL::Crypto
        android
        log
        z
    )

    # ARMV7-FIX: على armeabi-v7a فقط، WinPR/FreeRDP يستخدمان عمليات atomic
    # ذرية 8-بايت (__atomic_fetch_add_8 ...) التي لا يوفرها compiler-rt
    # ضمنياً على 32-بت ARM — يجب ربط libatomic.a الخاصة بـ NDK صريحاً، وإلا
    # يفشل الربط بخطأ "undefined reference to __atomic_fetch_add_4/8".
    # arm64-v8a/x86_64/x86 لا يحتاجون هذا (لديهم تعليمات atomic أصلية).
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_link_libraries(hexrdp_jni atomic)
    endif()

# ── الحالة 2: لا prebuilt → skip silently, لا جسر RDP أصلي متاح ─────────
else()
    message(STATUS "HexRDP: FreeRDP prebuilt NOT found at ${FREERDP_ABI_DIR}")
    message(STATUS "HexRDP: Skipping native bridge — RDP connections will fail until it is built (see SETUP.md). VNC/SSH are unaffected by this.")
    message(STATUS "HexRDP: Run CI to build FreeRDP prebuilt automatically")
endif()
