import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/*
 * Version is defined here and can be overridden from CI:
 *   ./gradlew assembleRelease -PappVersionName=1.1.0 -PappVersionCode=3
 * The release workflow derives both from the git tag, so a tag is the single
 * source of truth for what the in-app updater compares against.
 */
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode: Int = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1

/*
 * Signing.
 *
 * An update only installs over an existing app when both APKs carry the SAME
 * signature, so the release key has to be stable across builds. No key is
 * committed to this repository — it is supplied at build time:
 *
 *   CI      → from GitHub secrets, written to keystore.properties by the
 *             release workflow (see docs/SIGNING.md).
 *   Locally → create keystore.properties yourself, next to this file's root,
 *             pointing at your own .jks. It is git-ignored.
 *
 * With no key available the release build is left unsigned and CI falls back
 * to publishing a debug-signed APK instead, which installs fine but cannot be
 * upgraded in place.
 */
val keystoreProps = Properties().apply {
    val local = rootProject.file("keystore.properties")
    if (local.exists()) FileInputStream(local).use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val hasSigningKey: Boolean =
    signingValue("storeFile", "PANNER_KEYSTORE_FILE")?.let {
        rootProject.file(it).exists()
    } ?: false

android {
    namespace = "com.puzzleskills.panner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.puzzleskills.panner"
        minSdk = 24                 // Android 7.0 — covers effectively every device in use
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        resourceConfigurations += listOf("en", "ar")
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                storeFile = rootProject.file(signingValue("storeFile", "PANNER_KEYSTORE_FILE")!!)
                storePassword = signingValue("storePassword", "PANNER_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PANNER_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PANNER_KEY_PASSWORD")
                enableV1Signing = true      // required for sideloading on Android 7/8
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false         // a single WebView screen: nothing to shrink
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*"
            )
        }
    }

    androidResources {
        // The bundled banner artwork is already compressed; re-packing the
        // JPEGs only slows the build down.
        noCompress += listOf("jpg", "jpeg", "png")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // WebViewAssetLoader — serves assets/www over a real https origin so the
    // export canvas is never tainted and localStorage behaves normally.
    implementation("androidx.webkit:webkit:1.12.1")
    // Phone photos record their rotation in EXIF instead of in the pixels;
    // without this they come in sideways.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
