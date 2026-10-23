import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Room 注解处理（浏览历史等本地表）
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries.android)
}

// 显示版本由 version.properties 手工维护，内部编号由 Git 提交数派生。
// 构建本身从不修改任何计数：同一提交在任何机器上得到同一个 versionCode。
// 正式发布只由 GitHub Actions 触发（.github/workflows/release.yml）。
val appVersionProperties = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("version.properties")).asText.get().reader())
}

fun versionSegment(key: String): Int {
    val raw = appVersionProperties.getProperty(key, "").trim()
    val value = raw.toIntOrNull()
    require(value != null && value >= 0) { "$key must be a non-negative integer, was '$raw'" }
    require(raw == value.toString()) { "$key must not have leading zeros, was '$raw'" }
    return value
}

val versionMajor = versionSegment("VERSION_MAJOR")
val versionMinor = versionSegment("VERSION_MINOR")
val versionPatch = versionSegment("VERSION_PATCH")
val versionSuffix = versionSegment("VERSION_SUFFIX")
require(versionSuffix == 0 || versionSuffix == 1) { "VERSION_SUFFIX must be 0 (release) or 1 (prerelease)" }

val baseVersionName = "$versionMajor.$versionMinor.$versionPatch"
// 预发布序号 -beta.N 需要已有 tag 才能计算，由 CI 通过 -PmatterVersionName 传入；
// 本地预发布构建固定为 -beta.local，避免与线上已发布版本重名。
val appVersionName = providers.gradleProperty("matterVersionName")
    .getOrElse(if (versionSuffix == 1) "$baseVersionName-beta.local" else baseVersionName)
    .trim()

// 提交数是仓库属性：本地与 CI 一致，无需回写文件，也不存在并发分配。
// 注意 --depth 1 的浅克隆只会得到 1，CI 必须 fetch-depth: 0。
val gitCommitCount = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrNull()

val appVersionCode = providers.gradleProperty("matterVersionCode").orNull?.trim()?.toIntOrNull()
    ?: gitCommitCount
    ?: 1
require(appVersionCode in 1..2_100_000_000) { "versionCode must be between 1 and 2100000000, was $appVersionCode" }
require(Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.([1-9][0-9]*|local))?""").matches(appVersionName)) {
    "versionName must be X.Y.Z or X.Y.Z-alpha.N / beta.N / rc.N"
}

aboutLibraries {
    collect {
        // BOM 用于约束版本，不作为应用中的组件展示。
        includePlatform = false
    }
}

android {
    namespace = "org.kazusa.matter"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.kazusa.matter"
        // 只支持 Android 12（API 31）及以上：动图解码、分区存储、动态取色、
        // 启动画面等能力都可以直接依赖平台 API，不再保留低版本退回分支
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名材料只从 Gradle 属性读取（CI 由 secret 解码后追加到 gradle.properties）。
    // 没有密钥时保持未签名构建，日常开发、单测与 IDE 同步都不受影响。
    signingConfigs {
        if (project.hasProperty("RELEASE_STORE_FILE")) {
            create("release") {
                storeFile = rootProject.file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        debug {
            // Debug 包与 Release 包共存：org.kazusa.matter.debug
            // 应用名与 release 一致（不带 D），桌面/抽屉里靠黄色图标辨认（见 src/debug/res）
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (project.hasProperty("RELEASE_STORE_FILE")) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Room 导出版本化 schema，方便后续写 Migration 时有据可查
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Gradle 构建缓存在打包 Kotlin 编译任务的 classpath-snapshot 目录时，
// 会与 Kotlin 插件清理该目录的动作产生竞态，偶发报
// "Could not get file mode ... shrunk-classpath-snapshot.bin" 直接导致构建失败
// （构建失败后 IDE 可能保留旧 APK，表现为"改了代码但装上去没变化"）。
// 这里只关闭 Kotlin 编译任务的缓存，其余任务仍保留缓存收益。
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    outputs.cacheIf { false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation 3 with saved entry state and per-entry ViewModels
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Room 3（本地数据库：浏览历史等）
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Kotlinx Serialization & Coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Coil 3 Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)

    // MaterialKolor Dynamic Color
    implementation(libs.material.kolor)

    // Media3 ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // AndroidLiquidGlass (Kyant0 Backdrop)
    implementation(libs.backdrop)
    // 平滑圆角（G2 连续曲率），液态玻璃统一使用
    implementation(libs.shapes)

    // Reorderable Drag-and-drop List
    implementation(libs.reorderable)

    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
    testImplementation("org.robolectric:robolectric:4.17")
}

tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
