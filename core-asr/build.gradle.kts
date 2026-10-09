plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.localfirst.realtimetranslator.asr"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":core-model"))
    implementation("com.k2fsa:sherpa-onnx:1.13.8")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
