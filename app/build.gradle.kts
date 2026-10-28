plugins {
    id("com.android.application")
    // AGP 9 起 Kotlin 支持内建，不再应用 org.jetbrains.kotlin.android 插件
}

android {
    namespace = "com.dukangalex.angelacam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dukangalex.angelacam"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.6.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 正式签名配置：keystore 由 CI 从 Secrets 还原，密码走环境变量，绝不进仓库
    // 本地构建无环境变量时不创建，release 包保持未签名（不影响调试）
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                // 相对仓库根目录解析（workflow 把 keystore 还原到 <root>/app/release.keystore）
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 注意：CameraX 没有 BOM，各组件用统一版本号
    val cameraxVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    testImplementation("junit:junit:4.13.2")
}
