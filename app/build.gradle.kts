import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val airKeystore = file("aircontrol.keystore")
val appVersionName = providers.gradleProperty("VERSION_NAME").orElse("0.1.3")
val appVersionCode = providers.gradleProperty("VERSION_CODE").orElse("4")

android {
    namespace = "com.ikegami99.aircontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ikegami99.aircontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get().toInt()
        versionName = appVersionName.get()
    }

    signingConfigs {
        create("air") {
            storeFile = airKeystore
            storePassword = "aircontrol"
            keyAlias = "aircontrol"
            keyPassword = "aircontrol"
        }
    }

    buildTypes {
        debug {
            if (airKeystore.exists()) {
                signingConfig = signingConfigs.getByName("air")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("air")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val modelAsset = layout.projectDirectory.file("src/main/assets/gesture_recognizer.task").asFile
val downloadGestureModel by tasks.registering {
    outputs.file(modelAsset)
    doLast {
        if (!modelAsset.exists() || modelAsset.length() < 1_000_000L) {
            modelAsset.parentFile.mkdirs()
            val url = URI("https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task").toURL()
            println("Downloading MediaPipe gesture model...")
            url.openStream().use { input ->
                modelAsset.outputStream().use { output -> input.copyTo(output) }
            }
            check(modelAsset.length() > 1_000_000L) { "gesture_recognizer.task download failed" }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadGestureModel)
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("com.google.mediapipe:tasks-vision:1.0.0")
}
