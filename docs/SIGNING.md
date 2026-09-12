# 🔑 ضبط مفتاح التوقيع · Setting up the signing key

**مرة واحدة فقط، ثم تنساه.** بعدها كل تحديث يُثبَّت فوق النسخة القديمة مباشرة.

*A one-time step. After it, every update installs straight over the old version.*

---

## لماذا؟ · Why this matters

أندرويد يرفض تثبيت تطبيق فوق آخر إن اختلف **توقيعهما**. فإن بُني كل إصدار بمفتاح مختلف،
سيطلب الهاتف من المستخدم حذف التطبيق أولاً في كل مرة — وتضيع إعداداته وترتيبه المحفوظ.

*Android refuses to install an app over another one unless both carry the **same signature**.
If every build used a different key, the phone would ask the user to uninstall first every
single time — losing their settings and saved layout.*

لذلك يحتاج المستودع لمفتاح ثابت واحد، محفوظ في **GitHub Secrets** ولا يُرفع أبداً مع الكود.

*So the repository needs one stable key, kept in **GitHub Secrets** and never committed.*

---

## الخطوات · The steps

### 1. أنشئ المفتاح · Create the key

على أي جهاز فيه Java (أو Android Studio):

*On any machine with Java installed (Android Studio ships with it):*

```bash
keytool -genkeypair -v \
  -keystore puzzleskills-release.jks \
  -alias puzzleskills \
  -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=Puzzle Skills Panner, O=Puzzle Skills, C=EG"
```

سيطلب منك كلمة مرور — اخترها واحفظها في مكان آمن.

*It will ask for a password. Choose one and keep it somewhere safe.*

> [!CAUTION]
> **احتفظ بنسخة احتياطية من ملف `.jks` وكلمة مروره.**
> إن فقدته، لن تستطيع إصدار تحديثات تُثبَّت فوق النسخ المثبّتة على الهواتف —
> وسيضطر كل مستخدم لحذف التطبيق وإعادة تثبيته.
>
> ***Back up the `.jks` file and its password.*** *Lose it and you can no longer ship updates
> that install over what is already on people's phones — everyone would have to uninstall
> and start over.*

### 2. حوّله إلى نص · Turn it into text

```bash
base64 -w0 puzzleskills-release.jks > keystore.base64.txt
```

على macOS استخدم `base64 -i puzzleskills-release.jks -o keystore.base64.txt`.

*On macOS use `base64 -i puzzleskills-release.jks -o keystore.base64.txt`.*

### 3. أضف الأسرار الأربعة · Add the four secrets

في المستودع: **Settings → Secrets and variables → Actions → New repository secret**

*In the repository: **Settings → Secrets and variables → Actions → New repository secret***

| اسم السر · Secret name | القيمة · Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | محتوى `keystore.base64.txt` كاملاً · the full contents of `keystore.base64.txt` |
| `ANDROID_KEYSTORE_PASSWORD` | كلمة مرور المخزن · the keystore password from step 1 |
| `ANDROID_KEY_ALIAS` | `puzzleskills` |
| `ANDROID_KEY_PASSWORD` | كلمة مرور المفتاح (نفسها عادةً) · the key password (usually the same) |

### 4. احذف النسخ المؤقتة · Clean up

```bash
rm keystore.base64.txt
```

ثم انقل `puzzleskills-release.jks` إلى مكان آمن — **لا تضعه داخل المستودع**
(ملف `.gitignore` يمنع ذلك أصلاً).

*Then move `puzzleskills-release.jks` somewhere safe — **do not put it in the repository***
*(`.gitignore` already blocks it).*

### 5. أعد البناء · Rebuild

**Actions → Build APK → Run workflow**، أو ضع وسماً جديداً.
من الآن فصاعداً كل ملف APK موقّع بنفس المفتاح، والتحديث يُثبَّت فوق القديم بلا حذف.

***Actions → Build APK → Run workflow***, *or push a new tag. From now on every APK is signed
with the same key and updates install in place.*

---

## قبل ضبط المفتاح · Before you set this up

لا شيء يتعطّل. سيبني GitHub ملف APK بتوقيع **debug**:

*Nothing breaks. GitHub builds a **debug**-signed APK instead:*

| | |
|---|---|
| يُثبَّت ويعمل بالكامل · Installs and runs fully | ✅ |
| التحقق من التحديثات يعمل · Update check works | ✅ |
| التحديث يُثبَّت فوق القديم · Update installs in place | ❌ يطلب الحذف أولاً · asks you to uninstall first |

الملف يظهر باسم ينتهي بـ `-unsigned-debug.apk` حتى لا يختلط بالإصدار الحقيقي،
وملاحظات الإصدار تنبّه لذلك.

*The file is named with an `-unsigned-debug.apk` suffix so it can't be confused with a real
release, and the release notes say so.*

---

## البناء محلياً بالمفتاح · Building locally with the key

أنشئ ملف `keystore.properties` في جذر المشروع (وهو مُستثنى من git):

*Create `keystore.properties` in the project root (it is git-ignored):*

```properties
storeFile=/absolute/path/to/puzzleskills-release.jks
storePassword=…
keyAlias=puzzleskills
keyPassword=…
```

ثم:

```bash
./gradlew assembleRelease
```

بدلاً من ذلك يمكن ضبط متغيّرات البيئة `PANNER_KEYSTORE_FILE` و`PANNER_KEYSTORE_PASSWORD`
و`PANNER_KEY_ALIAS` و`PANNER_KEY_PASSWORD`.

*Alternatively set the environment variables `PANNER_KEYSTORE_FILE`, `PANNER_KEYSTORE_PASSWORD`,
`PANNER_KEY_ALIAS` and `PANNER_KEY_PASSWORD`.*
