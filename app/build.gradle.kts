plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Room 注解处理（浏览历史等本地表）
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries.android)
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
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Debug 包与 Release 包共存：org.kazusa.matter.debug
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
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
