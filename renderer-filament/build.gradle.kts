plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.krystals.renderer.filament"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":renderer-core"))
    api(project(":interaction"))
    val localFilament = rootProject.file(".tooling/filament-android-1.71.5.aar")
    val localUtils = rootProject.file(".tooling/filament-utils-android-1.71.5.aar")
    if (localFilament.exists()) {
        implementation(files(localFilament))
        // The local offline bootstrap may only have the core AAR. The online/release path below
        // always resolves both artifacts at the same fixed version.
        if (localUtils.exists()) implementation(files(localUtils))
    } else {
        implementation("com.google.android.filament:filament-android:1.71.5")
        implementation("com.google.android.filament:filament-utils-android:1.71.5")
    }
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.1")
    testImplementation("org.junit.platform:junit-platform-launcher:1.13.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
