plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "com.gguf.zerocopy"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.gguf.zerocopy"
        minSdk        = 29
        targetSdk     = 36
        versionCode   = 13
        versionName   = "1.0.2"

        externalNativeBuild {
            cmake {
cppFlags("-std=c++20 -O3 -flto=thin -march=armv8-a+dotprod -fstack-protector-strong")
cFlags  ("-O3 -flto=thin -march=armv8-a+dotprod -fstack-protector-strong")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DGGML_BACKEND_DL=OFF"
                )
                abiFilters += "arm64-v8a"
            }
        }

        buildConfigField("String", "VERSION_NAME", "\"1.0.2\"")
        buildConfigField("int", "VERSION_CODE", "13")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.litertlm.android)

    implementation(project(":zerocopy-lib"))

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
}
