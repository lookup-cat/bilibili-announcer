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

    val voices = listOf(
        VoiceInfo(0, "派蒙"),
        VoiceInfo(1, "凯亚"),
        VoiceInfo(2, "安柏"),
        VoiceInfo(3, "丽莎"),
        VoiceInfo(4, "琴"),
        VoiceInfo(5, "香菱"),
        VoiceInfo(6, "枫原万叶"),
        VoiceInfo(7, "迪卢克"),
        VoiceInfo(8, "温迪"),
        VoiceInfo(9, "可莉"),
        VoiceInfo(10, "早柚"),
        VoiceInfo(11, "托马"),
        VoiceInfo(12, "芭芭拉"),
        VoiceInfo(13, "优菈"),
        VoiceInfo(14, "云堇"),
        VoiceInfo(15, "钟离"),
        VoiceInfo(16, "魈"),
        VoiceInfo(17, "凝光"),
        VoiceInfo(18, "雷电将军"),
        VoiceInfo(19, "北斗"),
        VoiceInfo(20, "甘雨"),
        VoiceInfo(21, "七七"),
        VoiceInfo(22, "刻晴"),
        VoiceInfo(23, "神里绫华"),
        VoiceInfo(24, "戴因斯雷布"),
        VoiceInfo(25, "雷泽"),
        VoiceInfo(26, "神里绫人"),
        VoiceInfo(27, "罗莎莉亚"),
        VoiceInfo(28, "阿贝多"),
        VoiceInfo(29, "八重神子"),
        VoiceInfo(30, "宵宫"),
        VoiceInfo(31, "荒泷一斗"),
        VoiceInfo(32, "九条裟罗"),
        VoiceInfo(33, "夜兰"),
        VoiceInfo(34, "珊瑚宫心海"),
        VoiceInfo(35, "五郎"),
        VoiceInfo(36, "散兵"),
        VoiceInfo(37, "女士"),
        VoiceInfo(38, "达达利亚"),
        VoiceInfo(39, "莫娜"),
        VoiceInfo(40, "班尼特"),
        VoiceInfo(41, "申鹤"),
        VoiceInfo(42, "行秋"),
        VoiceInfo(43, "烟绯"),
        VoiceInfo(44, "久岐忍"),
        VoiceInfo(45, "辛焱"),
        VoiceInfo(46, "砂糖"),
        VoiceInfo(47, "胡桃"),
        VoiceInfo(48, "重云"),
        VoiceInfo(49, "菲谢尔"),
        VoiceInfo(50, "诺艾尔"),
        VoiceInfo(51, "迪奥娜"),
        VoiceInfo(52, "鹿野院平藏"),
    )
    var voice by mutableStateOf(voices.first())
    var started by mutableStateOf(false)
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
        snapshotFlow { config.volume }.onEach { player.playerGain = it * 2.0 }.launchIn(uiScope)
        snapshotFlow { config.queueLength }.onEach { player.maxQueueLength = it }.launchIn(uiScope)
    }

    fun start(roomId: Long) {
        this.started = true
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
                if (danmaku.code != LiveResponseCode.SUCCESS ||  danmakuData == null) {
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
                                        while(matcher.find()) {
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
                                logger.info("command: $command")
                            }
                        }
                    }
                }.join()
            } catch (e: Exception) {
                logger.error(e.message, e)
            } finally {
                console("关闭连接")
                started = false
            }
        }
    }

    fun stop() {
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
            val task = PlayTask(voice, "旅行者你好, 我叫${voice.name}", true) {
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

