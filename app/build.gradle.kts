import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin 是 Compose 的前置条件；Kotlin 2.0 起 Compose 编译器随插件分发，
    // 无需再手动配对 kotlinCompilerExtensionVersion
    id("org.jetbrains.kotlin.android") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0"
}

// ── 发布签名配置 ──
// keystore.properties 存放密钥路径与口令，已被 .gitignore 排除，绝不入库。
// 文件缺失时（如 CI 或其他机器）自动回退为「不签名」，不影响普通构建。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// ── 版本号唯一来源：仓库根的 versions.json ──
// 此前 versionCode/versionName 写死在本文件里，与 tag 长期不同步
// （tag 已到 v2.42.0-beta.10，本文件却停在 vc=120 / 2.39.0-beta.6）。
// 现改为从 versions.json 的 versions[0]（最新一条）读取，单一事实来源。
// 优先级：-PappVersionCode / -PappVersionName（命令行覆盖） > versions.json > 兜底值。
val versionsJsonFile = rootProject.file("versions.json")
val (autoVersionCode, autoVersionName) = run {
    val fallback = 1 to "0.0.0"
    if (!versionsJsonFile.exists()) {
        logger.warn("versions.json 不存在，版本号回退为 ${fallback.second} (${fallback.first})")
        return@run fallback
    }
    try {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(versionsJsonFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val versions = root["versions"] as? List<Map<String, Any?>>
        val latest = versions?.firstOrNull()
        if (latest == null) {
            logger.warn("versions.json 中 versions 为空，版本号回退")
            fallback
        } else {
            val vc = (latest["versionCode"] as? Number)?.toInt()
            val vn = latest["ver"] as? String
            if (vc == null || vn.isNullOrBlank()) {
                logger.warn("versions.json 首条缺少 versionCode/ver，版本号回退")
                fallback
            } else vc to vn
        }
    } catch (e: Exception) {
        logger.warn("解析 versions.json 失败（${e.message}），版本号回退")
        fallback
    }
}
val resolvedVersionCode: Int =
    (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: autoVersionCode
val resolvedVersionName: String =
    (project.findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: autoVersionName

android {
    namespace = "com.aliya.hy_vq"
    // compileSdk 34 是 Compose 的需要；AGP 8.0.0 的官方支持上限是 33，
    // 故用 suppression 消除「recommend newer AGP」提示，不做降级。
    @Suppress("UnstableApiUsage")
    compileSdk = 34

    buildFeatures {
        compose = true
    }

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
        // targetSdk 保持 33（compileSdk 已升 34 以支持 Compose），暂不适配 Android 17 (API 37)
        minSdk = 28
        targetSdk = 33
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        
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
        // 需要 BuildConfig.DEBUG 做「仅 debug 生效」的守卫（插件自测入口）
        buildConfig = true
    }
    
    lint {
        lintConfig = file("lint.xml")
        // 不在 release 打包时跑 lintVital：AGP 8.0 的 lintVital 内置 Kotlin 1.7.x 编译器，
        // 读 Kotlin 2.0 编译的依赖元数据（kotlin-stdlib/okio）必然刷
        // "Module was compiled with an incompatible version of Kotlin" 的 e: 日志，
        // 与本项目代码无关。lint 需要时用 `gradlew lint` 单独跑。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // ── Shizuku：以 adb(shell) 权限执行命令，用于读取系统日志提取抽卡链接 ──
    // 项目需离线构建，故 AAR 预置于 app/libs（非 Maven 联网拉取）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // ── AndroidX 基础 ──
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // ── ZIP 加密支持（zip4j）：java.util.zip 不支持 ZipCrypto/AES 解密，
    //    项目此前走零依赖路线，但加密包密码解出必须引入此库 ──
    implementation("net.lingala.zip4j:zip4j:2.11.5")

    // ── 网络：OkHttp + WebSocket ──
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── JSON 解析 ──
    implementation("com.google.code.gson:gson:2.10.1")

    // ── 媒体播放：Jetpack Media3 / ExoPlayer ──
    // 版本上限受 compileSdk 33 约束：1.1.1 的 minCompileSdk=33，1.2.0+ 要求 34。
    // 依赖走本地 Maven 仓库固化（/opt/local-maven），保持项目"离线构建"约定。
    implementation("androidx.media3:media3-exoplayer:1.1.1")
    implementation("androidx.media3:media3-ui:1.1.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.1.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.1.1")

    // ── HTML 解析：jsoup ──
    // 用于解析番剧源页面。ani 源规则基于 CSS selector（不是 XPath），jsoup 原生支持。
    // zero-dependency 纯 Java 库；同样走本地 Maven 仓库固化。
    implementation("org.jsoup:jsoup:1.17.2")

    // ── 图片加载 ──
    implementation("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor("com.github.bumptech.glide:compiler:4.16.0")


    // ── Compose（仅新页面用；旧页面仍为 XML View，渐进迁移）──
    // 版本取 compileSdk 34 可用集；经 aliyun/google 仓可达，已实测 7/7 200
    implementation("androidx.compose.ui:ui:1.6.6")
    implementation("androidx.compose.foundation:foundation:1.6.6")
    implementation("androidx.compose.animation:animation:1.6.6")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
}
// Kotlin 与 Java 的 JVM 目标必须一致（AGP 会严格校验）：统一到 11
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
}
