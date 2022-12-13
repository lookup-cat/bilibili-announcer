
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
    id("org.jetbrains.compose") version "1.2.1"
}

group = "com.lookupcat"
version = "1.0"

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

buildscript {
    dependencies {
//        classpath("com.guardsquare:proguard-gradle:7.1.0")
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("moe.sdl.yabapi:yabapi-core-jvm:0.11.1")
    implementation("io.ktor:ktor-client-cio-jvm:2.1.2")
    implementation("com.github.goxr3plus:java-stream-player:10.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.2.0")
    implementation("ch.qos.logback:logback-classic:1.4.5")
    implementation("org.slf4j:slf4j-api:2.0.5")
    implementation("org.jetbrains.kotlinx:atomicfu:0.18.3")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

compose.desktop {
    application {
        mainClass = "com.lookupcat.bilibiliannouncer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "bilibili-announcer"
            packageVersion = "1.0.0"
            val resources = project.file("src/main/resources")
            windows {
                macOS {
                    iconFile.set(resources.resolve("icon.icns"))
                }
                windows {
                    iconFile.set(resources.resolve("icon.ico"))
                    shortcut = true
                    menu = true
                }
                linux {
                    iconFile.set(resources.resolve("icon.png"))
                }
            }
        }
        // 当前compose版本启用proguard存在一些未知问题: https://github.com/JetBrains/compose-jb/issues/2393
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}