import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Firebase client config comes from androidApp/google-services.json (gitignored: it holds the project's API key),
// turned into a generated Kotlin object so common code on Android and iOS can use it.
val generatedConfigDir = layout.buildDirectory.dir("generated/firebaseConfig/kotlin")
val generateFirebaseConfig by tasks.registering {
    val source = rootProject.file("androidApp/google-services.json")
    val outDir = generatedConfigDir.get().asFile // plain locals: the configuration cache can't hold script references
    inputs.files(source).optional()
    outputs.dir(outDir)
    doLast {
        require(source.exists()) { "Missing androidApp/google-services.json (download it from the Firebase console)." }
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(source) as Map<String, Any?>
        val projectId = (json["project_info"] as Map<String, Any?>)["project_id"] as String
        val client = (json["client"] as List<Map<String, Any?>>).first()
        val apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"] as String
        val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String
        val senderId = (json["project_info"] as Map<String, Any?>)["project_number"] as String
        val out = File(outDir, "com/rimapps/fetchit/data/FirebaseConfig.kt")
        out.parentFile.mkdirs()
        out.writeText(
            """
            |package com.rimapps.fetchit.data
            |
            |/** Generated from google-services.json. Do not edit. */
            |internal object FirebaseConfig {
            |    const val PROJECT_ID = "$projectId"
            |    const val API_KEY = "$apiKey"
            |    /** Android app id and FCM sender, for initializing the native Firebase SDK (messaging, App Check). */
            |    const val ANDROID_APP_ID = "$appId"
            |    const val SENDER_ID = "$senderId"
            |    const val REGION = "us-central1"
            |}
            |""".trimMargin()
        )
    }
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    android {
       namespace = "com.rimapps.fetchit.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
       withDeviceTestBuilder {
           sourceSetTreeName = "test"
       }.configure {
           instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
       }
    }
    
    sourceSets {
        commonMain {
            kotlin.srcDir(generateFirebaseConfig)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.biometric)
            implementation(libs.androidx.browser)
            implementation(libs.androidx.exifinterface)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.messaging)
            implementation(libs.kotlinx.coroutines.playServices)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}