import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * compilerOptions rather than the older `kotlinOptions { jvmTarget = "17" }`.
 *
 * That form was deprecated for a long time and became a hard error in Kotlin 2.4, which
 * is what broke the grouped dependency update rather than anything in the update itself.
 * This DSL works on both, so it is a fix rather than a version bump in disguise.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * The single source of truth for the version. The release workflow reads this to name the
 * tag, and the in-app updater compares it against the latest GitHub release, so it must
 * not be duplicated anywhere else.
 */
val appVersionName = "0.1.3"

/**
 * Overridable so CI can guarantee a monotonically increasing code without anyone having
 * to remember to bump it: `-PversionCode=<run number>`.
 */
val appVersionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 3

android {
    namespace = "dev.kern.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kern.app"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            // Supplied by CI from encrypted secrets and never present in the repository.
            // Android refuses an update signed by a different key than the installed app,
            // so this key *is* the security boundary for over-the-air updates: losing it
            // means nobody can upgrade, and leaking it means someone else can.
            val keystore = System.getenv("KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            /**
             * On, with the two keep rules that make it safe, both in proguard-rules.pro.
             *
             * It was off with the note "no release minification in M1" and nothing saying
             * why - which by 0.1.3 had stopped being a decision and become a leftover.
             * The reason to be careful here is real, though: the JNI pty binds statically
             * by name and the workbench bridge is called from injected JavaScript, so R8
             * removing either produces a failure that exists only in release builds. The
             * rules pin both, and the result is verified on device before release rather
             * than assumed - see docs/17-releases.md.
             */
            isMinifyEnabled = true
            // Resources too. The vendored Termux view brings drawables and strings Kern
            // never renders, and nothing here loads a resource by name.
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned rather than broken when built locally without the key.
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // The updater compares BuildConfig.VERSION_NAME against the latest release.
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The bundled PRoot and its libraries must exist as real files in
            // nativeLibraryDir so they can be exec'd/dlopen'd, not left compressed
            // inside the APK.
            useLegacyPackaging = true
        }
    }

    ndkVersion = "27.0.12077973"
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)

    // Plain JVM JUnit 4 only. The unit tests here cover pure logic - quoting, parsing,
    // arithmetic - so nothing in src/test may need an emulator, Robolectric or a network:
    // the suite has to stay fast enough that every push can afford to run it.
    testImplementation(libs.junit)
}
