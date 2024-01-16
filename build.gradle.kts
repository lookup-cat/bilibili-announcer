
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.util.capitalizeDecapitalize.toUpperCaseAsciiOnly
import java.util.*

val appVersion = "2.0.0"
val appName = "bilibili-announcer"
val projectUrl = "https://github.com/lookup-cat/bilibili-announcer"
val isDebug = gradle.startParameter.taskNames.none { it.contains("release", ignoreCase = true) }
val versionUuid = UUID.nameUUIDFromBytes(appVersion.toByteArray()).toString().toUpperCaseAsciiOnly()

plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
}

group = "com.lookupcat.bilibiliannouncer"
version = appVersion

repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io")
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("moe.sdl.yabapi:yabapi-core-jvm:0.11.2")
    implementation("io.ktor:ktor-client-cio-jvm:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("com.github.goxr3plus:java-stream-player:10.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.2.0")
    implementation("ch.qos.logback:logback-classic:1.4.12")
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
            modules("java.instrument", "java.management", "java.naming", "jdk.unsupported")
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = appName
            packageVersion = appVersion
            jvmArgs += listOf("-Xmx256M")
            val resources = project.file("src/main/resources")
            windows {
                macOS {
                    iconFile.set(resources.resolve("icon.icns"))
                    bundleID = "com.lookupcat.bilibiliannouncer"
                }
                windows {
                    iconFile.set(resources.resolve("icon.ico"))
                    shortcut = true
                    menu = true
                    // see https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                    upgradeUuid = versionUuid
                }
                linux {
                    iconFile.set(resources.resolve("icon.png"))
                }
            }
        }
        buildTypes.release.proguard {
            configurationFiles.from("proguard-rules.pro")
        }
    }
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(project.layout.buildDirectory.dir("generated"))
    }
}


tasks.register<GenerateBuildConfig>("generateBuildConfig") {
    generatedOutputDir.set(project.layout.buildDirectory.dir("generated/com/lookupcat/bilibiliannouncer"))
    classFqName.set("com.lookupcat.bilibiliannouncer.BuildConfig")
    fieldsToGenerate.put("appVersion", appVersion)
    fieldsToGenerate.put("isDebug", isDebug)
    fieldsToGenerate.put("appName", appName)
    fieldsToGenerate.put("projectUrl", projectUrl)
}

tasks["compileKotlin"].dependsOn("generateBuildConfig")

open class GenerateBuildConfig : DefaultTask() {
    @get:Input
    val fieldsToGenerate: MapProperty<String, Any> = project.objects.mapProperty()

    @get:Input
    val classFqName: Property<String> = project.objects.property()

    @get:OutputDirectory
    val generatedOutputDir: DirectoryProperty = project.objects.directoryProperty()

    @TaskAction
    fun execute() {
        val dir = generatedOutputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()

        val fqName = classFqName.get()
        val parts = fqName.split(".")
        val className = parts.last()
        val file = dir.resolve("$className.kt")
        val content = buildString {
            if (parts.size > 1) {
                appendLine("package ${parts.dropLast(1).joinToString(".")}")
            }

            appendLine()
            appendLine("/* GENERATED, DO NOT EDIT MANUALLY! */")
            appendLine("object $className {")
            for ((k, v) in fieldsToGenerate.get().entries.sortedBy { it.key }) {
                appendLine("\tconst val $k = ${if (v is String) "\"$v\"" else v.toString()}")
            }
            appendLine("}")
        }
        file.writeText(content)
    }
}