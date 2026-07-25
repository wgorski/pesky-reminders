plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.peskyreminders.poc"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.peskyreminders.poc"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        // Single source of truth for the release version. Semver; bump once per
        // branch/session (minor for features, patch for fixes) — see CLAUDE.md.
        versionName = "0.11.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // POC only: sign the release build with the debug key so the exposed
            // APK is installable via sideload. A real release needs its own keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Name the release artifact after the version so the file in
    // app/build/outputs/apk/release/ always says what it is. Debug keeps the
    // stock app-debug.apk name that the install/test commands rely on.
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
