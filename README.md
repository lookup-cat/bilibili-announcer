# 派蒙弹幕姬

使用原神角色合成音自动播报b站直播间弹幕.

![image](./docs/screenshot_1.png)

本项目仅用于学习交流, 禁止商业用途, 侵权请联系删除!

## 功能

* [x] 自动过滤无效弹幕
* [x] 弹幕数量过多自动丢弃, 默认播报队列只有5条
* [x] 调节音量
* [ ] 自定义配置弹幕过滤功能

## 依赖

语音合成功能依赖于项目：[新版Bert-vits2 v2.0.2原神角色雷电将军音色模型一键推理整合包分享](https://www.bilibili.com/video/BV1WM411Z78E/)

## 更新日志

###　2.0.0

- 使用kotlin进行重构，修复了断开直播间不会立即停止语音播放的问题
- 更新音源为本地化部署方案
- 支持调整音量

## 相关项目

- [新版Bert-vits2 v2.0.2原神角色雷电将军音色模型一键推理整合包分享](https://www.bilibili.com/video/BV1WM411Z78E/) 
- [Yabapi](https://github.com/SDLMoe/Yabapi) 一个B站第三方 Kotlin API 库
