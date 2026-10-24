# 相接浅听

“相接浅听”是一款面向 Android 16 蓝牙播放设备的离线自动化播放应用。连接指定设备的媒体音频通道后，它可以按设备配置播放提示音，并启动或恢复用户选择的音乐平台。

## 主要功能

- 从手机已配对设备中直接选择，或通过 `CompanionDeviceManager` 发现新设备。
- 最多保存三台蓝牙播放设备，并完整显示设备名称和 MAC 地址。
- 自动识别车载音响、耳机或音响类型，也可以手动修正设备图标。
- 每台设备独立配置“音频播报”和“播放音乐”。
- 支持 Apple Music、Spotify、QQ音乐、网易云音乐和酷狗音乐，所有设备共用一个当前平台。
- 等待目标 A2DP 路由就绪后再播放，不主动回退到手机扬声器。
- 支持仅播报、播报后播放音乐、仅播放音乐和全部关闭四种组合。
- 音乐恢复会依次尝试目标平台媒体会话、媒体服务、显式启动和系统播放键。
- 可持续重试到设备断开，也可以限制为最多 60 秒。
- 保存并展开最近十次连接会话记录。
- 不声明网络权限，应用本身不访问网络。

## 系统要求

- Android 16（API 36）
- 支持媒体音频的蓝牙设备
- 若要播放音乐，需要安装、登录并准备好对应音乐平台的可播放队列

## 从源码构建

开发环境：

- JDK 17
- Android SDK Platform 36.1、Build Tools 36.x
- Android Gradle Plugin 8.13.2
- Gradle Wrapper 8.13

Windows：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS 或 Linux：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

也可以在 Windows 中运行项目脚本：

```powershell
.\scripts\build-apk.ps1
```

Debug APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 发布签名

仓库不包含项目发布私钥、密码或 `signing.properties`。公开源码默认可以生成 Debug APK；如需生成自己的签名版本，请创建独立密钥，并在本机 `signing/signing.properties` 中配置：

```properties
storeFile=signing/your-release-key.jks
storeType=JKS
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

`signing/`、密钥文件和签名配置均已被 `.gitignore` 排除。请勿提交真实密码或私钥。官方 Release 使用维护者离线保存的同一证书签名，以支持覆盖升级。

## 首次安装与配置

1. 在系统蓝牙设置中与车载音响、耳机或音响配对，并确认“媒体音频”已启用。
2. 安装 APK，打开“相接浅听”，授予附近设备和通知权限。
3. 在首页点击“增加设备”，选择已配对设备或发现新设备。
4. 为每台设备分别开启“音频播报”和/或“播放音乐”。
5. 在“音乐”页选择音乐平台，安装并登录对应应用，准备可继续播放的队列。
6. 按页面提示授予媒体会话控制授权，并在“后台”页完成电池优化和后台运行设置。

## Android 平台边界

- 系统“强行停止”后必须由用户手动重新打开应用。
- 普通应用不能为任意已配对设备静默创建 Companion 关联。
- 锁屏触发可靠性受 Android 版本和设备厂商后台策略影响。
- 音乐平台未安装时不会自动改播其他平台。
- 平台未登录、无网络、没有可播放队列或被系统强行停止时，Android 公开 API 无法保证成功出声。

实机回归项目见 [docs/REAL_DEVICE_TEST.md](docs/REAL_DEVICE_TEST.md)。

## 参与贡献

提交代码前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请按 [SECURITY.md](SECURITY.md) 中的方式私下报告，不要直接公开利用细节。

## 许可证与商标

本项目代码和原创矢量资源采用 [MIT License](LICENSE)。Apple Music、Spotify、QQ音乐、网易云音乐和酷狗音乐是其各自权利人的名称或商标；本项目与这些平台不存在隶属、赞助或官方合作关系，也不分发其商标素材。
