import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
    id("org.jetbrains.compose") version "1.8.2"
}

group = "com.ht.streamdesk"
version = "1.0.0"

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.1")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

compose.desktop {
    application {
        mainClass = "com.ht.streamdesk.MainKt"

        // 本地运行时的 JVM 参数（Windows 上中文路径/字体无需额外处理）
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        // macOS 上 gradlew run 的 Dock 图标。
        // 注意：-Xdock:icon 是 macOS 专用参数，Windows/Linux 的 JVM 不识别会直接启动失败
        //（实测 Windows JDK 报 Unrecognized option），必须按平台条件添加
        if (org.gradle.internal.os.OperatingSystem.current().isMacOsX) {
            jvmArgs += "-Xdock:icon=${rootProject.file("packaging/streamdesk-icon.png").absolutePath}"
        }

        // macOS 上若 ~/.skiko 解压被系统拦截（Operation not permitted），
        // 可把 skiko dylib 预先解压到 .skiko-libs/，通过该参数跳过运行时解压；Windows 不受影响
        rootDir.resolve(".skiko-libs").takeIf { it.isDirectory }?.let {
            jvmArgs += "-Dskiko.library.path=${it.absolutePath}"
        }

        nativeDistributions {
            // 多平台打包：macOS 上执行 packageDmg，Windows 上执行 packageMsi / packageExe
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "PacketCaptureDesk"
            packageVersion = "1.0.0"
            description = "Packet Capture 桌面同步面板"
            vendor = "ht.stream"

            // 应用图标（Windows 打包时自动转 ico）
            windows {
                iconFile.set(rootProject.file("packaging/streamdesk-icon.png"))
                menu = true
                // 不设置 menuGroup 时 jpackage 默认放「未知」文件夹，指定一个干净的分组名
                menuGroup = "Packet Capture"
                shortcut = true
                // 固定 UUID，保证后续版本可原地升级（不要随意更改）
                upgradeUuid = "6f3c1a54-8f2e-4b0d-9c47-2a5e7d1b8f30"
                dirChooser = true
            }
        }
    }
}
