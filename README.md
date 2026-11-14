# MciLm-link 安卓版 (Android Client)

> **项目简介**：本项目是基于 MciLm-link 联机引擎（C ABI 动态库）构建的 Android 原生宿主客户端。通过 JNI 与底层 C 库进行深度交互，实现了联机房间的创建、加入、状态监控、实时文字聊天、语音联机、Minecraft 局域网广播、文件同步、成员列表轮询、事件驱动 UI，平台生态功能（公告/活动/邀请/反馈），以及“后台全局保活与防崩溃分发机制”。

## 一、 基本信息

- **应用名称**：Mcilm-link 安卓版
- **包名 Namespace**：`wasai.link.mcilm.top`（发布后不可更改）
- **版本号**：`1.4.0` (versionCode 7)
- **ABI 支持**：仅支持 `arm64-v8a`（不提供 32 位 ARM、x86、x86_64 兜底）
- **最低支持**：Android 7.0 (API 24)
- **目标版本**：Android 14 (targetSdk 34)

## 二、 技术栈

- **UI 框架**：Jetpack Compose + Material 3 (深色主题，现代化 UI)
- **架构模式**：MVVM + Repository + 协程 (Coroutines) + 状态流 (StateFlow/SharedFlow)
- **网络请求**：Retrofit + OkHttp + kotlinx.serialization
- **本地存储**：EncryptedSharedPreferences (持久化 Token)
- **Native 桥接**：C++ JNI (动态 dlopen/dlsym)
- **音频引擎**：AudioRecord/AudioTrack (16kHz/mono/16-bit LE PCM)
- **后台保活**：Foreground Service (前台服务) + 电池优化忽略引导
- **日志管理**：外部私有存储持久化 (自动脱敏、防注入截断、东八区时区强制、支持导出分享)
- **辅助功能**：二维码生成与扫码 (ZXing / ScanContract)、智能正则解析、SHA-256 加密校验

## 三、 工程目录结构

    wasai.link.mcilm.top
     ├── data
     │    ├── TokenManager.kt          - 加密存储 Token（支持轮换强制覆盖）
     │    ├── AudioEngine.kt           - 语音采集播放引擎（AEC + NS，线程安全）
     │    ├── NativeLibraryManager.kt  - 内核动态库 API 下载、ELF校验、防崩溃熔断
     │    └── LogFileManager.kt        - 本地日志脱敏、防注入截断与异步写入
     ├── service
     │    └── LinkKeepAliveService.kt  - [新增] 前台保活服务（防止后台强杀断联）
     ├── link
     │    └── MciLmNative.kt           - 核心 JNI 声明、事件分发、音频回调、日志流
     ├── network
     │    ├── ApiModels.kt             - 开放 API 数据实体（含公告、活动、邀请等）
     │    ├── ApiService.kt            - Retrofit 接口定义
     │    └── AuthRepository.kt        - 统一网络错误处理 (超时封禁限流)
     ├── ui
     │    ├── AppRoot.kt               - 全局路由、启动内核加载页、全局弹窗、保活监听
     │    ├── LoginScreen.kt           - 登录页 (带隐私协议勾选)
     │    ├── HomeScreen.kt            - 主页 (创建加入、公开房列表、铃铛红点)
     │    ├── HostScreen.kt            - 房主开房页 (踢人语音控制排障参数)
     │    ├── JoinScreen.kt            - 访客加入页 (扫码语音开关强制中继)
     │    ├── SettingsScreen.kt        - 设置页 (内核App版本、开发者模式、平台功能入口)
     │    ├── AnnouncementsScreen.kt   - [新增] 公告页（响应式邮箱式分栏布局）
     │    ├── ActivityScreen.kt        - [新增] 活动中心页（一键领取时长）
     │    ├── InviteScreen.kt          - [新增] 邀请好友页（二维码、链接分享）
     │    ├── FeedbackScreen.kt        - [新增] 意见反馈页（提交与历史查看）
     │    ├── BackgroundSettingsDialog.kt - [新增] 电池优化与自启动引导弹窗
     │    ├── HtmlContent.kt           - [新增] WebView 渲染富文本（公告/活动用）
     │    ├── ChatComponents.kt        - 聊天复用组件 (气泡、底栏、文件进度)
     │    ├── CommonComponents.kt      - 日志面板、日志导出分享、全局公共组件
     │    └── theme/Theme.kt           - 暗黑科技主题
     ├── viewmodel
     │    ├── MainViewModel.kt         - 状态机核心骨架，持有 UiState 和核心变量
     │    ├── MainViewModel_Network.kt - [模块化] 网络、登录、节点解析与签到
     │    ├── MainViewModel_Event.kt   - [模块化] 底层事件订阅与聊天文件事件分流
     │    ├── MainViewModel_Room.kt    - [模块化] 房主访客联机控制、断线重连
     │    ├── MainViewModel_Chat.kt    - [模块化] 聊天收发、语音启停、成员踢人
     │    ├── MainViewModel_File.kt    - [模块化] 文件同步、SAF 导出、本地打开
     │    └── MainViewModel_Platform.kt- [新增] 公告、活动、邀请、反馈业务逻辑
     └── cpp
          ├── native-lib.cpp           - JNI C++ 封装，处理底层回调、音频注入与线程挂载
          └── CMakeLists.txt           - 构建配置 (仅包含 dl/log)

