plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// Dependency libraries also ship profiles; their presence does not prove app journeys were collected.
val appRules = rootProject.file("app/src/main/baseline-prof.txt")
val hasAppRules = appRules.isFile && appRules.useLines { lines -> lines.any { it.contains("Lcom/hellfire/ass/") } }
if (providers.gradleProperty("android.testInstrumentationRunnerArguments.class").orNull?.contains("ProfileStartupBenchmark") == true) {
    check(hasAppRules) {
        "Collect BaselineProfileGenerator on the profile variant, copy its app rules to app/src/main/baseline-prof.txt, then rebuild benchmark."
    }
}

android {
    namespace = "com.hellfire.ass.macrobenchmark"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_APP_PROFILE_RULES", hasAppRules.toString())
    }
    buildFeatures { buildConfig = true }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("profile") {
            initWith(getByName("benchmark"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType in setOf("benchmark", "profile") } }

dependencies {
    implementation(libs.benchmark.macro)
    implementation(libs.test.runner)
    implementation(libs.test.junit)
    implementation(libs.test.uiautomator)
}
