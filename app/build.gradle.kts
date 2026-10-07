plugins {
    id("com.android.application")
}
android {
    namespace = "dev.edgeai.prototype"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.edgeai.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            it.systemProperty("gliner.moduleRoot", rootProject.projectDir.absolutePath)
            it.systemProperty("gliner.buildDir", layout.buildDirectory.get().asFile.absolutePath)
        }
    }
}
dependencies {
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("com.google.ai.edge.litert:litert:2.2.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}