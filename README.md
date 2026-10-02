# NyaRemoteControl Android

NyaRemoteControl 的安卓客户端：用手机控制装了 NyaRemoteControl 被控端的 Windows 电脑。和 Windows 客户端、被控端分仓库、分开发版。

- 画面：被控端硬件编码（H.264 / HEVC / AV1），手机用 MediaCodec 硬件解码，低延迟模式。
- 分辨率跟随手机：连接时在电脑上建一块和手机屏幕一样大的虚拟显示器（也可选 ≤1080p 或电脑原分辨率），缩放比例可调。
- 触屏式 / 鼠标式两种操作方式，连接时显示手势指引（可关闭）。
- 悬浮球 → 侧边面板：操作方式、键盘、快捷操作（Ctrl+Alt+Del、Win、切换窗口、任务管理器…）、办公 / 游戏模式、统计信息、剪贴板、断开。
- 键盘：被控端 ≥ 协议 1.5 时直接用手机输入法打中文（文字以 Unicode 发给电脑）；旧被控端走按键。
- 声音：Opus 硬件解码 + 与 Windows 客户端相同的自适应抖动缓冲。
- 文件：发送手机文件到电脑；电脑上复制文件后，手机提示"保存到手机"，存到 `下载/NyaRemoteControl`。
- 手柄：手机连接的手柄（USB / 蓝牙）变成电脑上的虚拟 Xbox 手柄，支持震动。
- 剪贴板文字、多客户端（观看 / 接管 / 顶掉）、断线自动重连、实体键盘和鼠标。

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

被控端支持文字输入（协议 1.5）时，手机输入法打的字直接出现在电脑上；更早的被控端只收按键，中文要用电脑上的输入法（手机切到英文键盘）。键盘上方的附加键栏有 Esc、Tab、方向键、F1–F12，Ctrl / Alt / Shift / Win 按一下锁定到下一个键。

## 结构

```
app/            Kotlin + Jetpack Compose：界面、MediaCodec 解码、音频、手势、键盘
rust/src/       Rust 核心（libnya_android.so，JNI）：QUIC 连接、握手与配对、重连、视频数据报纠错、丢帧后等关键帧、抖动缓冲、文件收发
Cargo.toml      Rust 核心；依赖 ../common 的 nya-proto / nya-transport / nya-jitter（与 Windows 客户端同一套代码）
```

Kotlin 通过 `app.nya.remote.core.NativeCore` 调用核心：事件（JSON）、视频帧、音频包由 Kotlin 线程阻塞拉取，输入直接推送。核心只声明它实现了的协议功能（不含 USB、文件夹挂载、打印、HDR、麦克风、剪贴板图片 / 文件）。

## 构建

需要：JDK 17、Android SDK（NDK 28.2.13676358 会按 `app/build.gradle.kts` 自动使用）、Rust（`rustup target add aarch64-linux-android x86_64-linux-android`）、`cargo install cargo-ndk`。本仓库旁边要有 `common/`（和 Windows 客户端相同的目录布局）：

```
NyaRemoteControl/
  android/   本仓库
  common/    NyaRemoteControl-common
```

```bash
./gradlew assembleDebug        # Gradle 先用 cargo-ndk 编译 Rust 核心
cargo test                      # Rust 核心测试，含回环集成测试（假被控端：配对、视频、关键帧请求、文字输入、双向文件）
./gradlew testDebugUnitTest     # 手势、视口、按键映射、文字输入、手柄映射等
```

## 发版与签名

`scripts/release.ps1 <版本>` 写入 VERSION、Cargo.toml 版本和 COMMON_REF（固定 common 的提交），提交并打 `v<版本>` 标签；推送标签后 GitHub Actions 构建签名 APK 并发布到 Releases。

签名密钥不进仓库。`scripts/new-android-keystore.ps1` 在仓库外的 `..\signing\` 生成：

- `nya-remote-android-release.jks`：密钥
- `nya-remote-android-release.txt`：密码和别名

加 `-SetGitHubSecrets` 会把它们写入本仓库的 Actions secrets（`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`）。**两个文件都要备份**（例如放进密码管理器）：丢了密钥，已安装的 App 就无法用新版本覆盖升级，只能卸载重装。

本地签名构建：设置上面 txt 里的 `ANDROID_KEYSTORE_FILE` 等环境变量后运行 `./gradlew assembleRelease`。没有这些变量时 release 包不签名，不能发布。