## 四、 核心机制说明

### 1. 全局后台保活机制 (Foreground Service)
- 在用户登录成功后，自动启动 `LinkKeepAliveService`（声明为 `dataSync` 类型）。
- 首次登录会弹出引导框（`BackgroundSettingsDialog`），引导用户关闭电池优化并允许自启动（该弹窗只需点一次，后续永久静默）。
- 确保用户在锁屏、切换应用时，底层 UDP/QUIC 打洞与心跳不会被系统冻结。

### 2. 计费闭环与权益控制 (Billing & Limits)
- **静默签到**：登录成功及开房前自动调用 `/api/user/checkin`，彻底解决用户余额为 0 却不知为何不能开房的问题。
- **权益硬拦截**：开房输入人数时，与 `roomMax` 联动。普通用户输入超出权益（如 999 人），直接无法输入并弹出 Toast。
- **耗尽警告**：在房巡检发现 `can_host=false` 时，顶部弹出红色横幅“时长耗尽，新玩家无法加入”，但不断开已有玩家。

### 3. 网络抗波动与防假死机制 (Network Resilience)
- **断网缓冲期**：网络丢失给出 5 秒缓冲期，无缝续连。
- **底层假死检测**：巡检协程每 3 秒拉取一次状态，连续 15 秒无响应则触发重连。
- **重连防撞库**：断线时强制调用 `linkStop` 杀死底层协程，重连循环增加 3 秒退避。

### 4. 平台生态与富文本渲染 (Platform Ecosystem)
- **邮箱式公告**：在宽屏（≥600dp）下自动分栏为左侧列表、右侧 WebView 详情；手机端为列表到详情的单页流转，未读带绿点。
- **活动与邀请**：主页带有可领取的活动 Banner；邀请支持复制短链与展示二维码。

### 5. 交互防抖与保护 (UX Protection)
- **防连点**：ViewModel 层引入 500ms 动作锁。
- **返回键拦截**：在 Loading 状态拦截物理返回键，防止产生“幽灵房间”。

### 6. 现代文件同步体验 (SAF)
- 接收文件时拉起 `CreateDocument` 选择目录，后台搬运私有目录文件。
- 支持文件取消与本地打开。

## 五、 编译与运行指南

### 环境要求
- Android Studio Iguana 或更高版本
- NDK 26.1.10909125 (CMake 3.22.1)
- JDK 17

### 构建步骤
1. 打开项目，等待 Gradle Sync 完成。
2. 确保 `app/src/main/jniLibs/arm64-v8a` 目录下存在：`libmcilm_link.so`。
3. 点击 `Build - Make Project`。
4. 准备一台真机设备（arm64 架构）运行应用。

> ⚠️ **模拟器特别说明**：官方库为 AArch64 汇编编译，x86_64 模拟器（如 MuMu、雷电）的翻译层可能无法处理某些 Go 汇编指令，导致 SIGILL 闪退。强烈建议在 ARM64 真实手机上进行完整联机测试。

## 六、 待办事项 / 后续计划 (Todo)

**已完成（v1.4.x）：**
- [x] 语音联机接入与状态同步 (`link_set_audio`)。
- [x] Minecraft 局域网广播 (`link_set_extra`)。
- [x] 文件同步 SAF 自选目录、进度与打开。
- [x] 网络抗波动（打洞流量细分、断网缓冲、重连防竞态）。
- [x] 房主踢人、聊天控制面板。
- [x] 架构解耦（ViewModel 拆分）。
- [x] 平台生态接入：公告、活动领取、邀请好友、使用反馈。
- [x] 后台保活前台服务与首次登录引导。
- [x] 计费与时长完整性闭环（静默签到、权益拦截、耗尽横幅）。
- [x] 扫码组件升级 (`ScanContract`)。

**待办：**
- [ ] 节点延迟监控 UI 渲染（目前仅通过 `link_set_log` 日志解析，未做成状态栏面板）。
- [ ] 多设备适配：小米/OPPO/vivo 等国产 ROM 的 AEC（回声消除）特殊兼容性回归测试。
- [ ] 大文件传输：文件断点续传与分块传输（依赖底层引擎支持）。
