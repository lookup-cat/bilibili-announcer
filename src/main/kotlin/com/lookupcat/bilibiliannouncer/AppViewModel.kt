package com.lookupcat.bilibiliannouncer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import moe.sdl.yabapi.BiliClient
import moe.sdl.yabapi.Yabapi
import moe.sdl.yabapi.api.createLiveDanmakuConnection
import moe.sdl.yabapi.api.getLiveDanmakuInfo
import moe.sdl.yabapi.api.getRoomInfoByRoomId
import moe.sdl.yabapi.connect.onCertificateResponse
import moe.sdl.yabapi.connect.onCommandResponse
import moe.sdl.yabapi.data.GeneralCode
import moe.sdl.yabapi.data.live.LiveResponseCode
import moe.sdl.yabapi.data.live.commands.DanmakuMsgCmd
import java.util.regex.Pattern

/**
 * 试听状态
 */
enum class AuditionStatus {
  LOADING,
  PLAYING,
  STOPPED
}


class AppViewModel(
  private val uiScope: CoroutineScope,
) {

  val logger = logger()

  var voice by mutableStateOf(VoiceInfo(0, ""))
  val voices = mutableListOf<VoiceInfo>()
  var started by mutableStateOf(false)
  var tryReconnect: Boolean = false
  val consoleLogger = mutableStateListOf<String>()
  val maxConsoleLogLine = 200
  var consoleState = LazyListState()
  var auditionStatus by mutableStateOf(AuditionStatus.STOPPED)
  val config = ConfigStorage.read().toState()


  private var auditionJob: Job? = null
  private val player = VitsPlayer(this::console, uiScope)


  val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var liveJob: Job? = null
  private val biliClient by lazy { BiliClient() }
  var saveJob: Job? = null

  init {
    val jsonParser = Json {
      ignoreUnknownKeys = true
      prettyPrint = true
      isLenient = true
      coerceInputValues = true
    }
    Yabapi.defaultJson.value = jsonParser
    config.observe(uiScope) {
      saveJob?.cancel()
      saveJob = ioScope.launch {
        delay(1000)
        ConfigStorage.save(it.toConfig())
      }
    }
    // read voice
    AppViewModel::class.java.classLoader.getResourceAsStream("voice.txt")?.use {
      it.bufferedReader().readLines().forEachIndexed { index, s ->
        voices.add(VoiceInfo(index, s))
      }
      voice = voices.first()
      logger.info("音源数量:{}", voices.size)
    }

    snapshotFlow { config.volume }.onEach { player.playerGain = it * 2.0 }.launchIn(uiScope)
    snapshotFlow { config.queueLength }.onEach { player.maxQueueLength = it }.launchIn(uiScope)
  }

  fun start(roomId: Long) {
    this.started = true
    this.tryReconnect = true
    liveJob = ioScope.launch {
      try {
        console("开始获取房间号")
        val info = biliClient.getRoomInfoByRoomId(roomId)
        val infoData = info.data
        if (info.code != GeneralCode.SUCCESS || infoData == null) {
          console("获取房间号失败")
          return@launch
        }
        console("开始获取直播间信息")
        val actualRoomId = infoData.roomId
        val danmaku = biliClient.getLiveDanmakuInfo(actualRoomId)
        val danmakuData = danmaku.data
        if (danmaku.code != LiveResponseCode.SUCCESS || danmakuData == null) {
          console("获取直播间信息失败")
          return@launch
        }
        console("标题: ${infoData.title}")
        console("开始连接直播间")
        biliClient.createLiveDanmakuConnection(
          0L,
          actualRoomId,
          danmakuData.token!!,
          danmakuData.hostList.random(),
        ) {
          onCertificateResponse {
            it.collect {
              console("连接直播间成功")
            }
          }
          onCommandResponse {
            val pattern = Pattern.compile("[\u4e00-\u9fa5]")
            it.collect { command ->
              if (command is DanmakuMsgCmd) {
                command.data?.content?.let { msg ->
                  val task = PlayTask(voice, msg, false)
                  // 必须包含中文2字以上
                  val matcher = pattern.matcher(msg)
                  val result = buildString {
                    while (matcher.find()) {
                      append(matcher.group())
                    }
                  }
                  if (result.chars().distinct().count() < 2) {
                    console("过滤弹幕 ${task.description}")
                    return@collect
                  }
                  logger.info("add task: $task")
                  player.addPlayTask(task)
                }
              } else {
                logger.debug("command: $command")
              }
            }
          }
        }.join()
      } catch (e: Exception) {
        console("error:${e.message}")
        logger.error(e.message, e)
      } finally {
        started = false
        if (tryReconnect) {
          ioScope.launch {
            console("直播间连接异常 500ms后重新连接")
            delay(500)
            start(roomId)
          }
        } else {
          console("直播间连接关闭")
        }
      }
    }
  }

  fun stop() {
    tryReconnect = false
    liveJob?.cancel()
    player.cancelAll()
  }

  fun console(msg: String) {
    uiScope.launch {
      consoleLogger.add(msg)
      if (consoleLogger.size > maxConsoleLogLine) {
        consoleLogger.removeFirst()
      }
      delay(100)
      consoleState.animateScrollToItem(consoleLogger.size - 1)
    }
  }

  /**
   * 试听
   */
  fun startAudition() {
    uiScope.launch {
      val task = PlayTask(voice, "旅行者你好, 我是派蒙弹幕姬", true) {
        when (it) {
          PlayTextStatus.DOWNLOADING -> {
            auditionStatus = AuditionStatus.LOADING
          }

          PlayTextStatus.PLAYING -> {
            auditionStatus = AuditionStatus.PLAYING
          }

          PlayTextStatus.COMPLETED -> {
            auditionStatus = AuditionStatus.STOPPED
          }

          else -> {

          }
        }
      }
      auditionJob = player.addPlayTask(task)
    }
  }

  /**
   * 停止试听
   */
  fun stopAudition() {
    uiScope.launch {
      logger.info("cancel auditionJob")
      auditionJob?.cancel()
    }
  }

}

