
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
    id("org.jetbrains.compose") version "1.2.0"
}

group = "com.lookupcat"
version = "1.0"

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
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
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.0")
    compileOnly("org.jetbrains.kotlinx:atomicfu:0.18.3")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
//    kotlinOptions.freeCompilerArgs += "dontwarn org.jetbrains.skiko.*"
//    kotlinOptions.freeCompilerArgs += "dontwarn ch.qos.logback.*"
//    kotlinOptions.freeCompilerArgs = listOf("-dontwarn")
}

compose.desktop {
    application {
        mainClass = "com.lookupcat.bilibiliannouncer.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "bilibili-announcer"
            packageVersion = "1.0.0"
            windows {
                macOS {
                    iconFile.set(project.file("src/main/resources/icon.png"))
                }
                windows {
                    iconFile.set(project.file("src/main/resources/icon.png"))
                }
                linux {
                    iconFile.set(project.file("src/main/resources/icon.png"))
                }
            }
        }
    }
}