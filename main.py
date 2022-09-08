import _locale
import asyncio
import dataclasses
import json
import logging
import os

import winsound
import platform
from asyncio import QueueEmpty
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from enum import Enum
from threading import Thread
from typing import Union, Coroutine, Generator, Any, Optional, List

import aiofiles
import flet
import httpx
from aiofiles import os as async_os
from bilibili_api import ResponseCodeException
from bilibili_api.live import LiveDanmaku
from dataclasses_json import dataclass_json
from flet import Page, Row, icons, Column, Dropdown, dropdown, Container, Text, ElevatedButton, \
    ListView, padding, border, border_radius, Ref, ProgressRing, TextField, Theme
from playsound import playsound

_locale._getdefaultlocale = (lambda *args: ['zh_CN', 'utf8'])
is_windows = platform.system() == 'Windows'

logger = logging.getLogger('main')
logger.setLevel(logging.INFO)


@dataclass_json
@dataclass
class Config:
    room_id: int = 100  # 默认直播间
    sound: str = ''  # 音源
    play_queue_limit: int = 5  # 弹幕播报队列数量限制
    play_interval: float = 1  # 弹幕播报间隔 单位秒


class PlayStatus(Enum):
    stop = 0  # 停止播报
    operation = 1  # 正在连接或者断开直播间
    playing = 2  # 已连接直播间正在播报弹幕


class UILoggerHandler(logging.StreamHandler):
    def __init__(self, control: ListView, max_line: int = 1000):
        super().__init__()
        self.control = control
        self.max_line = max_line

    def emit(self, record: logging.LogRecord) -> None:
        msg = self.format(record)
        self.control.controls.append(Text(msg))
        self.control.update()


