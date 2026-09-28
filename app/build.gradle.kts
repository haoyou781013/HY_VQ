import java.util.Properties

plugins {
    id("com.android.application")
    
}

// ── 发布签名配置 ──
// keystore.properties 存放密钥路径与口令，已被 .gitignore 排除，绝不入库。
// 文件缺失时（如 CI 或其他机器）自动回退为「不签名」，不影响普通构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.aliya.hy_vq"
    compileSdk = 33

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    
    defaultConfig {
        applicationId = "com.aliya.hy_vq"
        // 版本适配策略：支持 Android 9 (API 28) 及以上；
        // compileSdk/targetSdk 保持 33，暂不适配 Android 17 (API 37)
        minSdk = 28
        targetSdk = 33
        versionCode = 48
        versionName = "2.10.3"
        
        vectorDrawables { 
            useSupportLibrary = true
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // 关闭 PNG crunching：res/mipmap-*/ic_launcher.png 实际是 JPEG 内容
            // （魔数 ffd8ff），aapt2 的 crunch 路径会正确地拒绝它。
            // 关闭后与 debug 构建行为一致，且不改动资源文件。
            isCrunchPngs = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        
    }
    
}

dependencies {
    // ── AndroidX 基础 ──
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ── 网络：OkHttp + WebSocket ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── JSON 解析 ──
    implementation("com.google.code.gson:gson:2.10.1")

    // ── 图片加载 ──
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")

}