<div align="center">

# 🧩 Puzzle Skills · Panner Design

**تطبيق أندرويد لتصميم بانر الجوخ — تعبئة متراصة، أضف صورك، جاهز للطباعة والقص**

*Android app for laying out felt banners — tight packing, your own images, print-and-cut ready*

تصميم: **إسراء عبد الظاهر**

[![Build APK](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/actions/workflows/android-release.yml/badge.svg)](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/actions/workflows/android-release.yml)

### 👉 [**تحميل آخر إصدار · Download the latest APK**](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/releases/latest)

</div>

---

## 📲 التثبيت على الهاتف · Install on your phone

> افتح الخطوات التالية **من هاتف الأندرويد نفسه**، لا من الكمبيوتر.
> *Follow these steps **on the Android phone itself**, not on a computer.*

| # | بالعربية | English |
|---|----------|---------|
| 1 | افتح صفحة [**Releases**](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/releases/latest) من متصفّح الهاتف | Open the [**Releases**](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/releases/latest) page in the phone's browser |
| 2 | اضغط على الملف المنتهي بـ `.apk` تحت **Assets** | Tap the file ending in `.apk` under **Assets** |
| 3 | عند التحذير "لا يُسمح بتثبيت تطبيقات من هذا المصدر" اضغط **الإعدادات** ثم فعّل **السماح من هذا المصدر** | On the "unknown source" warning tap **Settings**, then enable **Allow from this source** |
| 4 | ارجع واضغط **تثبيت** ثم **فتح** | Go back, tap **Install**, then **Open** |

التطبيق يعمل **بالكامل بدون إنترنت** — كل الصور ومحرّك التوزيع بداخل الملف.
الإنترنت مطلوب فقط للتحقق من التحديثات.

*The app works **fully offline** — the artwork and the layout engine are bundled inside it.
A connection is only needed to check for updates.*

<details>
<summary><b>لم يظهر أي ملف APK في صفحة الإصدارات؟ · No APK on the Releases page yet?</b></summary>

<br>

معناها أن أول إصدار لم يُبنَ بعد. ابنِه بضغطة واحدة:

*It means the first release has not been built yet. Build it in one click:*

1. **Actions** → **Build APK** → **Run workflow**
2. اكتب رقم الإصدار في خانة *version*، مثلاً `1.0.0` — *type a version, e.g. `1.0.0`*
3. **Run workflow**، وانتظر حوالي ٣–٥ دقائق — *and wait about 3–5 minutes*
4. سيظهر الملف في [Releases](https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design/releases/latest) — *the APK appears under Releases*

</details>

---

## ⬆️ التحديثات · Updating the app

التطبيق يحدّث نفسه من هذا المستودع — لا حاجة لمتجر تطبيقات.

*The app updates itself from this repository — no app store involved.*

**داخل التطبيق · From inside the app**

```
⋮  ←  التحقق من التحديثات
⋮  ←  Check for updates
```

* يتحقّق التطبيق تلقائياً **مرة كل ٢٤ ساعة** ويضع نقطة وردية 🔴 على زر ⋮ عند توفّر إصدار جديد.
* اضغط على الزر ليُنزّل التحديث ثم يفتح شاشة التثبيت — بياناتك وترتيبك المحفوظ يبقيان كما هما.

*The app checks once every 24 hours and marks the ⋮ button with a pink dot when a new version is out.
Tapping it downloads the update and opens the installer — your saved layout is kept.*

**إصدار تحديث جديد · Publishing a new version** *(صاحب المستودع · repository owner)*

```bash
git tag v1.1.0
git push origin v1.1.0
```

هذا وحده يكفي: يبني GitHub الملف تلقائياً وينشره في Releases، ويراه التطبيق خلال يوم.

*That is all it takes: GitHub builds the APK, publishes it to Releases, and every installed
copy of the app finds it within a day.*

> [!IMPORTANT]
> لكي يُثبّت التحديث **فوق** النسخة القديمة بدل طلب حذفها، يجب ضبط مفتاح التوقيع مرة واحدة —
> الخطوات في [`docs/SIGNING.md`](docs/SIGNING.md).
>
> *For updates to install **over** the old version instead of asking you to uninstall it first,
> the signing key must be set up once — see [`docs/SIGNING.md`](docs/SIGNING.md).*

---

## ✨ ما الذي يفعله التطبيق · What the app does

كل خيارات المشروع الأصلي متاحة من الواجهة، مرتّبة في لوحة إعدادات تفتح بزر **☰**:

*Every option from the original project is on the front end, grouped in a settings sheet behind the **☰** button:*

