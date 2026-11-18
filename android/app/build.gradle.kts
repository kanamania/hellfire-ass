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
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        versionCode = 9
        versionName = "0.9.0"
        testInstrumentationRunner = "com.hellfire.ass.PredeviceRunner"
        buildConfigField("boolean", "EMULATOR_BUILD", "false")
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
        getByName("debug") { ndk { abiFilters += "arm64-v8a" } }
        create("emulator") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".emulator"
            ndk { abiFilters.clear(); abiFilters += "x86_64" }
            buildConfigField("boolean", "EMULATOR_BUILD", "true")
            matchingFallbacks += "debug"
        }
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".devicetest"
            matchingFallbacks += "debug"
        }
        getByName("release") {
            ndk { abiFilters += "arm64-v8a"; debugSymbolLevel = "FULL" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ownerSigning.isNotEmpty()) signingConfig = signingConfigs.getByName("ownerRelease")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".benchmark"
            matchingFallbacks += "release"
        }
        create("profile") {
            initWith(getByName("benchmark"))
            // Collect original method names; R8 rewrites these rules in the optimized build.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    testBuildType = providers.gradleProperty("integrationBuildType").getOrElse("emulator")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    sourceSets.getByName("test").resources.srcDir("schemas")
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    sourceSets.getByName("profile").manifest.srcFile("src/benchmark/AndroidManifest.xml")
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
    implementation(libs.barcode.scanning)
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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.transformer)
    implementation(libs.exifinterface)
    "benchmarkImplementation"(libs.profileinstaller)
    "profileImplementation"(libs.profileinstaller)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.test.uiautomator)
    "emulatorImplementation"("androidx.compose.ui:ui-test-manifest")
    "deviceTestImplementation"("androidx.compose.ui:ui-test-manifest")
}
