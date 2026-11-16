import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val ownerSigning = Properties().apply {
    val source = rootProject.file("signing.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}

android {
    namespace = "com.hellfire.ass"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "com.hellfire.ass"
        minSdk = 29
        targetSdk = 36
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        versionCode = 8
        versionName = "0.8.0"
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    signingConfigs {
        if (ownerSigning.isNotEmpty()) create("ownerRelease") {
            storeFile = rootProject.file(ownerSigning.getProperty("storeFile"))
            storePassword = ownerSigning.getProperty("storePassword")
            keyAlias = ownerSigning.getProperty("keyAlias")
            keyPassword = ownerSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ownerSigning.isNotEmpty()) signingConfig = signingConfigs.getByName("ownerRelease")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
    lint { abortOnError = true }
    packaging { resources { excludes += setOf("META-INF/NOTICE.md", "META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE.txt") } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.google.auth)
    implementation(libs.litertlm)
    implementation(libs.ocr)
    implementation(libs.image.labeling)
    implementation(libs.mediapipe.text)
    implementation(libs.onnxruntime)
    implementation(libs.sqlcipher)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.work.runtime)
    implementation(libs.core)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
