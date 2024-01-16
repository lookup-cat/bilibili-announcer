package com.lookupcat.bilibiliannouncer

import com.goxr3plus.streamplayer.enums.Status
import com.goxr3plus.streamplayer.stream.StreamPlayer
import com.goxr3plus.streamplayer.stream.StreamPlayerEvent
import com.goxr3plus.streamplayer.stream.StreamPlayerListener
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume


typealias PlayTextStatusListener = (PlayTextStatus) -> Unit

data class PlayTask(
  val voice: VoiceInfo,
  val msg: String,
  val cache: Boolean,
  val listener: PlayTextStatusListener = {}
) {
  val description: String = "[${voice.name}]: $msg"
}


/**
 * 线程安全的VITS播放器
 */
class VitsPlayer(
  console: (String) -> Unit,
  private val uiScope: CoroutineScope
) {

  // 音量 0.0 ~ 2.0
  var playerGain: Double = 1.0
    set(value) {
      streamPlayer.setGain(value)
      field = value
    }

  // 播放队列长度
  var maxQueueLength: Int = 5
    set(value) {
      field = value
      logger.info("set maxQueueLength: $maxQueueLength")
    }

  // 单线程调度器
  private val scope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())

  // 当前排队播放数量
  private var currentQueueCount: Int = 0

  private val logger = logger()

  // 最大同时下载数量
  private val downloadSemaphore = Semaphore(3)

  // 播放锁
  private val streamPlayerMutex = Mutex()

  private val streamPlayer = StreamPlayer()

  private val playJobs = ConcurrentHashMap<PlayTask, Job>()

  private val safeConsole = { msg: String ->
    uiScope.launch {
      console(msg)
    }
  }

  private val httpClient = HttpClient {
    install(HttpTimeout) {
      requestTimeoutMillis = 30000
      connectTimeoutMillis = 5000
    }
    install(ContentNegotiation) {
      json()
    }
  }

  init {
    AppFiles.voiceQueue.deleteRecursively()
  }

  suspend fun addPlayTask(task: PlayTask): Job {
    streamPlayerMutex.withLock {
      val job = scope.launch {
        val safeListener = task.listener.safe()
        try {
          // 计算队列是否已满
          if (currentQueueCount >= maxQueueLength) {
            logger.info("play queue is full, cancel play: ${task.description}")
            safeConsole("丢弃弹幕 ${task.description}")
            return@launch
          }
          currentQueueCount++
          //下载
          val file = downloadSemaphore.withPermit {
            safeListener(PlayTextStatus.DOWNLOADING)
            logger.info("start downloading ${task.description}")
            val file = download(task)
            safeListener(PlayTextStatus.DOWNLOADED)
            logger.info("download completed ${task.description}")
            file
          }
          //播放
          streamPlayerMutex.withLock {
            safeConsole(task.description)
            safeListener(PlayTextStatus.PLAYING)
            logger.info("start play ${task.description}")
            play(task, file)
            logger.info("play completed ${task.description}")
            // 等待0.5s后再释放锁
            delay(500)
          }
        } finally {
          currentQueueCount--
        }
      }
      playJobs[task] = job
      job.invokeOnCompletion {
        if (it != null) {
          when (it) {
            is HttpRequestTimeoutException -> {
              logger.error("request timeout ${task.description}", it)
              safeConsole("语音生成超时 ${task.description}")
            }

            is CancellationException -> {
              logger.error("cancel play ${task.description}", it)
              safeConsole("取消播放 ${task.description}")
            }

            else -> {
              logger.error("play error ${task.description}", it)
              safeConsole("播放失败 ${task.description}")
            }
          }
        }
        playJobs.remove(task)
        val safeListener = task.listener.safe()
        uiScope.launch {
          safeListener(PlayTextStatus.COMPLETED)
        }
      }
      return job
    }
  }


  fun cancelAll() {
    scope.launch {
      playJobs.values.forEach {
        it.cancel()
      }
      playJobs.clear()
      currentQueueCount = 0
    }
  }

  private fun PlayTextStatusListener.safe(): suspend (PlayTextStatus) -> Unit {
    return { status ->
      withContext(uiScope.coroutineContext) {
        this@safe(status)
      }
    }
  }

  private suspend fun play(
    task: PlayTask,
    file: File
  ) {
    try {
      streamPlayer.playUntilCompleted(playerGain, file)
    } finally {
      if (!task.cache) {
        file.delete()
      }
    }
  }

  private suspend fun download(task: PlayTask): File {
    val (voice, msg, cache, listener) = task
    val safeListener = listener.safe()
    safeListener(PlayTextStatus.DOWNLOADING)
    val actualMsg = msg.trim()
    if (actualMsg.isBlank()) {
      error("msg is blank")
    }
    if (cache) {
      // 获取音频缓存
      val file = getVoiceCacheFile(voice, actualMsg)
      if (file.exists()) {
        safeListener(PlayTextStatus.DOWNLOADED)
        return file
      }
    }
    // 下载音频
    val url = "http://127.0.0.1:7860/run/predict"
    val start = System.currentTimeMillis()
    val response = httpClient.post {
      url(url)
      setBody("""
        {
          "data": [
            "$actualMsg",
            "${voice.name}",
            0.5,
            0.6,
            0.9,
            1,
            "ZH",
            null,
            "Happy",
            "Text prompt",
            "",
            0.7
          ],
          "event_data": null,
          "fn_index": 0
        }
      """.trimIndent())
      contentType(ContentType.parse("application/json"))
    }

    when (response.status) {
      HttpStatusCode.Unauthorized -> {
        safeConsole("语音生成失败")
        error("download error")
      }

      HttpStatusCode.OK -> {
        logger.info("[${voice.name}]${actualMsg} 用时:${System.currentTimeMillis()-start}ms")
        val body = response.body<JsonObject>()
        val data = body["data"]?.jsonArray
        if (data == null) {
          safeConsole("语音生成失败")
          error("generate error")
        }
        if (data[0].jsonPrimitive.content != "Success") {
          safeConsole("语音生成失败")
          error("generate error")
        }
        val filename = data[1].jsonObject["name"]?.jsonPrimitive?.content
        if (filename == null) {
          safeConsole("语音生成失败")
          error("generate error")
        }
        val voiceFile = File(filename)
        if (cache) {
          // 保存文件到缓存
          val cacheFile = getVoiceCacheFile(voice, actualMsg)
          withContext(Dispatchers.IO) {
            cacheFile.parentFile.mkdirs()
            Files.copy(voiceFile.toPath(), cacheFile.toPath())
          }
        }
        return voiceFile
      }

      else -> {
        safeConsole("语音生成失败 ${task.description}")
        error("download error, status code: ${response.status}")
      }
    }
  }

  private fun getVoiceCacheFile(voice: VoiceInfo, msg: String): File {
    return AppFiles.voiceCache / voice.id.toString() / msg
  }

}

