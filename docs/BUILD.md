# 🛠 البناء والتطوير · Building and developing

## الطريقة الأسهل: بدون تثبيت أي شيء · The easy way: install nothing

لا تحتاج Android Studio ولا حتى كمبيوتر. GitHub يبني الملف نيابةً عنك:

*You need neither Android Studio nor, really, a computer. GitHub builds it for you:*

1. **Actions** → **Build APK** → **Run workflow**
2. اكتب رقم الإصدار، مثلاً `1.0.0` · *type a version, e.g. `1.0.0`*
3. بعد ٣–٥ دقائق يظهر الملف في [Releases](../../releases/latest)

*After 3–5 minutes the APK appears under [Releases](../../releases/latest).*

---

## البناء على جهازك · Building locally

### المتطلبات · Prerequisites

| | |
|---|---|
| JDK | **17** (أو أحدث · or newer) |
| Android SDK | Platform **35** + Build-Tools 35 |
| Gradle | لا حاجة لتثبيته — المشروع يجلبه بنفسه · not needed, the wrapper fetches it |

أسهل طريقة للحصول على الـ SDK هي تثبيت
[Android Studio](https://developer.android.com/studio) وفتح المشروع به مرة واحدة.

*The simplest way to get the SDK is to install
[Android Studio](https://developer.android.com/studio) and open the project once.*

بدون Android Studio، اضبط مسار الـ SDK في ملف `local.properties`:

*Without Android Studio, point `local.properties` at your SDK:*

```properties
sdk.dir=/path/to/Android/sdk
```

### الأوامر · Commands

```bash
# نسخة تجريبية تُثبَّت فوراً · a debug build you can install right away
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# نسخة الإصدار (تحتاج مفتاح توقيع — راجع SIGNING.md)
# a release build (needs a signing key — see SIGNING.md)
./gradlew assembleRelease
# → app/build/outputs/apk/release/app-release.apk

# التثبيت على هاتف موصول بـ USB · install onto a USB-connected phone
./gradlew installDebug

# تحديد رقم الإصدار يدوياً · set the version explicitly
./gradlew assembleRelease -PappVersionName=1.2.0 -PappVersionCode=10200
```

> `versionCode` **يجب** أن يزيد مع كل إصدار، وإلا رفض أندرويد التحديث.
> يشتقّه سير العمل تلقائياً من الوسم: `v1.2.3` → `10203`.
>
> *`versionCode` **must** increase with every release or Android rejects the update.
> The workflow derives it from the tag automatically: `v1.2.3` → `10203`.*

---

## تطوير الواجهة · Working on the UI

الواجهة كلها — التخطيط، محرّك التعبئة، التصدير — في ملف واحد:

*The whole front end — layout, packing engine, export — is one file:*

```
app/src/main/assets/www/index.html
```

### دورة تطوير سريعة · A fast edit loop

افتح الملف مباشرة في متصفّح على الكمبيوتر:

*Open it straight in a desktop browser:*

```bash
cd app/src/main/assets/www
python3 -m http.server 8000
# ثم افتح · then open  http://localhost:8000/index.html
```

كل شيء يعمل كما في التطبيق، عدا أمرين يتعطّلان تلقائياً خارجه:

*Everything behaves as it does in the app, except two things that switch themselves off:*

- **التصدير** يعود إلى تنزيل الملفات عبر المتصفّح بدل الحفظ في المعرض
  · *export falls back to a browser download instead of writing to the gallery*
- **قائمة ⋮** وأزرار الزووم لا تظهر — فهي خاصة بالهاتف
  · *the ⋮ menu and zoom dock are hidden — they are phone-only*

> استخدم خادماً محلياً (`http.server`) لا فتح الملف بـ `file://`.
> صفحات `file://` تُفسد `canvas.toBlob` المستخدَم في التصدير.
>
> *Serve it over `http://` rather than opening it as `file://`. A `file://` page taints the
> canvas and breaks the `toBlob` call the export relies on.*

### الطبقة الخاصة بالأندرويد · The Android-only layer

| الملف · File | الدور · What it does |
|---|---|
| `android.css` | مساحات آمنة للنوتش · أهداف لمس أكبر · قائمة سفلية · إشعارات · لوحة زووم |
| `android.js` | بث ملف PNG المصدَّر إلى المعرض · واجهة التحديث · تجميع الإعدادات · زر الرجوع |

كلاهما يتوقف تلقائياً إن لم يجد الجسر الأصلي `window.PannerNative`.

*Both no-op when the native bridge `window.PannerNative` is absent.*

### الصور المرفقة · Bundled artwork

`app/src/main/assets/www/img/` — الشعار فقط. التصميم يبدأ فارغاً والمستخدم
يضيف صوره من الهاتف.

*Only the logo. A new design starts empty and the user adds their own images
from the phone.*

لإضافة صور مرفقة مع التطبيق: ضعها في `img/` ثم عرّف مصفوفة `INITIAL` في أعلى
السكربت داخل `index.html` وابدأ `imageList` منها (كل عنصر يحمل `src` و`w` و`h` والاسم).

*To ship images with the app again: drop them in `img/`, declare an `INITIAL`
array at the top of the script in `index.html`, and seed `imageList` from it —
each entry carries `src`, `w`, `h` and a name.*

---

## المعمارية · How the app is put together

شاشة واحدة فيها `WebView`، وجسر أصلي للأشياء التي لا تستطيع صفحة ويب فعلها.

*One screen holding a `WebView`, plus a native bridge for the things a web page cannot do.*

```
MainActivity ──── WebView ──── assets/www/index.html
     │                              │
     │                              └── android.js  ⇄  window.PannerNative
     │
     ├── ExportStore    يبث الـ PNG إلى المعرض على دفعات
     │                  streams the PNG into the gallery in chunks
     └── UpdateManager  يسأل GitHub Releases، ينزّل، يشغّل المثبّت
                        asks GitHub Releases, downloads, launches the installer
```

### لماذا `WebViewAssetLoader` وليس `file://`؟ · Why `WebViewAssetLoader`?

الصفحة تُقدَّم على `https://appassets.androidplatform.net/` بدل تحميلها كـ `file://`.
صفحة `file://` تحصل على أصل مجهول، ما يفسد `canvas.toBlob` (وهو أساس التصدير)
ويجعل `localStorage` غير موثوق. على أصل حقيقي يعمل الاثنان بشكل طبيعي.

*The page is served from `https://appassets.androidplatform.net/` rather than loaded as
`file://`. A `file://` page gets an opaque origin, which taints the export canvas — breaking
`toBlob` — and makes `localStorage` unreliable. On a real origin both work normally.*

### لماذا يُبَثّ التصدير على دفعات؟ · Why chunked export?

بانر بدقة ٣٠٠ DPI قد يتجاوز مئات الميغابايتات. تحويله إلى `data:` URL واحد يستهلك ذاكرة
هائلة ويُسقط التطبيق. بدلاً من ذلك ترسل الصفحة base64 على دفعات محاذاة لثلاث بايتات،
فتُفكّ كل دفعة وحدها وتبقى الذاكرة ثابتة مهما طال البانر.

*A 300 DPI banner can run to hundreds of megabytes. Turning it into a single `data:` URL
would blow up memory. Instead the page sends 3-byte-aligned base64 chunks, each decodable on
its own, so memory stays flat no matter how long the banner gets.*
