# 参与贡献

感谢你帮助改进“相接浅听”。

## 开发准备

- JDK 17
- Android SDK Platform 36.1 与 Build Tools 36.x
- 使用仓库中的 Gradle Wrapper 8.13

## 提交前检查

Windows：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

macOS 或 Linux：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

涉及蓝牙路由、后台服务或音乐平台恢复逻辑的修改，还应按 `docs/REAL_DEVICE_TEST.md` 完成相应实机测试。

## 提交规范

- 一个提交只处理一个清晰问题。
- 为行为变更补充或更新测试。
- 不提交 APK、构建缓存、本机 SDK 路径、设备地址、账号信息、签名配置或私钥。
- 不提交第三方音乐平台的 Logo、安装包或其他受保护素材。
- 保持包名兼容，除非变更已明确包含迁移方案。

提交 Issue 时请提供 Android 版本、设备厂商、蓝牙设备类型、复现步骤和已隐藏隐私信息的日志。不要公开密钥、完整个人设备标识或其他敏感数据。
