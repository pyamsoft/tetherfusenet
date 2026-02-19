/*
 * Copyright 2025 pyamsoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  // Can't use alias() or we get some weird error about double Android on classpath?
  id(libs.plugins.android.application.get().pluginId)

  alias(libs.plugins.ksp)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.android.cacheFix)
}

android {
  namespace = "com.pyamsoft.tetherfi"

  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    applicationId = "com.pyamsoft.tetherfi"

    versionCode = 66
    versionName = "20260219-1"

    minSdk = libs.versions.minSdk.get().toInt()
    targetSdk = libs.versions.targetSdk.get().toInt()

    vectorDrawables.useSupportLibrary = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    // Flag to enable support for the new language APIs
    isCoreLibraryDesugaringEnabled = true
  }

  signingConfigs {
    named("debug") {
      storeFile = file("debug.keystore")
      keyAlias = "androiddebugkey"
      keyPassword = "android"
      storePassword = "android"
    }
    create("release") {
      // For some reason with Gradle 8.5 and AGP 8.2.0 we need to load local.properties manually?
      // https://stackoverflow.com/questions/21999829/how-do-i-read-properties-defined-in-local-properties-in-build-gradle
      //
      // Be sure to close the file after!
      val properties =
          rootProject.file("local.properties").reader().use { r -> Properties().apply { load(r) } }

      storeFile = file(properties.getProperty("BUNDLE_STORE_FILE") ?: "CANNOT BUILD")
      keyAlias = properties.getProperty("BUNDLE_KEY_ALIAS") ?: "CANNOT BUILD"
      keyPassword = properties.getProperty("BUNDLE_KEY_PASSWD") ?: "CANNOT BUILD"
      storePassword = properties.getProperty("BUNDLE_STORE_PASSWD") ?: "CANNOT BUILD"
    }
  }

  // https://developer.android.com/build/build-variants
  flavorDimensions += listOf("store")

  productFlavors {
    create("fdroid") {
      dimension = "store"

      // https://github.com/pyamsoft/tetherfusenet/issues/307
      dependenciesInfo {
        includeInApk = false
        includeInBundle = false
      }
    }

    create("google") {
      dimension = "store"

      // https://github.com/pyamsoft/tetherfusenet/issues/307
      dependenciesInfo {
        includeInApk = true
        includeInBundle = true
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

      // https://developer.android.com/build/shrink-code#native-crash-support
      // androidx.graphics.path includes native code now?
      ndk { debugSymbolLevel = "FULL" }
    }

    debug {
      signingConfig = signingConfigs.getByName("debug")
      applicationIdSuffix = ".dev"
      versionNameSuffix = "-dev"

      // https://developer.android.com/build/shrink-code#native-crash-support
      // androidx.graphics.path includes native code now?
      ndk { debugSymbolLevel = "FULL" }
    }
  }

  buildFeatures {
    buildConfig = true
    compose = true
  }

  // Fixes this error message
  // More than one file was found with OS independent path "META-INF/core_release.kotlin_module"
  packaging {
    resources.pickFirsts +=
        setOf(
            "META-INF/core_release.kotlin_module",
            "META-INF/ui_release.kotlin_module",
            "META-INF/INDEX.LIST",
            "META-INF/io.netty.versions.properties",
        )
  }
}

kotlin {
  compilerOptions {
    languageVersion = KotlinVersion.KOTLIN_2_3
    jvmTarget = JvmTarget.JVM_21
  }
}

// Leave at bottom
// apply plugin: "com.google.gms.google-services"
dependencies {
  coreLibraryDesugaring(libs.android.desugar)

  ksp(libs.dagger.compiler)

  // Leak Canary
  debugImplementation(libs.leakcanary)
  implementation(libs.leakcanary.plumber)

  // AndroidX
  implementation(libs.androidx.activity.compose)

  // Needed just for androidx.preference.PreferenceManager
  // Eventually, big G may push for DataStore being a requirement, which will be pain
  // This pulls in all the UI bits too, which is a little lame.
  implementation(libs.androidx.preference)

  // DataStore
  implementation(libs.androidx.dataStore)

  // PYDroid
  implementation(libs.pydroid.notify)
  implementation(libs.pydroid.ui)

  implementation(project(":behavior"))
  implementation(project(":connections"))
  implementation(project(":core"))
  implementation(project(":info"))
  implementation(project(":main"))
  implementation(project(":server"))
  implementation(project(":service"))
  implementation(project(":settings"))
  implementation(project(":status"))
  implementation(project(":tile"))
  implementation(project(":ui"))
}