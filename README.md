# NyaRemoteControl Android

NyaRemoteControl 的安卓客户端：用手机控制装了 NyaRemoteControl 被控端的 Windows 电脑。和 Windows 客户端、被控端分仓库、分开发版。

- 画面：被控端硬件编码（H.264 / HEVC / AV1），手机用 MediaCodec 硬件解码，低延迟模式。
- 分辨率跟随手机：连接时在电脑上建一块和手机屏幕一样大的虚拟显示器（也可选 ≤1080p 或电脑原分辨率），缩放比例可调。
- 触屏式 / 鼠标式两种操作方式，连接时显示手势指引（可关闭）。
- 悬浮球 → 侧边面板：操作方式、键盘、快捷操作（Ctrl+Alt+Del、Win、切换窗口、任务管理器…）、办公 / 游戏模式、切换显示器、统计信息、剪贴板、发送文件、麦克风、USB 设备、断开。
- 键盘：手机输入法照常使用（拼音、手写、语音）。被控端 ≥ 协议 1.5 时文字以 Unicode 打进电脑；更早的被控端上中文经电脑剪贴板粘贴，英文走按键。
- 声音：Opus 硬件解码 + 与 Windows 客户端相同的自适应抖动缓冲。麦克风：手机麦克风传到电脑（Android 10+，电脑需装虚拟声卡；打开期间电脑的默认麦克风自动切到虚拟声卡，关掉后恢复）。手机上其他应用照样能用麦克风（前台应用优先），本应用在后台时不占用麦克风。
- 剪贴板：文字、图片双向；手机上复制的文件可以在电脑上直接粘贴。
- 文件：发送手机文件到电脑；电脑上复制文件后，手机提示"保存到手机"，存到 `下载/NyaRemoteControl`。
- 共享文件夹：设置里选的手机文件夹在电脑上显示为一个盘符（电脑需装 WinFsp；手机需授予文件访问权限）。
- 打印：电脑打印到"打印到 NyaRemoteControl 客户端"，手机上提示打印或保存。
- USB：手机 OTG 接口上的设备共享给电脑（手机自己充当 USB/IP 服务端；不支持摄像头、声卡这类等时传输设备）。
- 手柄：手机连接的手柄（USB / 蓝牙）变成电脑上的虚拟 Xbox 手柄，支持震动。
- HDR：手机屏幕支持 HDR10 且电脑开着 HDR 时，以 HDR10 显示。
- 多客户端（观看 / 接管 / 顶掉）、断线自动重连、实体键盘和鼠标、应用内更新（GitHub Releases）。

## 手势

| 触屏式 | | 鼠标式 | |
|---|---|---|---|
| 单指轻按 | 单击 | 单指滑动 | 移动光标 |
| 单指长按 | 右键 | 单指轻按 | 单击（在光标处） |
| 单指长按后拖动 | 拖动 | 双指轻按 / 单指长按 | 右键 |
| 单指滑动 | 滚动 | 长按后滑动 | 拖动 |
| 双指捏合 | 缩放画面 | 双指滑动 | 滚动 |
| 放大后双指滑动 | 移动画面 | 双指捏合 | 缩放（画面跟随光标） |
| 三指轻按 | 弹出键盘 | 三指轻按 | 弹出键盘 |

手机输入法打的字直接出现在电脑上：被控端支持文字输入（协议 1.5）时以 Unicode 打入；更早的被控端上中文经电脑剪贴板 + Ctrl+V（会覆盖电脑剪贴板）。键盘上方的附加键栏有 Esc、Tab、方向键、F1–F12，Ctrl / Alt / Shift / Win 按一下锁定到下一个键。点"电脑键盘"可换成屏幕上的电脑布局键盘（按键直接发给电脑，适合游戏、快捷键、命令行），再点"输入法"换回；设置里可选默认键盘。

## 结构

```
app/            Kotlin + Jetpack Compose：界面、MediaCodec 解码、音频、手势、键盘
rust/src/       Rust 核心（libnya_android.so，JNI）：QUIC 连接、握手与配对、重连、视频数据报纠错、丢帧后等关键帧、抖动缓冲、文件收发、共享文件夹、USB/IP 服务端（usb.rs）
Cargo.toml      Rust 核心；依赖 ../common 的 nya-proto / nya-transport / nya-jitter（与 Windows 客户端同一套代码）
```

Kotlin 通过 `app.nya.remote.core.NativeCore` 调用核心：事件（JSON）、视频帧、音频包由 Kotlin 线程阻塞拉取，输入直接推送。核心声明除 4:4:4 以外的全部协议功能（手机解码器只有 4:2:0）；多画面窗口改为面板里切换显示器。

## 构建

需要：JDK 17、Android SDK（NDK 28.2.13676358 会按 `app/build.gradle.kts` 自动使用）、Rust（`rustup target add aarch64-linux-android x86_64-linux-android`）、`cargo install cargo-ndk`。本仓库旁边要有 `common/`（和 Windows 客户端相同的目录布局）：

```
NyaRemoteControl/
  android/   本仓库
  common/    NyaRemoteControl-common
```

```bash
./gradlew assembleDebug        # Gradle 先用 cargo-ndk 编译 Rust 核心
cargo test                      # Rust 核心测试：回环集成测试（假被控端：配对、视频、关键帧请求、文字输入、双向文件、共享文件夹），USB/IP 服务端（模拟设备）
./gradlew testDebugUnitTest     # 手势、视口、按键映射、文字输入与粘贴回退、手柄映射、DIB 图片转换、版本比较等

```

## 发版与签名

`scripts/release.ps1 <版本>` 写入 VERSION、Cargo.toml 版本和 COMMON_REF（固定 common 的提交），提交并打 `v<版本>` 标签；推送标签后 GitHub Actions 构建签名 APK 并发布到 Releases。

签名密钥不进仓库。`scripts/new-android-keystore.ps1` 在仓库外的 `..\signing\` 生成：

- `nya-remote-android-release.jks`：密钥
- `nya-remote-android-release.txt`：密码和别名

加 `-SetGitHubSecrets` 会把它们写入本仓库的 Actions secrets（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`）。**两个文件都要备份**（例如放进密码管理器）：丢了密钥，已安装的 App 就无法用新版本覆盖升级，只能卸载重装。

本地签名构建：设置上面 txt 里的 `ANDROID_KEYSTORE_FILE` 等环境变量后运行 `./gradlew assembleRelease`。没有这些变量时 release 包不签名，不能发布。
