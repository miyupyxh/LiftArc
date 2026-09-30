import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lockbar.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lockbar.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 118
        versionName = "1.0.18"
    }

    // 应用本身只有中文，去掉各大库带的几十种语言翻译
    androidResources {
        localeFilters.add("zh")
    }

    // 正式签名：密码与路径放在仓库根目录的 keystore.properties（.gitignore 已挡，绝不上传）。
    // 没有这个文件时（别人 clone 下来）回退 debug 签名，保证开箱能构建。
    var hasReleaseKeystore = false
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                val storePath = props.getProperty("storeFile")
                if (!storePath.isNullOrEmpty()) {
                    val f = File(storePath)
                    storeFile = if (f.isAbsolute) f else rootProject.file(storePath)
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                    storeType = props.getProperty("storeType") ?: "PKCS12"
                    hasReleaseKeystore = true
                }
            }
        }
    }

    buildTypes {
        release {
            // 裁剪 + 混淆：material-icons-extended 一家就有 34MB，全靠 R8 砍
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有正式 keystore 就用它；否则退回 debug 签名（贡献者 clone 后可直接构建）
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            // 也开 R8：平时跑 assembleDebug 出来的同样是个小包
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        // miuix-nav 0.9.4 是按 JVM 21 编译的，它的 `entry<T>{}` 是 inline 函数，
        // 目标低于 21 会报 “Cannot inline bytecode built with JVM target 21”
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            // 保留 libxposed 的模块描述文件（META-INF/xposed/*）
            excludes -= setOf("META-INF/xposed/**")
            pickFirsts += "META-INF/*.version"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)

    // 运行时由 LSPosed 提供，仅编译期使用
    compileOnly(libs.libxposed.api)
    // 模块 App 进程同步配置到 LSPosed
    implementation(libs.libxposed.service)
}
