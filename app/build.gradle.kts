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
        versionCode   = 1050
        versionName   = "1046"

        externalNativeBuild {
            cmake {
cppFlags("-std=c++20 -O3 -flto=thin -fstack-protector-strong")
cFlags  ("-O3 -flto=thin -fstack-protector-strong")
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

        buildConfigField("String", "VERSION_NAME", "\"0.5\"")
        buildConfigField("int", "VERSION_CODE", "1")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // ── Product flavors ─────────────────────────────────────────────────────
    // "standard"     — full performance build. Compile-time baseline assumes
    //                  armv8.2-a+dotprod (safe for Exynos 9825, Exynos 2200,
    //                  Snapdragon 8 Elite and basically every device released
    //                  after ~2018). This is what most users should install.
    //
    // "compatibility" — maximum-safety build for old / unusual / low-end
    //                  ARM64 devices where even dotprod at the compiler
    //                  baseline level is a risk (custom ROMs, rare SoCs,
    //                  very early arm64-v8a chips). Pure armv8-a baseline,
    //                  zero compile-time CPU feature assumptions — every
    //                  optimized code path is gated behind a runtime HWCAP
    //                  check inside ggml/llama.cpp itself, so the binary
    //                  degrades gracefully instead of hitting SIGILL.
    //                  Also forces conservative runtime defaults (smaller
    //                  context, fewer threads, flash attention off,
    //                  GPU layers off) so first-run never OOMs either.
    flavorDimensions += "compatibility"
    productFlavors {
        create("standard") {
            dimension = "compatibility"
            buildConfigField("boolean", "IS_COMPAT_BUILD", "false")
            buildConfigField("int", "SAFE_DEFAULT_CTX", "4096")
            buildConfigField("int", "SAFE_DEFAULT_THREADS", "4")
            externalNativeBuild {
                cmake {
                    arguments("-DZC_ARCH_PROFILE=standard")
                }
            }
        }
        create("compatibility") {
            dimension = "compatibility"
            applicationIdSuffix = ".compat"
            versionNameSuffix = "-compat"
            resValue("string", "app_name", "ZeroCopy Compat")
            buildConfigField("boolean", "IS_COMPAT_BUILD", "true")
            // Lower default context + thread count so first launch on a
            // 2-4 GB RAM device (Note 10 Lite class) can't OOM before the
            // user even gets to Settings to tune it down themselves.
            buildConfigField("int", "SAFE_DEFAULT_CTX", "2048")
            buildConfigField("int", "SAFE_DEFAULT_THREADS", "2")
            externalNativeBuild {
                cmake {
                    arguments("-DZC_ARCH_PROFILE=compatibility")
                }
            }
        }
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

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.litertlm.android)
    implementation(libs.mlkit.text.recognition)

    debugImplementation(libs.compose.ui.tooling)



    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
}
