# 可靠传输与桌面客户端

本说明适用于 `4.0.1-preview`、协议 **401** 的源代码。
手机与电脑必须使用相同协议的构建，不能与协议 400 或原 3.0 安装包混用。
根 README 保留了上游原有介绍；本次新增功能和操作以本说明为准。

## 构建和启动

桌面需要 JDK 21 或更新版本、Python 3.9 或更新版本：

```text
python tools/build_desktop.py --test --package
```

首次构建会从 Maven Central 获取固定版本的 FlatLaf 并核验 SHA-256，后续使用
`.tools/dependencies` 缓存。结果位于 `.build/distributions`；ZIP 包含 Java 运行时，
Windows 解压后启动 `HybridFileXfer.exe`。单独的 JAR 可通过
`java -jar HybridFileXfer.jar` 启动，字节码以 Java 8 为目标。
桌面详细参数、主题、诊断和键盘操作见 [DESKTOP.md](../HybridFileXfer-PC/DESKTOP.md)。

构建会将 Git 完整提交号、是否含未提交修改及源码位置写入 `SOURCE.txt`。
默认源码位置是上游仓库；在其他 GitHub 仓库构建时，应显式传入
`--source-repository OWNER/REPOSITORY`，并确保相应提交已公开可访问。
发布包含未提交修改的构建时，必须另行提供对应的完整源码，不能仅依赖提交链接。
JAR 和桌面 ZIP 包含本项目 GPL 及第三方许可证，运行时许可证保留在其 `legal` 目录。

Android 使用 JDK 17、Android SDK 34 和项目所需 NDK/CMake，进入
`HybridFileXfer-Android` 后执行：

```text
./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug
```

Windows 使用 `gradlew.bat`。调试 APK 不能覆盖不同签名的安装包；卸载会清除应用设置。
APK 中的 `assets/licenses` 包含项目许可证、第三方声明及修改说明。

## 连接和选择文件

1. 打开桌面界面，选择 USB/ADB 或手机网络地址，设置电脑目录并开始等待连接。
2. ADB 需要 Android platform-tools 和手机 USB 调试授权。可请求启动手机应用，
   由用户在手机确认配对及权限；留空配对码时读取已批准会话的配对码和实际端口。
3. 网络连接需要输入手机显示的配对码。只应与信任的设备配对；电脑初始目录不是权限隔离边界。
4. 文件选择仍在手机双栏界面进行。桌面显示连接、进度、通道状态、取消操作和结果。
5. 断线后可在同一窗口重新连接，再选择同一源文件和目标目录尝试续传；任务不会自动重放。

手机监听端口可配置，默认 5740。ADB 本地端口默认自动分配，仅清理本应用建立的转发。
配对码不写入偏好或历史记录；ADB 读取接口仅允许已授权 shell/root UID。
普通、Shizuku 和直接 su 模式受各自 Android 权限约束，不保证访问所有目录。

## 文件保护和校验

文件先写入同目录私有暂存文件，完成后按覆盖、跳过或保留两份策略提交。
源文件不会因传输失败被删除；无法读取、非法文件名或写入失败会单独报告。
默认允许续传，复用前验证已持久化前缀；严格增量比较检查内容，不把相同大小视为相同文件。

CRC32 分块校验始终开启。完整文件 SHA-256 默认关闭，任一端启用即可对两端生效：
数据阶段结束后并行校验，完成一轮后批量重传摘要不匹配的文件，再进行校验。
读取、权限或磁盘错误记为失败，不以无休止重传掩盖错误。

“停止校验与重试”在正常完成停止协议后保留连接，分别报告通过、失败及未校验文件。
未校验副本不会覆盖原文件，已知不匹配或不完整重传仍留在暂存区。
数据阶段的“取消传输”会关闭当前连接以中止阻塞 I/O，之后需要重新连接。
详见 [校验协议](verification-protocol.md)。

## 验证边界

自动化测试使用合成文件、回环网络和可控故障，检查数据保护、恢复、认证、协议边界和状态。
[基准工具](transfer-benchmarks.md)可复现本机软件开销，但不能证明手机吞吐提高或保持不变。
真实手机的 USB/Wi-Fi、外置存储、Root/Shizuku、旋转与锁屏、OEM 后台限制、Windows DPI
及多显示器仍需设备验收。前台服务不能保证绕过所有厂商的后台限制。

许可证、修改日期和来源见 [CHANGES.md](../CHANGES.md) 与
[第三方声明](../THIRD_PARTY_NOTICES.txt)。
