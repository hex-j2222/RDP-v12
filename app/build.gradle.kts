plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.gotohex.rdp"
    compileSdk = 35
    // تثبيت إصدار NDK صراحةً — يمنع Gradle من اختيار أحدث NDK مثبّت على الجهاز/CI
    // ✅ r27d LTS — يطابق NDK_VERSION في main.yml (27.3.13750724)
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.gotohex.rdp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resourceConfigurations += listOf("en", "ar")

        // ── aFreeRDP native bridge (RDP) ─────────────────────────────────────
        // ABIs to build the native FreeRDP bridge for.
        // arm64-v8a   : modern 64-bit ARM devices.
        // armeabi-v7a : older / budget 32-bit ARM devices — added so the app
        //               installs and runs the native FreeRDP path on them too.
        //               Requires a 32-bit ARM build of the FreeRDP + OpenSSL
        //               prebuilt libraries (see freerdp-prebuilt/armeabi-v7a
        //               and ANDROID_OPENSSL_ROOT/openssl-armeabi-v7a in CI).
        // x86_64 / x86: 64-bit and 32-bit emulators (x86 also covers a small
        //               number of legacy Intel Atom tablets).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // عندما تُبنى FreeRDP فعلياً (submodule موجود)، find_package(OpenSSL)
        // التقليدي في CMake يبحث في مسارات النظام (Linux/host) بدل sysroot
        // الخاص بـ NDK، فيجد OpenSSL غير متوافق مع target Android ويفشل
        // تكوين CMake بالكامل (هذا سبب فشل configureCMakeDebug في الـ CI).
        // الحل: نمرر جذر OpenSSL المخصص لأندرويد (مبني مسبقاً في CI عبر
        // سكربت FreeRDP الرسمي android-build-openssl.sh، انظر main.yml)
        // إلى CMake عبر متغير البيئة ANDROID_OPENSSL_ROOT.
        // نمرر ANDROID_OPENSSL_ROOT فقط — CMakeLists.txt يحتسب المسار الكامل لكل ABI.
        // FreeRDP يُبنى كـ prebuilt منفصل في CI (انظر main.yml)، ولا يُبنى داخل Gradle.
        val androidOpenSslRoot = System.getenv("ANDROID_OPENSSL_ROOT")
        if (!androidOpenSslRoot.isNullOrBlank()) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_OPENSSL_ROOT=$androidOpenSslRoot"
                }
            }
        }
    }

    // ── Native build via CMake ────────────────────────────────────────────────
    // CMakeLists.txt already guards against missing FreeRDP submodule:
    // if FreeRDP/CMakeLists.txt is absent it prints a warning and skips
    // native build silently. SSH (JSch, pure Kotlin/Java) still works in that
    // case. RDP does NOT have a Kotlin fallback — see RdpRemoteAdapter.kt
    // ("the pure-Kotlin hand-written RDP parser has been removed; FreeRDP is
    // the only supported backend"); without the native .so, RDP connections
    // fail with an error directing the user to app/src/main/cpp/SETUP.md. VNC
    // is unimplemented regardless of native build status (see
    // app/src/main/java/com/undatech/opaque/RfbConnectable.kt — always throws
    // VncNotImplementedException).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ── ABI-SPLIT: 3 APKs من بناء Gradle واحد ───────────────────────────────
    // ينتج هذا البلوك تلقائياً (داخل app/build/outputs/apk/<type>/):
    //   1) app-universal-<type>.apk      → armeabi-v7a + arm64-v8a مدموجتان معاً
    //   2) app-armeabi-v7a-<type>.apk    → armeabi-v7a فقط (أصغر حجماً)
    //   3) app-arm64-v8a-<type>.apk      → arm64-v8a فقط (أصغر حجماً)
    // x86/x86_64 (المحاكي) تبقى مبنية للتطوير المحلي لكن بلا split خاص بها —
    // ستكون موجودة فقط داخل الـ universal APK إن وُجدت مكتباتها الأصلية.
    //
    // ملاحظة عن versionCode: لم أضِف override تلقائي للـ versionCode لكل
    // split (Gradle/AGP يوفر ذلك عبر internal API غير مضمون الاستقرار بين
    // إصدارات AGP). إن كنت ستوزّع الـ 3 APKs على متجر يفرض versionCode فريد
    // لكل ملف، فاضبطه يدوياً (مثلاً عبر CI: armeabi-v7a=1xxx, arm64-v8a=2xxx,
    // universal=3xxx) — أخبرني إن أردت ذلك وسأضيفه باستخدام طريقة مضمونة
    // أكثر (post-processing على اسم ملف APK الناتج بدل internal AGP classes).
    splits {
        abi {
            isEnable = true
            reset()                                  // تجاهل أي قائمة افتراضية سابقة
            include("armeabi-v7a", "arm64-v8a")      // الـ splits المطلوبة فقط
            isUniversalApk = true                     // + نسخة مدموجة لكل المعماريات
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcprov-jdk18on و jsch كلاهما يشحن نفس مسار MANIFEST.MF
            // داخل multi-release jar (versions/9 و versions/11)، ما يسبب
            // فشل mergeDebugJavaResource. هذا الملف مجرد بيان OSGi
            // وغير ضروري وقت التشغيل على أندرويد، فنستثنيه بالكامل
            // بدلاً من استثناء نسخة واحدة فقط.
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // All detectors below crash with IncompatibleClassChangeError because
        // the Kotlin Analysis API interfaces they depend on changed from classes
        // to interfaces between the Kotlin version used to compile lint jars
        // and the one used by the compiler plugin. Disabling them globally
        // until the upstream libraries ship updated lint artifacts.
        disable += setOf(
            // ── androidx.compose.runtime lint ─────────────────────────────────
            // All detectors below crash with IncompatibleClassChangeError because
            // the Kotlin Analysis API changed classes to interfaces between the
            // version used to compile the lint JARs and the active compiler plugin.
            // Disabling the entire compose.runtime.lint suite until upstream ships
            // updated artifacts.
            "AutoboxingStateCreation",
            "ComposableLambdaParameterNaming",
            "ComposableLambdaParameterPosition",
            "CompositionLocalNaming",
            "CoroutineCreationDuringComposition",
            "FlowOperatorInvokedInComposition",
            "FrequentlyChangingValue",
            "ProduceStateDoesNotAssignValue",
            "RememberInComposition",
            "RememberReturnType",
            "UnrememberedAnimatable",
            "UnrememberedMutableInteractionSource",
            "UnrememberedMutableState",
            // ── androidx.compose.ui lint ──────────────────────────────────────
            "ModifierFactoryExtensionFunction",
            "ModifierFactoryReturnType",
            "ModifierFactoryUnreferencedReceiver",
            "ModifierNodeInspectableProperties",
            "SuspiciousCompositionLocalModifierRead",
            "UnnecessaryCompositionLocalUsage",
            // ── androidx.compose.foundation lint ─────────────────────────────
            "FrequentlyChangedStateReadInComposition",
            // ── androidx.navigation.compose lint ─────────────────────────────
            "ComposableDestinationInComposeNavigator",
            // ── androidx.lifecycle lint ───────────────────────────────────────
            "NullSafeMutableLiveData",
            "StateFlowValueCalledInComposition",
            "LifecycleWhenChecks",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // HIGH-3 FIX: SQLCipher — AES-256 encryption for the Room database at rest.
    // Pass SupportFactory(passphrase) to Room.databaseBuilder().openHelperFactory().
    implementation(libs.sqlcipher)
    // 🔴 CRITICAL FIX: net.zetetic:sqlcipher-android does not transitively pull in
    // androidx.sqlite — it must be declared explicitly or SupportFactory fails to resolve.
    implementation(libs.androidx.sqlite)
    // MED-2 FIX: EncryptedFile for AES-256-GCM screenshot thumbnail encryption
    implementation(libs.security.crypto)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)

    // Lottie Animation
    implementation(libs.lottie.compose)

    // Gson
    implementation(libs.gson)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Splash Screen
    implementation(libs.splashscreen)

    // BouncyCastle for TLS/NLA
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bctls)
    // FIX-HTTPS: bcpkix needed for JcaX509v3CertificateBuilder (self-signed cert for HTTPS server)
    implementation(libs.bouncycastle.bcpkix)

    // JSch — pure-Java SSH2 client
    implementation(libs.jsch)

    // Biometric — مصادقة بصمة الإصبع ورمز الجهاز (BiometricManager/BiometricPrompt)
    implementation(libs.androidx.biometric)

    // bVNC (com.undatech.opaque) — استُعيض عنها بـ local stubs في:
    // app/src/main/java/com/undatech/opaque/
    // السبب: بناء bVNC عبر JitPack يفشل لأنها تعتمد على NDK+FreeRDP+sqlcipher prebuilt.

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