# noinspection PyBroadException
class Controller(Thread):
    def __init__(self):
        super().__init__()
        self.config: Optional[Config] = None
        self.room: Optional[LiveDanmaku] = None
        self.__previous_config__: Optional[Config] = None
        self.loop = asyncio.new_event_loop()
        self.sounds: List[str] = []
        self.room_ref = Ref[TextField]()
        self.sound_ref = Ref[Dropdown]()
        self.log_ref = Ref[ListView]()
        self.play_button_ref = Ref[ElevatedButton]()
        self.play_queue: Optional[asyncio.Queue] = None
        self.play_status = PlayStatus.stop

    def run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.play_queue = asyncio.Queue()
        # 读取配置
        self.run_async(self.read_config())
        # 播放器任务
        self.run_async(self.start_player())
        # 日志输出
        # logging.root.handlers = []
        logging.root.addHandler(UILoggerHandler(self.log_control, max_line=1000))
        self.loop.run_forever()

    def run_async(self, func: Union[Coroutine, Generator[Any, None, Any]]):
        asyncio.run_coroutine_threadsafe(func, self.loop)

    @property
    def log_control(self) -> ListView:
        return self.log_ref.current

    @property
    def room_control(self) -> TextField:
        return self.room_ref.current

    @property
    def sound_control(self) -> Dropdown:
        return self.sound_ref.current

    @property
    def play_control(self) -> ElevatedButton:
        return self.play_button_ref.current

    def set_play_status(self, play_status: PlayStatus):
        # 修改当前播报状态
        self.play_status = play_status
        button = self.play_control
        room = self.room_control
        if play_status == PlayStatus.playing:
            room.read_only = True
            room.disabled = True
            room.update()
            button.disabled = False
            button.content = None
            button.text = '暂停'
            button.icon = icons.PAUSE
            button.update()
        elif play_status == PlayStatus.operation:
            room.read_only = True
            room.disabled = True
            room.update()
            button.disabled = True
            button.text = None
            button.icon = None
            button.content = ProgressRing(width=12, height=12, stroke_width=2)
            button.update()
        elif play_status == PlayStatus.stop:
            room.disabled = False
            room.read_only = False
            room.update()
            button.disabled = False
            button.content = None
            button.text = '启动'
            button.icon = icons.PLAY_ARROW
            button.update()

    async def read_config(self):
        """
        读取音源配置、应用配置
        :return:
        """
        # 读取音源
        await self.read_sounds()
        if not await async_os.path.exists('config.json'):
            self.config = Config()
        else:
            async with aiofiles.open(os.getcwd() + '/config.json') as file:
                content = await file.read()
            try:
                self.config = Config.from_json(content)
            except Exception:
                self.config = Config()

        if self.config.sound not in self.sounds:
            self.config.sound = self.sounds[0]

        # 更新ui
        # 房间号
        self.room_control.value = str(self.config.room_id)
        self.room_control.update()
        # 音源
        self.sound_control.value = self.config.sound
        self.sound_control.update()

    async def write_config(self):
        """
        如果配置发生变化，写入新配置到磁盘
        :return:
        """
        config = self.config
        if config != self.__previous_config__:
            async with aiofiles.open('config.json', 'w') as file:
                await file.write(Config.to_json(config, ensure_ascii=False, indent=2))
            self.__previous_config__ = dataclasses.replace(config)

    async def start_player(self):
        """
        启动播放任务
        :return:
        """
        executor = ThreadPoolExecutor(1)
        async with httpx.AsyncClient() as client:
            while True:
                text: str = await self.play_queue.get()
                self.log_control.controls.append(Text(f'播放: [{self.sound_control.value}] {text}'))
                self.log_control.update()
                # noinspection HttpUrlsUsage
                url = f"http://233366.proxy.nscc-gz.cn:8888?speaker={self.sound_control.value}&text={text}"
                is_fail = False
                try:
                    response = await client.get(url)
                    if response.status_code == 200:
                        mp3 = response.content
                        if await async_os.path.exists('tmp.mp3'):
                            await async_os.remove('tmp.mp3')
                        async with aiofiles.open('tmp.mp3', 'wb') as file:
                            await file.write(mp3)
                        await self.loop.run_in_executor(executor, playsound, os.getcwd() + '/tmp.mp3')
                        await async_os.remove('tmp.mp3')
                    else:
                        # 下载音频失败
                        is_fail = True
                        pass
                except Exception:
                    # 下载音频失败
                    is_fail = True
                    pass
                finally:
                    if is_fail:
                        logger.info(f'播放 [{self.sound_control.value}] {text} 失败')
                    await asyncio.sleep(self.config.play_interval)

    async def add_play_task(self, text: str):
        """
        添加播放任务
        :param text: 播放文本
        :return:
        """
        # 删除多余弹幕
        while self.play_queue.qsize() >= self.config.play_queue_limit:
            await self.play_queue.get()
        await self.play_queue.put(text)
        await asyncio.sleep(self.config.play_interval)

    async def read_sounds(self):
        """
        读取音频列表
        :return:
        """
        async with aiofiles.open('sounds.json') as file:
            content = await file.read()
        print(f'sounds: {content}')
        sounds = json.loads(content)
        sound_control = self.sound_ref.current
        self.sounds = sounds
        options = [dropdown.Option(sound) for sound in sounds]
        sound_control.options = options
        sound_control.update()

    async def connect(self, room_id: int):
        """
        连接直播间并接受弹幕
        :param room_id:
        :return:
        """
        room = LiveDanmaku(room_id)
        self.room = room

        @room.on('VERIFICATION_SUCCESSFUL')
        async def on_verification_successful(event):
            self.set_play_status(play_status=PlayStatus.playing)

        # noinspection SpellCheckingInspection
        @room.on('DANMU_MSG')
        async def on_danmaku(event):
            text = event['data']['info'][1]
            logger.info(f'收到弹幕: {text}')
            # TODO 新增弹幕过滤机制
            if len(set(text)) == 1:
                return
            if '哈哈' in text:
                return
            self.log_control.controls.append(Text(f'弹幕: {text}'))
            self.log_control.update()
            await self.add_play_task(text)

        # TODO 处理房号错误、重连问题
        try:
            await room.connect()
        except ResponseCodeException as e:
            logger.error(e.msg)
        finally:
            self.set_play_status(PlayStatus.stop)

    async def stop(self):
        """
        停止播放，清空播放队列
        :return:
        """
        while not self.play_queue.empty():
            try:
                self.play_queue.get_nowait()
            except QueueEmpty:
                break
        if is_windows:
            winsound.PlaySound(None, winsound.SND_PURGE)

    async def disconnect(self):
        """
        断开直播间连接
        :return:
        """
        await self.room.disconnect()

    def room_changed(self, _):
        if self.room_control.error_text:
            self.room_control.error_text = None
        self.room_control.update()

    def sound_changed(self, _):
        self.config.sound = self.sound_control.value
        self.run_async(self.write_config())

    def play_click(self, _):
        """
        点击启动按钮，开始连接直播间并播报弹幕
        :param _:
        :return:
        """
        room_id_text = self.room_control.value
        try:
            room_id = int(room_id_text)
            if room_id <= 0:
                raise Exception()
        except Exception:
            self.room_control.error_text = '请输入有效房间号'
            self.room_control.update()
            return

        self.run_async(self.do_play_click(room_id))

    async def do_play_click(self, room_id: int):
        status = self.play_status
        if status == PlayStatus.stop:
            # 启动播放
            self.set_play_status(PlayStatus.operation)
            self.loop.create_task(self.connect(room_id))
            # 保存配置
            self.config.room_id = room_id
            await self.write_config()
        elif status == PlayStatus.playing:
            self.set_play_status(PlayStatus.operation)
            # 暂停播放
            await self.loop.create_task(
                asyncio.wait([
                    self.stop(),
                    asyncio.sleep(0.8)
                ])
            )
            await self.disconnect()


