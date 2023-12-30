package com.lookupcat.bilibiliannouncer

import org.jetbrains.skiko.hostOs
import java.io.File
import kotlin.reflect.KProperty

object AppFiles {

  val appDir by lazy {
    val dir = when {
      !BuildConfig.isDebug && hostOs.isWindows -> File(System.getenv("LOCALAPPDATA"), "bilibili-announcer")
      else -> File(System.getProperty("user.dir"))
    }
    dir.mkdirs()
    dir
  }
  val data: File by directory(appDir, "data")
  val voiceCache: File by directory(data, "voiceCache")
  val voiceQueue: File by directory(data, "voiceQueue")
  val config: File = data / "config.json"

  private fun directory(child: String) = Directory(null, child)
  private fun directory(parent: File, child: String) = Directory(parent, child)

  class Directory(parent: File? = null, child: String) {
    private val file = File(parent, child)

    init {
      if (!file.exists()) {
        file.mkdirs()
      }
    }

    operator fun getValue(thisRef: Any, property: KProperty<*>): File {
      return file
    }

  }
}

operator fun File.div(child: String) = File(this, String(child.toByteArray(Charsets.UTF_8)))
fun File.deleteFiles() {
  listFiles()?.forEach { if (it.isFile) it.delete() }
}
