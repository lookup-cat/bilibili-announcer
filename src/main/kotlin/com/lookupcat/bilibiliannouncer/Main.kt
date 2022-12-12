@file:Suppress("FunctionName")

package com.lookupcat.bilibiliannouncer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.goxr3plus.streamplayer.enums.Status.*
import com.lookupcat.bilibiliannouncer.AuditionStatus.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.util.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.*
import java.awt.Cursor


@OptIn(ExperimentalMaterialApi::class)
fun main() {
    application {
        val scope = rememberCoroutineScope()
        val viewModel = remember { AppViewModel(scope) }
        Window(
            onCloseRequest = ::exitApplication,
            resizable = false,
            state = rememberWindowState(size = DpSize(400.dp, 650.dp)),
            title = "派蒙弹幕姬",
            icon = painterResource("icon.png")
        ) {
            App(viewModel, scope)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
@Preview
fun App(viewModel: AppViewModel, scope: CoroutineScope) {
    var isOpenSetting by remember { mutableStateOf(false) }
    val bottomSheetState = rememberBottomSheetState(BottomSheetValue.Collapsed)

    fun openSetting() {
        if (!isOpenSetting) {
            isOpenSetting = true
            scope.launch { bottomSheetState.expand() }
        }

    }

    fun closeSetting() {
        if (isOpenSetting) {
            isOpenSetting = false
            scope.launch { bottomSheetState.collapse() }
        }
    }

    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = bottomSheetState)
    MaterialTheme(
        colors = lightColors(primary = Color(0XFF03A9F4))
    ) {
        BottomSheetScaffold(
            scaffoldState = bottomSheetScaffoldState,
            sheetPeekHeight = 0.dp,
            sheetGesturesEnabled = false,
            sheetElevation = 0.dp,
            sheetBackgroundColor = Color(0x00000000),
            sheetContent = {
                SettingSheet(
                    viewModel = viewModel,
                    onDismiss = {
                        closeSetting()
                    }
                )
            },
            content = {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxSize()

                            .padding(horizontal = 36.dp)
                    ) {
                        Spacer(modifier = Modifier.padding(top = 20.dp))
                        RoomRow(viewModel)
                        Spacer(modifier = Modifier.padding(top = 20.dp))
                        VoiceRow(viewModel, bottomSheetScaffoldState, scope)
                        Spacer(modifier = Modifier.padding(top = 20.dp))
                        PlayButton(
                            viewModel = viewModel,
                            onRoomIdIsEmpty = {
                                scope.launch {
                                    bottomSheetScaffoldState
                                        .snackbarHostState
                                        .showSnackbar(
                                            "请输入房间号",
                                            actionLabel = "好的",
                                            duration = SnackbarDuration.Short
                                        )
                                }
                            },
                            onApiKeyIsEmpty = {
                                scope.launch {
                                    openSetting()
                                    bottomSheetScaffoldState
                                        .snackbarHostState
                                        .showSnackbar(
                                            "请输入API Key",
                                            actionLabel = "好的",
                                            duration = SnackbarDuration.Short
                                        )
                                }
                            }
                        )
                        Button(
                            onClick = {
                                openSetting()
                            },
                            modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = "设置")
                            Text("设置")
                        }
                        ConsoleLogger(viewModel)
                    }

                    // 打开设置的遮罩层
                    AnimatedVisibility(
                        visible = isOpenSetting,
                        enter = fadeIn(animationSpec = SwipeableDefaults.AnimationSpec),
                        exit = fadeOut(animationSpec = SwipeableDefaults.AnimationSpec)
                    ) {
                        val interactionSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier.fillMaxSize()
                                .background(Color(0x221E1E1E))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) {
                                    closeSetting()
                                }
                        )
                    }

//                    }
                }
            }
        )
    }
}

@Composable
fun ApiKeyRow(viewModel: AppViewModel) {
    OutlinedTextField(
        viewModel.config.apiKey,
        label = { Text("API Key") },
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
        onValueChange = { viewModel.config.apiKey = it })
}

@Composable
fun RoomRow(viewModel: AppViewModel) {
    OutlinedTextField(
        viewModel.config.roomId?.toString() ?: "",
        label = { Text("房间号") },
        modifier = Modifier.fillMaxWidth(),
        onValueChange = { newValue ->
            viewModel.config.roomId = newValue.toLongOrNull()
        }
    )
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun VoiceRow(
    viewModel: AppViewModel,
    bottomSheetScaffoldState: BottomSheetScaffoldState,
    scope: CoroutineScope
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("音源", modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
        VoicesSpinner(viewModel, modifier = Modifier.weight(6f))
        Box(modifier = Modifier.weight(2f)) {
            OutlinedButton(
                onClick = {
                    if (viewModel.started) {
                        scope.launch {
                            bottomSheetScaffoldState.snackbarHostState
                                .showSnackbar(
                                    "请先点击停止按钮",
                                    actionLabel = "好的",
                                    duration = SnackbarDuration.Short
                                )
                        }
                    } else {
                        scope.launch {
                            when (viewModel.auditionStatus) {
                                LOADING -> viewModel.stopAudition()
                                AuditionStatus.PLAYING -> viewModel.stopAudition()
                                AuditionStatus.STOPPED -> viewModel.startAudition()
                            }
                        }
                    }
                },
                modifier = Modifier.size(36.dp)
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR))),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    backgroundColor = MaterialTheme.colors.primary,
                    contentColor = Color.White
                ),
            ) {
                when (viewModel.auditionStatus) {
                    LOADING -> CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp),
                        color = Color.White
                    )

                    AuditionStatus.PLAYING -> {
                        val icons =
                            remember { listOf(Icons.Filled.VolumeMute, Icons.Filled.VolumeDown, Icons.Filled.VolumeUp) }
                        var icon by remember { mutableStateOf(icons.first()) }
                        LaunchedEffect(icons) {
                            var index = 0
                            while (isActive) {
                                delay(400)
                                icon = icons[index++ % icons.size]
                            }
                        }
                        Icon(icon, contentDescription = "停止")
                    }

                    AuditionStatus.STOPPED -> Icon(Icons.Filled.VolumeUp, contentDescription = "播放")
                }
            }
        }
    }
}


