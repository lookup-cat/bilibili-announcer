import _locale
import asyncio
import dataclasses
import json
import logging
import os

import platform
import re
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
from sounds import sound_sources

_locale._getdefaultlocale = (lambda *args: ['zh_CN', 'utf8'])
is_windows = platform.system() == 'Windows'

application_name = '派蒙弹幕姬'
application_version = '1.1'


@dataclass_json
@dataclass
class Config:
    room_id: int = 100  # 默认直播间
    sound: str = ''  # 音源
    play_queue_limit: int = 5  # 弹幕播报队列数量限制
    play_interval: float = 1  # 弹幕播报间隔 单位秒
    log_max_lines: int = 1000  # 日志最大行数


class PlayStatus(Enum):
    stop = 0  # 停止播报
    operation = 1  # 正在连接或者断开直播间
    playing = 2  # 已连接直播间正在播报弹幕


# noinspection PyBroadException
class Controller(Thread):
    def __init__(self):
        super().__init__()
        self.config: Optional[Config] = None
        self.room: Optional[LiveDanmaku] = None
        self.__previous_config__: Optional[Config] = None
        self.loop = asyncio.new_event_loop()
        self.room_ref = Ref[TextField]()
        self.sound_ref = Ref[Dropdown]()
        self.log_ref = Ref[ListView]()
        self.play_button_ref = Ref[ElevatedButton]()
        self.play_queue: Optional[asyncio.Queue] = None
        self.play_status = PlayStatus.stop
        self.logger = logging.getLogger('main')
        self.log_count = 0

    def log(self, msg: str):
        self.logger.info(msg)
        while len(self.log_control.controls) >= self.config.log_max_lines:
            self.log_control.controls.remove(self.log_control.controls[0])
        self.log_control.controls.append(Text(msg))
        self.log_control.update()

    def run(self) -> None:
        asyncio.set_event_loop(self.loop)
        self.play_queue = asyncio.Queue()
        # 读取配置
        self.run_async(self.read_config())
        # 播放器任务
        self.run_async(self.start_player())
        # 日志输出
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
        """
        修改当前播报状态
        :param play_status:
        :return:
        """
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
        读取应用配置
        :return:
        """
        if not await async_os.path.exists('config.json'):
            self.config = Config()
        else:
            async with aiofiles.open('config.json') as file:
                content = await file.read()
            try:
                self.config = Config.from_json(content, infer_missing=True)
            except Exception:
                self.config = Config()

        if self.config.sound not in sound_sources:
            self.config.sound = sound_sources[0]

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
        mp3_file = 'tmp.mp3'
        async with httpx.AsyncClient() as client:
            while True:
                text: str = await self.play_queue.get()
                self.log(f'播放: [{self.sound_control.value}] {text}')
                # noinspection HttpUrlsUsage
                url = f"http://233366.proxy.nscc-gz.cn:8888?speaker={self.sound_control.value}&text={text}"
                is_fail = False
                try:
                    response = await client.get(url)
                    if response.status_code == 200:
                        mp3 = response.content
                        if await async_os.path.exists(mp3_file):
                            await async_os.remove(mp3_file)
                        async with aiofiles.open(mp3_file, 'wb') as file:
                            await file.write(mp3)
                        await self.loop.run_in_executor(executor, playsound, os.getcwd() + '/tmp.mp3')
                        await async_os.remove(mp3_file)
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
                        self.log(f'播放 [{self.sound_control.value}] {text} 失败')
                    await asyncio.sleep(self.config.play_interval)

    async def add_play_task(self, text: str):
        """
        添加播放任务
        :param text: 播放文本
        :return:
        """
        # 删除多余弹幕
        while self.play_queue.qsize() >= self.config.play_queue_limit:
            try:
                text = self.play_queue.get_nowait()
                self.log(f'丢弃弹幕: {text}')
            except QueueEmpty:
                pass
        await self.play_queue.put(text)
        await asyncio.sleep(self.config.play_interval)

    async def connect(self, room_id: int):
        """
        连接直播间并接受弹幕
        :param room_id:
        :return:
        """
        self.log(f'开始连接直播间')
        room = LiveDanmaku(room_id)
        self.room = room

        @room.on('VERIFICATION_SUCCESSFUL')
        async def on_verification_successful(event):
            self.log('连接直播间成功')
            self.set_play_status(play_status=PlayStatus.playing)

        # noinspection SpellCheckingInspection
        @room.on('DANMU_MSG')
        async def on_danmaku(event):
            text = event['data']['info'][1]
            # TODO 优化弹幕过滤机制
            # 必须包含中文 或 只有一种字符
            if not re.match('[\w\W]*[\u4e00-\u9fa5]+[\w\W]*', text) \
                    or len(set(text)) == 1:
                self.log(f'过滤弹幕: {text}')
                return
            await self.add_play_task(text)

        try:
            await room.connect()
        except ResponseCodeException as e:
            self.log(e.msg)
        except Exception:
            # TODO 新增自动重连
            self.log('连接异常')
        finally:
            # TODO 新增立即停止声音播放
            self.set_play_status(PlayStatus.stop)
            self.log('断开直播间连接')

    async def disconnect(self):
        """
        停止播放
        :return:
        """
        while not self.play_queue.empty():
            try:
                self.play_queue.get_nowait()
            except QueueEmpty:
                break
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
            # 停止播放
            self.set_play_status(PlayStatus.operation)
            await self.disconnect()


def main(page: Page):
    controller = Controller()
    page.window_width = 400
    page.window_height = 650
    page.window_resizable = False
    if is_windows:
        page.theme = Theme(font_family='微软雅黑')

    page.title = f"{application_name} V{application_version}"
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
                        content_padding=padding.only(left=12, right=12)
                    )
                ]
            ),
            Container(padding=padding.only(top=2)),
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
                        on_change=controller.sound_changed,
                        content_padding=padding.only(left=12, right=12),
                        options=[dropdown.Option(sound_source) for sound_source in sound_sources]
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
