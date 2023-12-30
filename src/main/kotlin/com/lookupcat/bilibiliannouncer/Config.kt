package com.lookupcat.bilibiliannouncer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


object ConfigStorage {

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = true
  }
  private val logger = logger()

  fun save(config: Config) {
    logger.info("save config: $config")
    json.encodeToString(config).let {
      AppFiles.config.writeText(it)
    }
  }

  fun read(): Config {
    if (!AppFiles.config.exists()) {
      return Config()
    }
    val content = AppFiles.config.readText()
    return try {
      json.decodeFromString(content)
    } catch (e: Exception) {
      e.printStackTrace()
      val newConfig = Config()
      save(newConfig)
      newConfig
    }
  }
}

@Serializable
data class Config(
  /**
   * 音量配置 范围 0.0 ~ 1.0
   */
  val volume: Float = 0.5f,

  /**
   * 弹幕队列长度 必须大于0
   */
  val queueLength: Int = 5,

  /**
   * 音源id
   */
  val voiceId: Int = 0,

  /**
   * 房间号
   */
  val roomId: Long? = null,
)

class ConfigState(
  volume: Float,
  queueLength: Int,
  voiceId: Int,
  roomId: Long?,
) {
  var volume by mutableStateOf(volume)
  var voiceId by mutableStateOf(voiceId)
  var queueLength: Int by mutableStateOf(queueLength)
  var roomId: Long? by mutableStateOf(roomId)

  fun observe(scope: CoroutineScope, block: (ConfigState) -> Unit) {
    snapshotFlow { volume }.onEach { block(this) }.launchIn(scope)
    snapshotFlow { queueLength }.onEach { block(this) }.launchIn(scope)
    snapshotFlow { voiceId }.onEach { block(this) }.launchIn(scope)
    snapshotFlow { roomId }.onEach { block(this) }.launchIn(scope)
  }
}

fun ConfigState.toConfig() = Config(
  volume = volume,
  queueLength = queueLength,
  voiceId = voiceId,
  roomId = roomId
)

fun Config.toState() = ConfigState(
  volume = volume,
  queueLength = queueLength,
  voiceId = voiceId,
  roomId = roomId
)