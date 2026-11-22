# CFluxDo Watch（OPPO Watch X2 精简原生移植版）

[![Build APK](https://github.com/gqe188/CFluxDo-Watch/actions/workflows/build.yml/badge.svg)](https://github.com/gqe188/CFluxDo-Watch/actions/workflows/build.yml)
[![GitHub Release](https://img.shields.io/github/v/release/gqe188/CFluxDo-Watch?style=flat-square&logo=github&label=Release)](https://github.com/gqe188/CFluxDo-Watch/releases)
[![Upstream](https://img.shields.io/badge/Upstream-1molchuan%2FCFluxDo-blue?style=flat-square&logo=github)](https://github.com/1molchuan/CFluxDo)
[![Platform](https://img.shields.io/badge/Platform-Wear%20OS%20%2F%20ColorOS%20Watch-3DDC84?style=flat-square&logo=android&logoColor=white)](#安装到手表)
[![API](https://img.shields.io/badge/Android%20API-26%2B-brightgreen?style=flat-square)](#从源码构建)
[![License](https://img.shields.io/badge/License-GPL--3.0-orange?style=flat-square)](LICENSE)

专为 **OPPO Watch X2**（466×466 圆形 LTPO AMOLED 屏 / 骁龙 W5 Gen 1 / ColorOS Watch 7.0 & Wear OS 5）深度精简并原生重构的 [Linux.do](https://linux.do/) 第三方手表客户端（兼容 Android API 26+ 圆形 Wear OS / ColorOS Watch 手表）。

> [!IMPORTANT]
> **原作者项目声明**
> - 本项目基于 **[1molchuan/CFluxDo](https://github.com/1molchuan/CFluxDo)**（作者：[1molchuan](https://github.com/1molchuan)）进行手表端深度精简与原生移植，复用了其 Rust 核心网络库 `libdoh_proxy.so`（来自 [1molchuan/fluxdo_doh](https://github.com/1molchuan/fluxdo_doh)）以及 CFluxDo v2 扫码登录协议与 Cloudflare 本地节点优选方案。
> - [CFluxDo](https://github.com/1molchuan/CFluxDo) 源自 **[Lingyan000/fluxdo](https://github.com/Lingyan000/fluxdo)**（FluxDO，作者：[Lingyan000](https://github.com/Lingyan000)）。
> - 本项目为第三方社区移植版本，**与原作者项目及 Linux.do 官方均无直接隶属关系**，遵循上游 **GPL-3.0** 协议开源。使用本手表版遇到的问题请在本仓库提交 Issue，勿打扰上游原作者。

---

## 核心特性

### 1. 极致低功耗与流畅度（不发热、不卡顿）
- **纯原生 Android Kotlin + RecyclerView**：彻底移除 Flutter 引擎、Dart VM、QuickJS、音视频解码器、后台保活服务、MessageBus 轮询与 WebView 正文渲染，常驻内存大幅降低，闲置 CPU 占用接近 0%。
- **AMOLED 纯黑省电主题**：`#000000` 纯黑底色搭配暖墨纸色正文（`#EDE9E0`），充分利用 LTPO OLED 像素熄灭特性降低功耗。
- **按需图片加载**：默认采用「点击加载图片」省电模式（可在设置中切换自动加载），图片解码使用 `RGB_565`（内存减半）配合手表分辨率下采样，缓存上限严格控制在 `4 MB`，支持点击全屏查看。
- **按需 WebView 过盾 + 老旧 WebView 同 Wi-Fi 凭证同步**：平时不初始化 WebView（零常驻 WebView 内存占用）；仅在触发 Cloudflare 验证时临时拉起并走同一本地网关，退出即彻底 `destroy()`。针对部分手表系统内置 WebView 过旧（如 Chromium 83）难以完成验证的问题，自动提示并提供 **「同 Wi-Fi 电脑/手机同步 `cf_clearance` + `User-Agent`」** 通道，自动对齐浏览器 `sec-ch-ua` 请求头与本地网关指纹。

### 2. 完整保留 CFluxDo 核心网络访问能力
- 通过轻量 C JNI 桥接层（`libdoh_jni.so`）直接调用上游 `1molchuan/CFluxDo 1.3.4` 的 Rust 核心库 `libdoh_proxy.so`（同时提供 `arm64-v8a` 与 `armeabi-v7a`）。
- 完整支持：
  - **本地 Cloudflare 节点竞速优选**（公共 DNS 并发竞速 + Atlas 节点池 + CF 网段抽样）
  - **ECH 加密握手**（内层 SNI 为 `linux.do`，应对国内 DNS 污染与 SNI 重置）
  - **BoringSSL 浏览器 TLS 指纹 + HTTP/2**（`browser_tls: true`）
  - **出口边缘 IP 钉住**（降低频繁弹 Cloudflare 验证的概率）
  - **自定义 CF 优选节点**、**IPv6 策略切换（自动 / IPv6 优先 / IPv4 优先）**、**一键重新优选**

### 3. 适配无摄像头手表的双模「扫码登录」
- **手机扫码登录手表（局域网直连，无任何第三方中转）**：
  1. 在手表端点击 **「扫码登录」**，手表启动临时局域网 HTTP 服务并在圆形表盘中央展示高对比度白底二维码。
  2. 手机（与手表连接同一 Wi-Fi）使用相机 / 浏览器扫描手表二维码，打开手机端上传页面。
  3. **方式一（推荐，兼容原版 CFluxDo v2 协议）**：在手机或电脑端 CFluxDo 中打开「扫码登录」并截图/拍照，上传至网页 -> 手表端通过 ZXing 本地解码 -> 经本地网关兑换 OTP 换取 `_t` Cookie 并立即吊销一次性 API Key（用完即焚）。
  4. **方式二**：直接粘贴 `fluxdo://qr-login?v=2&...` 链接，或手动粘贴 `_t`（及可选 `cf_clearance`）Cookie。
- **手表反向展示登录码**：手表已登录后，可生成标准 CFluxDo v2 协议二维码（10 分钟倒计时、3 秒轮询状态、关闭页面自动吊销未消费密钥），方便其他 CFluxDo 设备扫码登录。扫码页面启用 `FLAG_SECURE` 防截屏保护。

### 4. 专为圆形表盘优化的阅读与交互体验
- **圆形屏幕安全边距**：列表上下留白配合 `clipToPadding="false"`，首尾卡片均可顺滑滚动至圆形屏幕最宽区域，避免圆角裁切。
- **OPPO Watch 旋转表冠（滚轮）深度适配**：遵循 Wear OS / Android 官方旋转编码器规范（`SOURCE_ROTARY_ENCODER` + `AXIS_SCROLL` / `AXIS_VSCROLL` 双轴兼容 + `ViewConfigurationCompat` 系统滚动因子 + 亚像素累积防丢脉冲），覆盖全应用所有页面（含 Cloudflare 过盾页）及 Activity 级焦点兜底，并适配 Android 14 / Wear OS 5 线性马达机械刻度震动反馈与触顶/触底反馈（设置中支持开关表冠震动与反转滚动方向）。
- **原生富文本帖子阅读**：基于 Jsoup + 原生 `View` / `Spanned` 渲染，支持段落、链接、引用块、横向滚动代码块、可折叠 Spoiler/Details、楼层自动分页加载、**仅看楼主**、**A-/A+ 实时调节字号**、**帖子点赞**，以及 **最新 / 热门 / 未读 / 分类** 切换。

---

## 项目结构

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── cpp/                        # CMake + 轻量 C JNI 桥接 (doh_jni.c)
│       │   ├── jniLibs/                    # 来自 1molchuan/CFluxDo 的 Rust 核心库 (libdoh_proxy.so)
│       │   │   ├── arm64-v8a/
│       │   │   └── armeabi-v7a/
│       │   ├── java/com/cfluxdo/watch/
│       │   │   ├── auth/                   # 局域网扫码登录服务 & CFluxDo v2 协议实现
│       │   │   ├── model/                  # 数据模型
│       │   │   ├── net/                    # 本地网关管理、Discourse API、Cookie 持久化、过盾检测、图片加载
│       │   │   ├── render/                 # Jsoup 原生 HTML 帖子解析器
│       │   │   ├── ui/                     # 主列表、分类、帖子详情、扫码登录、设置、过盾、表冠适配
│       │   │   └── util/                   # 偏好设置与时间格式化
│       │   └── res/                        # 纯黑 AMOLED 布局与主题资源
│       └── test/                           # 核心逻辑单元测试
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── LICENSE                                 # GPL-3.0
```

---

## 安装到手表

前往本仓库的 [Releases](https://github.com/gqe188/CFluxDo-Watch/releases) 下载对应架构的 APK（或自行从源码编译，产物位于 `app/build/outputs/apk/release/`）：
- `app-arm64-v8a-release.apk`：**推荐优先安装**，原生 64 位，体积更小（适用于 OPPO Watch X2 等 64 位手表）
- `app-armeabi-v7a-release.apk`：32 位 ARM 兼容包
- `app-universal-release.apk`：全架构通用包

> 应用包名为 `com.cfluxdo.watch`，与原版 CFluxDo 相互独立，互不冲突。

### 通过 ADB 无线安装（以 OPPO Watch X2 为例）
1. 在手表上打开 **设置 → 其他设置 → 关于手表 → 版本信息**，连续点击 **版本号** 5 次开启开发者模式。
2. 返回 **其他设置 → 开发者选项**，打开 **ADB 调试 / 无线调试**，记下手表的 IP 与端口（例如 `192.168.1.50:5555`）。
3. 在电脑端执行以下命令（也可使用「WearOS 工具箱」「甲壳虫 ADB 助手」等工具选择 APK 安装）：
   ```bash
   adb connect <手表IP>:<端口>
   adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
   ```

---

## 从源码构建

### 环境要求
- JDK 17 或 JDK 21
- Android SDK（`compileSdk = 34`，`minSdk = 26`）
- Android NDK `27.2.12479018`
- CMake `3.22.1`
- Gradle `8.11.1`（或使用 Android Studio 打开本工程）

### 编译步骤
1. 克隆本仓库：
   ```bash
   git clone https://github.com/gqe188/CFluxDo-Watch.git
   cd CFluxDo-Watch
   ```
2. 在根目录创建 `local.properties`，配置本机 Android SDK 路径（Android Studio 会自动生成）：
   ```properties
   sdk.dir=/path/to/your/Android/Sdk
   ```
3. 执行 Release 构建：
   ```bash
   gradle assembleRelease --no-daemon
   ```
   如需运行单元测试：
   ```bash
   gradle testDebugUnitTest --no-daemon
   ```

> 注：本项目已包含直接取自 `1molchuan/CFluxDo 1.3.4` 的预编译 `libdoh_proxy.so`（`arm64-v8a` / `armeabi-v7a`），无需本机配置 Rust 工具链即可直接编译 APK。如需自行从源码编译 `libdoh_proxy.so`，请前往上游仓库 [1molchuan/fluxdo_doh](https://github.com/1molchuan/fluxdo_doh)（`cfluxdo` 分支）。

### GitHub Actions 自动编译与发版
本仓库已配置 [`.github/workflows/build.yml`](.github/workflows/build.yml)：
- 每次推送到 `main`、发起 PR 或在 Actions 页面手动触发（`workflow_dispatch`）时，自动运行单元测试并构建 3 个架构的 Release APK 与 `SHA256SUMS.txt` 上传至 Artifacts。
- 推送 `v*` 标签（如 `git tag v1.0.1-watch && git push origin v1.0.1-watch`）时，自动创建/更新对应 GitHub Release 并附带全部 APK。
- 如需固定正式签名，可在仓库 Settings → Secrets 中配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`（未配置时默认使用 debug 签名以便直接 ADB 安装）。

---

## 声明与致谢

- **核心上游项目**：感谢 **[1molchuan](https://github.com/1molchuan)** 开发的 **[CFluxDo](https://github.com/1molchuan/CFluxDo)** 及其 Rust 网络引擎 **[fluxdo_doh](https://github.com/1molchuan/fluxdo_doh)**。本项目保留并复用了其核心连接能力与扫码登录协议，在此特别致谢并声明出处。
- **原始上游项目**：感谢 **[Lingyan000](https://github.com/Lingyan000)** 及其所有贡献者开发的 **[FluxDO](https://github.com/Lingyan000/fluxdo)**，CFluxDo 正是基于 FluxDO 发展而来。
- **社区支持**：感谢 **[Linux.do](https://linux.do/)** 社区。
- **免责声明**：本项目仅为个人学习与穿戴设备体验优化所做的非官方精简移植版，与 Linux.do 官方及上游原作者无隶属关系。

## 开源协议

遵循上游 CFluxDo 的开源要求，本项目基于 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 协议开源。
