plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.smsforwarder"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.smsforwarder"
        minSdk = 31
        targetSdk = 36
        versionCode = 20103
        versionName = "2.1.3"
    }

    val signingStoreFile = System.getenv("SIGNING_STORE_FILE")
    if (signingStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The encrypted dependency-metadata blob is flagged by F-Droid and IzzyOnDroid.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

tasks.withType<Test>().configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
    maxHeapSize = "1g"
    // Screenshots are rendered on demand: ./gradlew :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotsTest'
    // -Pscreenshots=es renders the Spanish UI; a bare -Pscreenshots renders English.
    if (project.hasProperty("screenshots")) {
        systemProperty("screenshots.locale", project.property("screenshots").toString().ifBlank { "en" })
        // Always render again: never UP-TO-DATE or FROM-CACHE, so the PNGs are really written.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    } else {
        filter.excludeTestsMatching("*ScreenshotsTest")
    }
}
