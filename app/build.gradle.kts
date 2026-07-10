import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: reads credentials from app/keystore.properties (gitignored).
// Missing file => no release signingConfig, so debug builds still work without it.
val keystorePropsFile = rootProject.file("app/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.nayeemcharx.jplens"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nayeemcharx.jplens"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    lint {
        // The AnkiDroid API artifact ships AnkiDroid's own internal lint checks
        // (com.ichi2.anki.lint). DirectSystemCurrentTimeMillisUsage is a house rule
        // for their testable Time collection and doesn't apply to this app; it fires
        // on our own System.currentTimeMillis() calls and fails the release build.
        disable += "DirectSystemCurrentTimeMillisUsage"
    }
    packaging {
        resources {
            // Kuromoji's ipadic + core JARs both ship META-INF/CONTRIBUTORS.md.
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md"
            )
        }
    }
    // NOTE: deliberately NOT adding `androidResources { noCompress += "onnx" }`.
    // The int8 FuguMT weights compress ~30% (90 MB -> ~63 MB), which matters for the
    // Play 200 MB compressed-download limit, and Translator copies them to internal
    // storage before opening (ORT loads by path), so leaving them DEFLATE-compressed in
    // the APK only costs a one-time inflate on first run — no runtime penalty.
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kuromoji.ipadic)
    implementation(libs.anki.droid.api)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}