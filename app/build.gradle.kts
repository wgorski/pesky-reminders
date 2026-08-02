import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The Play upload key, loaded from a gitignored keystore.properties. Signing is
// deliberately *opt-in*: Play rejects a debug-signed upload outright, but the
// sideload flow in CLAUDE.md still wants `assembleRelease` to produce something
// installable on any machine that has only the debug key. Present the properties
// file and the release build is signed for upload; leave it out and it falls back
// to the debug key exactly as before.
//
// Note the two keys produce APKs that cannot upgrade each other — a signature
// change forces an uninstall, which takes the task list with it.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// -PuseDebugSigning forces the release build back onto the debug key even when the
// upload key is configured. The two channels genuinely want different signatures:
// Play needs the upload key, and any APK meant for sideloading must keep the debug
// key or it cannot install over the copy already on the phone — Android refuses a
// signature change, and an uninstall takes the task list with it.
val forceDebugSigning = providers.gradleProperty("useDebugSigning").isPresent
val hasUploadKey = keystoreProps.getProperty("storeFile") != null && !forceDebugSigning

android {
    namespace = "com.wgorski.peskyreminders"
    // API 36 (Android 16): Play requires new apps and updates to target it from
    // 31 Aug 2026. Nothing here needs the newer platform APIs — this is the Play
    // floor, not a feature.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wgorski.peskyreminders"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        // Single source of truth for the release version. Semver; bump once per
        // branch/session (minor for features, patch for fixes) — see CLAUDE.md.
        versionName = "0.19.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasUploadKey) {
            create("upload") {
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
            signingConfig = signingConfigs.getByName(if (hasUploadKey) "upload" else "debug")
        }
    }

    // Name the release artifact after the version so the file in
    // app/build/outputs/apk/release/ always says what it is. Debug keeps the
    // stock app-debug.apk name that the install/test commands rely on.
    //
    // This hook reaches APK outputs only — the bundle is named by the
    // stageReleaseBundle task below. Using `base { archivesName }` would cover
    // both but also rename app-debug.apk, which the documented install and test
    // commands hardcode.
    applicationVariants.all {
        if (name == "release") {
            outputs.all {
                (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                    .outputFileName = "pesky-reminders-$versionName.apk"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // The Compose UI tests run on the JVM under Robolectric, so they need the
    // merged resources (fonts, manifest) available to plain unit tests.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// The Play upload artifact. `bundleRelease` always writes app-release.aab, so this
// stages a version-named copy — the same reasoning as the APK renaming above: what
// you drag into the Play Console should say which build it is.
//
// The copy lands in a sibling `play/` directory rather than beside the original:
// reading from and writing into outputs/bundle/release makes the task its own
// input, which Gradle rejects.
tasks.register<Copy>("stageReleaseBundle") {
    dependsOn("bundleRelease")
    from(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
    into(layout.buildDirectory.dir("outputs/bundle/play"))
    rename { "pesky-reminders-${android.defaultConfig.versionName}.aab" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
