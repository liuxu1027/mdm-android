plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mdm.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mdm.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // UniFFI Kotlin 依赖 JNA 调用原生 .so
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}

// libmdm_android.so 链接了 NDK 的 C++ 共享运行时，APK 里必须自带它，
// 否则启动时 dlopen 失败（libc++_shared.so not found）直接闪退。
// 构建前从 NDK 里把它拷到 jniLibs，让 Gradle 打进 APK。
// 注意：不要用 android.ndkDirectory，它在 CI 上会报 "NDK is not installed"；
// 直接读 workflow 里设置的 ANDROID_NDK_HOME 环境变量。
tasks.register("copyLibcxxShared") {
    doLast {
        val ndkHome = System.getenv("ANDROID_NDK_HOME")
            ?: System.getenv("ANDROID_NDK_ROOT")
            ?: System.getenv("ANDROID_HOME")?.let { home ->
                File(home, "ndk").listFiles()?.maxByOrNull { f -> f.name }?.absolutePath
            }
            ?: throw GradleException("找不到 NDK：ANDROID_NDK_HOME 未设置")
        val src = File(
            ndkHome,
            "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
        )
        if (!src.exists()) {
            throw GradleException("找不到 libc++_shared.so：${src.absolutePath}")
        }
        val destDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile
        destDir.mkdirs()
        src.copyTo(destDir.resolve("libc++_shared.so"), overwrite = true)
        println("已复制 libc++_shared.so 到 jniLibs")
    }
}

tasks.named("preBuild") {
    dependsOn("copyLibcxxShared")
}