| المجموعة · Group | الخيارات · Controls |
|---|---|
| 📐 **المقاس** · Size | عرض القماش (سم) · أكبر ضلع للصورة، موحّد أو لكل صورة على حدة · الفاصل الداخلي (مم) · هامش الأطراف، موحّد أو لكل جهة |
| 🔁 **التكرار** · Repeat | تكرار كل صورة · تكرار البانر كاملاً ✂ |
| 🎨 **المظهر** · Appearance | تشبّع الألوان · لون القماش (٦ ألوان جوخ) · إظهار الأدلّة |
| ⚙️ **التعبئة الذكية** · Smart fill | تدوير الصور للملاءمة ↻ · تقسيم الصور الكبيرة لبلاطات ✂ · تعبئة الفراغات ▦ بحد أدنى للمقاس · الترتيب: متوازن / أصلي / عشوائي |
| ✥ **التحريك اليدوي** · Manual | سحب أي صورة بالإصبع · تغيير المقاس · تدوير · تكرار · حذف · للأمام/للخلف · يُحفظ تلقائياً |
| ⬇ **التصدير** · Export | ٧٢ / ١٥٠ / ٣٠٠ DPI · حفظ مباشر في معرض الصور · مشاركة الملف |

**إضافات خاصة بنسخة الأندرويد · Added for the Android build**

- **أضف صورك** من معرض الهاتف مباشرة — *pick images straight from the phone gallery*
- **حفظ في المعرض** بدل التنزيل: تُحفظ التصديرات في `Pictures/PuzzleSkills` — *exports land in the gallery, not a downloads folder*
- **تصدير بلا حدود**: البانرات الطويلة تُقسّم إلى شرائح PNG بكامل الدقة، والقص يقع دائماً بين الصور لا عبرها — *long banners split into full-resolution PNG strips, always cut between images and never through one*
- **تكبير/تصغير** بالإصبع أو بأزرار الزووم، مع زر "ملاءمة الشاشة" — *pinch zoom plus precise zoom buttons and fit-to-screen*
- **زر الرجوع** يغلق اللوحات المفتوحة أولاً — *the back button closes open panels first*
- الشاشة لا تنطفئ أثناء التصدير — *the screen stays awake while exporting*

---

## 🗂 بنية المشروع · Project layout

```
.
├── app/
│   ├── build.gradle.kts              إعدادات البناء والتوقيع · build + signing config
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/www/               ← تطبيق الويب كاملاً · the whole web app
│       │   ├── index.html              محرّك التوزيع والواجهة · layout engine + UI
│       │   ├── android.css             لمسات الهاتف · phone ergonomics
│       │   ├── android.js              جسر الحفظ والتحديث · save + update bridge
│       │   └── img/                    الصور المرفقة · bundled artwork
│       ├── java/com/puzzleskills/panner/
│       │   ├── MainActivity.kt         شاشة التطبيق · the one screen
│       │   ├── ExportStore.kt          الحفظ في المعرض · streaming gallery writer
│       │   ├── UpdateManager.kt        التحديث الذاتي · self-update
│       │   └── PannerApp.kt
│       └── res/                        الأيقونات والألوان والنصوص
├── .github/workflows/android-release.yml   بناء ونشر الـ APK · builds + publishes the APK
└── docs/
    ├── SIGNING.md                    ضبط مفتاح التوقيع · set up the signing key
    └── BUILD.md                      البناء على جهازك · build it locally
```

### تعديل التصميم · Changing the design

كل الواجهة في ملف واحد: `app/src/main/assets/www/index.html`.
عدّله، ثم ضع وسماً جديداً (`git tag v1.0.1 && git push origin v1.0.1`) ليصل التعديل لكل الهواتف.

*The entire UI lives in one file: `app/src/main/assets/www/index.html`.
Edit it, push a new tag, and every phone picks the change up.*

يمكنك فتح نفس الملف في متصفّح على الكمبيوتر لتجربة التعديلات بسرعة — الإضافات الخاصة
بالأندرويد تتعطّل تلقائياً خارج التطبيق ويعود التصدير إلى تنزيل عادي.

*You can open that same file in a desktop browser to iterate quickly — the Android-only
pieces switch themselves off outside the app and export falls back to a normal download.*

---

## 🛠 البناء محلياً · Build it yourself

```bash
git clone https://github.com/OmarEAbdelaal/Puzzle-Skills-Panner-Design.git
cd Puzzle-Skills-Panner-Design
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

التفاصيل الكاملة في [`docs/BUILD.md`](docs/BUILD.md) · *Full details in [`docs/BUILD.md`](docs/BUILD.md).*

---

## 📋 المتطلبات · Requirements

| | |
|---|---|
| أقل إصدار أندرويد · Minimum Android | **7.0** (API 24) |
| حجم التطبيق · App size | ≈ 2 MB |
| الأذونات · Permissions | الإنترنت (للتحديثات فقط) · تثبيت التطبيقات (للتحديث الذاتي) · التخزين (أندرويد ٩ وأقدم فقط) |
| يعمل بدون إنترنت · Works offline | ✅ |

---

<div align="center">
<sub>🧩 Puzzle Skills · Panner Design</sub>
</div>
