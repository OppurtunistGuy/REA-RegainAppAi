plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "app.steady"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.steady"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            // Local backend (`npm run dev` in backend/) through the emulator's host alias.
            buildConfigField("String", "DEFAULT_API_BASE_URL", "\"http://10.0.2.2:8787\"")
            buildConfigField("boolean", "REQUIRE_HTTPS_API", "false")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Placeholder production host; change before release (docs/release).
            buildConfigField("String", "DEFAULT_API_BASE_URL", "\"https://api.steady.app\"")
            buildConfigField("boolean", "REQUIRE_HTTPS_API", "true")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { jvmToolchain(21) }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/sharedAssets"))
    testOptions { unitTests.isIncludeAndroidResources = true }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}
// Copy the shared detector seed into assets so the app works offline.
val copySharedAssets by tasks.registering(Copy::class) {
    from(rootProject.file("../shared/detectors"))
    into(layout.buildDirectory.dir("generated/sharedAssets/detectors"))
}
tasks.named("preBuild") { dependsOn(copySharedAssets) }
dependencies {
    implementation(project(":core:rules"))
    implementation(project(":core:detect"))
    implementation(project(":core:learning"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.text.google.fonts)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.billing.ktx)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
