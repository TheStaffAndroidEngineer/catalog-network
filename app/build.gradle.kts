plugins { alias(libs.plugins.androidApplication); alias(libs.plugins.kotlinCompose) }
android {
    namespace = "staff.android.network"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "staff.android.network"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_26
        targetCompatibility = JavaVersion.VERSION_26
    }
}
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
}
dependencies {
    implementation(project(":protocol"))
    implementation(libs.androidxActivityCompose)
    implementation(libs.androidxLifecycleViewModel)
    implementation(libs.androidxLifecycleRuntimeCompose)
    implementation(libs.coroutinesAndroid)
    val composeBom = platform(libs.composeBom)
    implementation(composeBom)
    implementation(libs.composeUi)
    implementation(libs.composeFoundation)
    implementation(libs.composeMaterial3)
    debugImplementation(libs.composeTestManifest)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.composeTestJunit4)
    androidTestImplementation(libs.androidxTestJunit)
    androidTestImplementation(libs.androidxTestRunner)
}