def main(page: Page):
    controller = Controller()
    page.window_width = 400
    page.window_height = 650
    page.window_resizable = False
    if is_windows:
        page.theme = Theme(font_family='微软雅黑')

    page.title = "派蒙弹幕播报"
    page.vertical_alignment = "center"

    left_column_width = page.window_width * 0.2
    right_column_width = page.window_width * 0.5

    column = Column(
        [
            Row(
                width=left_column_width + right_column_width,
                vertical_alignment='center',
                controls=[
                    Text(
                        '直播间房号',
                        text_align='center',
                        width=left_column_width
                    ),
                    TextField(
                        width=right_column_width,
                        ref=controller.room_ref,
                        hint_text='请输入直播间房号',
                        on_change=controller.room_changed,
                    )
                ]
            ),
            Row(
                width=left_column_width + right_column_width,
                vertical_alignment='center',
                controls=[
                    Text(
                        '音源',
                        text_align='center',
                        width=left_column_width
                    ),
                    Dropdown(
                        ref=controller.sound_ref,
                        width=right_column_width,
                        autofocus=True,
                        on_change=controller.sound_changed
                    )
                ]
            ),
            Container(padding=padding.only(top=10)),
            ElevatedButton(
                '启动',
                icon=icons.PLAY_ARROW,
                ref=controller.play_button_ref,
                on_click=controller.play_click
            ),
            Container(padding=padding.only(top=10)),
            Container(
                padding=padding.all(8),
                border_radius=border_radius.all(5),
                border=border.all(2, '#CCCCCC'),
                # bgcolor='#EEEEEE',
                content=ListView(
                    ref=controller.log_ref,
                    auto_scroll=True,
                    height=250,
                    width=page.window_width * 0.8
                )
            ),
        ],
        width=page.window_width,
        alignment='center',
        horizontal_alignment='center',
        scroll=False
    )
    page.add(column)
    controller.start()


flet.app(target=main)
