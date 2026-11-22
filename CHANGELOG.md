# 更新日志（Changelog）

本项目基于 **[1molchuan/CFluxDo](https://github.com/1molchuan/CFluxDo)**（及 [1molchuan/fluxdo_doh](https://github.com/1molchuan/fluxdo_doh)）精简移植，原始上游为 **[Lingyan000/fluxdo](https://github.com/Lingyan000/fluxdo)**。

## v1.0.3-watch

### 问题修复与优化
- **修复帖子表情包与自定义表情不渲染的问题**：
  - 移除原先把 `img.emoji` 直接替换成纯文本 `:shortcode:` 的逻辑，改为通过原生 `ImageSpan` 内联异步渲染 Discourse 标准表情、Linux.do 自定义表情包（`emoji-custom`）与大号独立表情（`only-emoji`），同时支持引用块内的表情渲染。
  - 新增小尺寸贴纸图片（如第三方表情包）自动识别与透明背景渲染（`ARGB_8888`），默认省电模式下也会自动加载表情包，大图/截图仍保持「点击加载」省电策略。
  - 修复 `DiscourseClient.downloadBytes` 未跟随 `301/302` 重定向导致部分短链/CDN 表情与图片加载失败的问题，并增加同 URL 并发请求合并与缓存同步命中（滚动零闪烁）。

## v1.0.2-watch

### 新功能与优化
- **同 Wi-Fi 同步 Cloudflare 过盾凭证（解决手表 WebView 过旧问题）**：
  - 针对 OPPO Watch 等系统内置 WebView 过旧（如 Chromium 83）难以完成 Cloudflare 验证的情况，新增局域网同步通道：可在过盾页顶部、设置页或账号页一键开启。
  - 同 Wi-Fi 的电脑/手机现代浏览器打开手表网址后，自动填充当前浏览器 `User-Agent`，粘贴 `cf_clearance`（支持纯值或整段 Cookie）即可同步到手表并自动重启本地网关生效。
- **修复内置 WebView 过盾 UA 一致性**：
  - 加载验证页前统一将清洗后的 `User-Agent` 同步至 WebView、API 客户端与本地 Rust 网关，并新增 1.5 秒 `CookieManager` 轮询检测。
  - 自动检测低版本 WebView（Chrome < 105）并提示使用「同 Wi-Fi 同步」。
- **智能浏览器 Client Hints 对齐**：
  - 根据同步过来的 `User-Agent` 自动生成对应的 `sec-ch-ua`（支持 Chrome / Edge / Android WebView）与 `sec-ch-ua-platform`（Windows / macOS / Linux / Android）。
- **OPPO Watch 旋转表冠（滚轮）深度适配**：
  - 参考 Wear OS / Android 官方规范重构 `RotaryHelper`，兼容 `SOURCE_ROTARY_ENCODER` + `AXIS_SCROLL` / `AXIS_VSCROLL` 双轴输入与亚像素防丢脉冲累积。
  - 全应用 6 个页面统一补齐焦点管理与 Activity 级 `onGenericMotionEvent` 兜底（解决点击按钮、弹窗返回或下拉刷新后表冠失灵问题）。
  - 适配 Android 14 / Wear OS 5 线性马达机械刻度震动反馈（`SEGMENT_FREQUENT_TICK`）与触顶/触底单次边界反馈，并在设置页新增「表冠震动反馈」与「表冠滚动方向」开关。
- **工程与发版**：
  - 新增 GitHub Actions 自动测试、编译与自动发版工作流，并启用固定正式签名 Keystore，后续 CI 版本均支持直接覆盖升级。

## v1.0.0-watch

### 首发版本
- 基于 `1molchuan/CFluxDo 1.3.4` 纯原生 Kotlin + RecyclerView 精简移植，适配 OPPO Watch X2（466×466 圆形 LTPO AMOLED 屏）。
- 完整保留 Rust 核心网络库 `libdoh_proxy.so`（本地 Cloudflare 节点优选、ECH 加密握手、BoringSSL 浏览器 TLS 指纹 + HTTP/2、自定义节点与 IPv6 策略）。
- 实现适配无摄像头手表的局域网双模「扫码登录」（兼容原版 CFluxDo v2 协议，支持上传二维码截图本地 ZXing 解码或手表反向生成登录码）。
- 原生富文本帖子阅读（代码块、引用、折叠、按需 `RGB_565` 图片加载、楼层分页、仅看楼主、A-/A+ 调字号、点赞、分类筛选）。