/**
 * 播放状态
 */
enum class PlayTextStatus {
  /**
   * 下载音频中
   */
  DOWNLOADING,

  /**
   * 下载音频完成
   */
  DOWNLOADED,

  /**
   * 播放中
   */
  PLAYING,

  /**
   * 停止播放
   */
  COMPLETED
}


suspend fun StreamPlayer.playUntilCompleted(gain: Double, file: File) {
  openAndPlayUntilCompleted(gain) { open(file) }
}

suspend fun StreamPlayer.playUntilCompleted(gain: Double, stream: InputStream) {
  openAndPlayUntilCompleted(gain) { open(stream) }
}

suspend fun StreamPlayer.openAndPlayUntilCompleted(gain: Double, openAction: StreamPlayer.() -> Unit) =
  suspendCancellableCoroutine { con ->
    con.invokeOnCancellation {
      stop()
    }
    addStreamPlayerListener(object : StreamPlayerListener {
      override fun opened(dataSource: Any?, properties: MutableMap<String, Any>?) {
        // ignore
      }

      override fun progress(
        nEncodedBytes: Int,
        microsecondPosition: Long,
        pcmData: ByteArray?,
        properties: MutableMap<String, Any>?
      ) {
        // ignore
      }

      override fun statusUpdated(event: StreamPlayerEvent) {
        val status = event.playerStatus
        if (status == Status.STOPPED || status == Status.PAUSED || status == Status.EOM) {
          if (con.isActive) {
            con.resume(Unit)
          }
          return
        }
      }
    })
    openAction()
    play()
    setGain(gain)
  }