@Composable
fun PlayButton(
    viewModel: AppViewModel,
    onRoomIdIsEmpty: () -> Unit,
    onApiKeyIsEmpty: () -> Unit
) {
    Button(
        onClick = {
            if (viewModel.started) {
                viewModel.stop()
            } else {
                val roomId = viewModel.config.roomId
                val apiKey = viewModel.config.apiKey
                when {
                    roomId == null -> onRoomIdIsEmpty()
                    apiKey.isEmpty() -> onApiKeyIsEmpty()
                    else -> viewModel.start(roomId)
                }
            }
        },
        modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
    ) {
        if (viewModel.started) {
            Icon(imageVector = Icons.Default.Stop, contentDescription = "停止")
            Text("停止")
        } else {
            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "启动")
            Text("启动")
        }
    }
}

@Composable
fun VolumeRow(viewModel: AppViewModel) {
    var volume by remember { mutableStateOf(viewModel.config.volume) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = Icons.Default.VolumeUp, contentDescription = "音量")
        Text("音量", modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
        Slider(value = volume,
            valueRange = 0f..1f,
            modifier = Modifier.weight(6f).padding(8.dp),
            onValueChangeFinished = {
                viewModel.config.volume = volume
            },
            onValueChange = { newValue ->
                volume = newValue
            }
        )
        Text("${(volume * 100).toInt()}%", modifier = Modifier.weight(2f), textAlign = TextAlign.Center)
    }
}

@Composable
fun VoicesSpinner(viewModel: AppViewModel, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.Center, modifier = modifier) {
        val shape = RoundedCornerShape(6.dp)
        Row(Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(shape)
            .border(1.dp, Color.Gray, shape)
            .clickable { expanded = !expanded }
            .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = viewModel.voice.name,
                fontSize = 18.sp,
                modifier = Modifier
                    .padding(end = 8.dp)
            )
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = "")
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                viewModel.voices.forEach { voice ->
                    DropdownMenuItem(onClick = {
                        expanded = false
                        viewModel.voice = voice
                        viewModel.config.voiceId = voice.id
                    }) {
                        Text(text = voice.name)
                    }
                }
            }
        }
    }
}


@Composable
fun ConsoleLogger(viewModel: AppViewModel) {
    LazyColumn(
        modifier = Modifier
            .padding(vertical = 20.dp)
            .fillMaxSize()
            .background(Color(0x11000000), RoundedCornerShape(12.dp))
            .padding(8.dp),
        state = viewModel.consoleState,
    ) {
        items(items = viewModel.consoleLogger) {
            Text(it)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun SettingSheet(
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
) {

    Column(
        modifier = Modifier.fillMaxWidth()
            .height(360.dp)
            .background(MaterialTheme.colors.background, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("设置", fontSize = 24.sp)
        Divider(modifier = Modifier.padding(vertical = 12.dp))
        ApiKeyRow(viewModel)
        Spacer(modifier = Modifier.height(20.dp))
        QueueLength(viewModel)
        Spacer(modifier = Modifier.height(20.dp))
        VolumeRow(viewModel)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDismiss,
                modifier = Modifier.pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
fun QueueLength(viewModel: AppViewModel) {
    OutlinedTextField(
        viewModel.config.queueLength.toString(),
        label = { Text("弹幕队列长度") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            autoCorrect = true,
            imeAction = ImeAction.Next
        ),
        onValueChange = { newValue ->
            viewModel.config.queueLength = newValue.toPositiveIntOrDefault(1)
        }
    )
}

fun String.toPositiveIntOrDefault(defaultValue: Int): Int {
    if (this.isBlank()) {
        return defaultValue
    }
    val value = this.toIntOrNull()
    return if (value != null && value > 0) {
        value
    } else {
        defaultValue
    }
}

fun String.toPositiveLongOrDefault(defaultValue: Long): Long {
    if (this.isBlank()) {
        return defaultValue
    }
    val value = this.toLongOrNull()
    return if (value != null && value > 0) {
        value
    } else {
        defaultValue
    }
}