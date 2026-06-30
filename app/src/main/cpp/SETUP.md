# إعداد aFreeRDP (دليل بناء محلي)

بيئة بناء هذا المشروع لا تملك اتصال إنترنت ولا NDK toolchain مُجهّز، لذلك
لا يمكن سحب وبناء FreeRDP هنا. كل ملفات الربط (CMake + JNI bridge بلغة C +
طبقة Kotlin) جاهزة، وما تبقى هو ثلاث خطوات تنفذها **على جهازك** بعد فتح
المشروع في Android Studio.

## المتطلبات

- Android Studio (Hedgehog أو أحدث) مع NDK 26+ و CMake مثبتين من SDK Manager.
- Git.
- اتصال إنترنت (لمرة واحدة، لسحب مصدر FreeRDP).

## الخطوة 1 — سحب FreeRDP كـ submodule

من جذر المشروع:

```bash
git submodule add https://github.com/FreeRDP/FreeRDP.git app/src/main/cpp/FreeRDP
cd app/src/main/cpp/FreeRDP
git checkout 3.5.1   # أو أي وسم/تاغ مستقر حديث تفضّله
git submodule update --init --recursive
cd ../../../../..
```

> إذا كنت تفضل عدم استخدام submodule، يمكنك ببساطة استنساخ FreeRDP يدوياً
> داخل `app/src/main/cpp/FreeRDP` بنفس الطريقة — ملف `CMakeLists.txt` في
> `app/src/main/cpp/` يبحث عن هذا المسار تحديداً.

## الخطوة 2 — تفعيل بناء الكود الأصلي (native) في Gradle

ملف `app/build.gradle.kts` يحتوي بالفعل على كتلة `externalNativeBuild`
معطّلة افتراضياً (انظر تعليق `// ENABLE_NATIVE_BUILD`). بعد سحب
الـ submodule، فعّلها كما هو موضّح في التعليق المجاور مباشرة، ثم أعد
المزامنة (Sync) في Android Studio.

## الخطوة 3 — البناء

```
./gradlew assembleDebug
```

أول بناء لـ FreeRDP نفسه قد يستغرق 20-60 دقيقة حسب جهازك (يُبنى لكل
ABI مفعّل في `ndk { abiFilters }`). البناءات اللاحقة أسرع بكثير بفضل
الـ cache.

## ماذا لو لم أفعل هذه الخطوات؟

البناء نفسه لن يتعطل. ملف `CMakeLists.txt` يتحقق من وجود
`app/src/main/cpp/FreeRDP/CMakeLists.txt` قبل محاولة البناء؛ إن لم يجده
يطبع رسالة ويتخطى بناء الكود الأصلي بالكامل، ويستمر باقي المشروع بالبناء
بنجاح. لكن **تنبيه مهم وظيفياً** (تم تصحيحه — كان هذا القسم سابقاً يذكر
معلومة غير صحيحة):

- **RDP**: لا يوجد "محرك RDP احتياطي مكتوب بلغة Kotlin" — تمت إزالته من
  `RdpRemoteAdapter.kt` (انظر تعليق الكلاس هناك: "the pure-Kotlin
  hand-written RDP parser has been removed; FreeRDP is the only supported
  backend"). أي أنه بدون بناء FreeRDP الأصلي فعلياً (محلياً عبر هذا الدليل،
  أو تلقائياً عبر CI في `.github/workflows/main.yml`)، فإن `AFreeRdpBridge.isAvailable`
  سيكون `false` وكل محاولات اتصال RDP ستفشل فوراً برسالة خطأ توجّه المستخدم
  لبناء المكتبة الأصلية — RDP لن "يعمل بشكل طبيعي" بدونها.
- **VNC**: غير منفَّذ بالكامل حالياً بصرف النظر عن خطوات هذا الدليل.
  `com.undatech.opaque.RfbConnectable.connect()` ما زال stub يرمي
  `VncNotImplementedException` دائماً (انظر `app/src/main/java/com/undatech/opaque/`).
  دمج FreeRDP لا علاقة له بـ VNC إطلاقاً؛ تفعيل VNC الحقيقي يتطلب دمج مكتبة
  RFB منفصلة تماماً (مثل `iiordanov/remote-desktop-clients`) وهو عمل غير
  مُنجز بعد في هذا المستودع.
- **SSH**: يعمل بشكل طبيعي دائماً (مكتبة JSch خالصة بلغة Java/Kotlin، بلا
  حاجة لأي بناء أصلي).

## تخصيصات يمكنك إضافتها لاحقاً

- `WITH_CLIPBOARD_REDIR`، `WITH_AUDIO`، الخ — أعلام بناء FreeRDP إضافية
  يمكن تفعيلها في `app/src/main/cpp/CMakeLists.txt`.
- إضافة قنوات (channels) كالحافظة (clipboard) ومشاركة الأجهزة (drive
  redirection) تتطلب توسيع `hexrdp_jni.c` لتسجيل الـ callbacks الخاصة بها.
