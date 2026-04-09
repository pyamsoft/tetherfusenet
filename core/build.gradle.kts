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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  alias(libs.plugins.ksp)
  alias(libs.plugins.android)
  alias(libs.plugins.android.cacheFix)
}

android {
  namespace = "com.pyamsoft.tetherfi.core"

  compileSdk = libs.versions.compileSdk.get().toInt()

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()

    // Android Testing
    // https://developer.android.com/training/testing/instrumented-tests
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21

    // Flag to enable support for the new language APIs
    isCoreLibraryDesugaringEnabled = true
  }

  buildFeatures { buildConfig = false }
}

kotlin {
  compilerOptions {
    languageVersion = KotlinVersion.KOTLIN_2_3
    jvmTarget = JvmTarget.JVM_21
  }
}

dependencies {
  coreLibraryDesugaring(libs.android.desugar)

  ksp(libs.dagger.compiler)

  api(libs.kotlinx.coroutines)

  api(libs.timber)

  // Compose runtime for annotations
  implementation(libs.compose.runtime.annotation)

  // Android support library.
  api(libs.androidx.core)

  // Dagger
  api(libs.dagger)

  // TODO(Peter): Remove in favor of slim shared module
  api(libs.pydroid.notify)

  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(libs.androidx.testRunner)
  androidTestImplementation(libs.kotlin.test)
  androidTestImplementation(libs.kotlinx.coroutines.test)
}
