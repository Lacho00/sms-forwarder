plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val refName = System.getenv("GITHUB_REF_NAME").orEmpty()
val releaseVersion = Regex("""^v(\d+)\.(\d+)\.(\d+)$""").matchEntire(refName)?.destructured

android {
    namespace = "dev.smsforwarder"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.smsforwarder"
        minSdk = 31
        targetSdk = 36
        versionCode = releaseVersion?.let { (x, y, z) -> x.toInt() * 10000 + y.toInt() * 100 + z.toInt() } ?: 1
        versionName = releaseVersion?.let { (x, y, z) -> "$x.$y.$z" } ?: "0.0.0-dev"
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
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
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
