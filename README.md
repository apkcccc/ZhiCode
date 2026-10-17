# ZhiCode — Android 上的编码 Agent

一个跑在 Android 手机上的**编码 Agent 工作区**：Kotlin + Jetpack Compose 界面（[Miuix](https://github.com/compose-miuix-ui/miuix) 原生主题），
Java 编写的 Agent 引擎与协议层，应用内自带一套 Termux 用户空间，并内置一个虚拟化运行时用于免安装运行与调试 APK。

| 项 | 值 |
| --- | --- |
| 应用名 / 包名 | `ZhiCode` / `com.zhizhu.code` |
| 版本 | `versionName 0.2`，`versionCode 1` |
| 仓库名 | `IQ-Code-Compose`（`settings.gradle` 的 `rootProject.name`） |
| 平台 | Android，**仅 arm64-v8a** |
| SDK | `compileSdk 37` / `targetSdk 28` / `minSdk 24` |
| 界面 | Jetpack Compose + Miuix `0.9.4` |
| 本项目代码许可 | MIT（[`LICENSE`](LICENSE)）；发行物另含第三方组件，见 [`NOTICE`](NOTICE) |

> **先说清楚适用范围。** 这是 Android 单平台工程，不是跨端项目；内置 Termux 用户空间与预编译原生库都是 arm64 的，
> 所以 32 位设备、模拟器（x86_64）都不在支持范围内。

---

## 1. 它现在是什么

不是 UI 原型：引擎、Termux 环境、沙箱、会话存储都已经接上真实实现。

| 能力 | 实现位置 |
| --- | --- |
| Agent 主循环 | `app/src/main/java/com/termux/app/zhicode/core/ZhiCodeEngine.java` —— 流式输出、工具调用、子代理、steering、历史自愈 |
| 模型协议 | `core/` 同级的 `api/`（OpenAI Responses / Chat Completions / Anthropic 等适配），支持自定义 Base URL 与明文 HTTP 开关 |
| 工具集 | `com/termux/app/zhicode/tools/`（`ToolRegistry` + 各 `ZhiTool` 实现，含 Bash / 文件读写 / WebSearch / 沙箱 / Debug） |
| 内置终端 | 自带 Termux bootstrap（`app/src/main/assets/bootstrap-aarch64.zip`）→ 真实 PTY（`TerminalSession` + `libtermux.so`） |
| 虚拟化沙箱 | `Bcore/`（BlackBox 血统）+ 宿主层 `com/zhizhu/zhicode/sandbox/` —— 免安装运行 APK、Frida 注入、原生内存调试 |
| MCP / 技能 / 角色卡 / 记忆 | `McpStore`、`McpRuntime`、`SkillStore`、`RoleCardStore`、`MemoryStore` |
| 会话与任务 | `SessionStore`（写 SQLite 单库 `~/.zhicode/sessions.db`）、`TaskStore`、`PlanStore` |
| 编辑器 | 独立 `EditorActivity`（独立进程 `:editor`）+ Rosemoe Sora Editor + TextMate 语法高亮 |
| 权限门控 | `PermissionGate` / `PermissionModePolicy` / `RiskClassifier`，模式可在设置页与输入器页脚切换（见 [`SECURITY.md`](SECURITY.md)） |
| shell 身份 | `com/zhizhu/zhicode/shizuku/` + `tools/ShizukuBashTool`：经 [Shizuku](https://github.com/RikkaApps/Shizuku) 以 uid 2000 跑 `pm/am/settings/dumpsys/input` 这类系统命令，**不需要设备 root**；开关默认关闭，命令执行前校验真实 uid |

顶层模块：

| 模块 | 内容 | 许可 |
| --- | --- | --- |
| `app` | 应用本体：Compose 界面层 + 引擎 / 协议 / 工具 / 宿主层 | MIT（本工程代码） |
| `Bcore` | BlackBox 血统的虚拟化运行时 | Apache-2.0（第三方） |
| `black-reflection` | Bcore 的注解驱动反射 | Apache-2.0（第三方） |
| `compiler` | 编译期注解处理器 | Apache-2.0（第三方） |

---

## 2. 明确未做 / 已知限制

- **没有悬浮球、也没有 Frida / Debug 的图形面板** —— 调试走内置命令 `zhisandbox` / `zhidebug` 与 Agent 的 Debug 工具。
- **Shizuku 要先自己装、自己启动**：本工程只做授权与调用，不打包 Shizuku 管理端，也不能替你启动它的服务。
  没装或服务没起来时，`/shizuku status` 与设置里的那一行会如实说明卡在哪一步，工具本身不会假装成功。
- **没有原版 IQ Code 的三套自定义调色板** —— 界面统一走 Miuix 原生主题，跟随系统深浅色；
  本工程自己的语义色在 `theme/` 的 `ZhiColors` 里（成功/失败/警示与层级色）。
- **不做 iOS / 桌面端**。
- **`targetSdk 28`**：这让应用保留旧版存储与后台行为的语义（`requestLegacyExternalStorage`、`usesCleartextTraffic` 等）。
  这是一处**刻意的取舍**，不是遗漏；升级 `targetSdk` 会连带改变存储、前台服务与明文流量行为，需要单独评估。
- **内置沙箱不是安全隔离边界**。它是为了「免安装运行 / 调试」，不是用来关住不可信应用的（见 [`SECURITY.md`](SECURITY.md)）。
- **部分权限来自库清单合并**，而不是 `app` 模块自己声明的：`Bcore/src/main/AndroidManifest.xml` 里有数百条
  `uses-permission`（**具体条数会随 Bcore 更新变化，请按下面命令现场数**），合并后 APK 里会出现一批
  `app` 模块自己从未声明的**危险权限**（`SYSTEM_ALERT_WINDOW`、`READ_SMS`、`CAMERA`、`RECORD_AUDIO`、
  `QUERY_ALL_PACKAGES` 等）。其中许多是 signature/privileged 级别、普通应用拿不到，
  但**危险权限会真实出现在安装页的权限列表里**。这是当前实现的一个已知问题，详见 [`SECURITY.md`](SECURITY.md) 第 4 节。
- **文档里的历史数字不是承诺**：性能、体积、行数、权限条数一类的度量随提交变化，正文只保留方法，
  数字请按文档里的命令自己跑。**本文件尤其不写死源码文件数**（每加一个类就过期）：

  ```bash
  find app/src/main/java -name '*.java' | wc -l
  find app/src/main/java -name '*.kt'   | wc -l
  # 合并后的权限条数（先构建一次 debug）
  grep -c '<uses-permission' \
    app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
  ```

---

## 3. 构建

环境要求：**JDK 21**、Android SDK（`platforms;android-37` 与 `build-tools`）、能联网拉 Gradle 依赖。

```bash
# debug
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/ZhiCode-debug.apk

# release（需要签名配置，见下）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/ZhiCode-release.apk
```

工具链版本（由 wrapper 与 `build.gradle` 决定，不要单独升级其中一个）：

| 项 | 版本 |
| --- | --- |
| JDK | 21 |
| Gradle | 9.3.1（`gradle/wrapper/gradle-wrapper.properties`） |
| AGP | 9.1.1 |
| Kotlin / Compose 插件 | 2.4.0 |
| Miuix | 0.9.4 |
| Sora Editor | 0.24.6 |

依赖与版本锁定的理由、R8 规则、Baseline Profile、签名与 CI 的细节见 **[`docs/build-and-release.md`](docs/build-and-release.md)**。

### 在 Termux（bionic）里构建

AGP 自带的 aapt2 在 bionic 下跑不起来，需要指向 Termux 的 aapt2 —— 这是**本机配置，不进仓库**：

```bash
# ~/.gradle/gradle.properties
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
```

普通 Linux / macOS / Windows 开发机不需要这一项。细节见 [`docs/build-and-release.md`](docs/build-and-release.md) 第 2 节。

### 发布签名

签名配置从**仓库之外**读取，优先级为 `release.properties`（已 gitignore）→ 环境变量
`ZHICODE_STORE_FILE` / `ZHICODE_STORE_PASSWORD` / `ZHICODE_KEY_ALIAS` / `ZHICODE_KEY_PASSWORD`。
两者都读不到时 `assembleRelease` **仍然能构建**，只是产物未签名并打印一条警告 —— 这是为了让 clone 之后不卡在密钥上。

签名只启用 **APK Signature Scheme v2**（v1 / v3 / v4 显式关闭）。发布时请一并留存
`app/build/outputs/mapping/release/mapping.txt`，否则用户报的崩溃栈无法还原。

### CI

`.github/workflows/build.yml` 在 GitHub 托管的 runner 上构建 debug 与 release：装 JDK 21、Android SDK，
删掉本机的 `local.properties` 与 `aapt2FromMavenOverride`，然后 `:app:assembleDebug :app:assembleRelease`。
签名相关的 4 个 secret（`RELEASE_KEYSTORE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）齐全时会校验并产出已签名包，
并断言「v2 为真、v1/v3/v4 为假、签名者数量为 1」；**缺任何一个 secret 时不失败**，改为产出 `zhicode-release-apk-unsigned`
这一档产物（fork 的 PR 拿不到 secret，这是有意为之）。

---

## 4. 验证与测试

仓库里只剩一条测试入口 —— `app/src/test/` 下**真正跑起来的 JVM 单测**：

```bash
bash test-jvm-fast.sh                 # 快路径：只编被测试依赖的那几个文件，秒级
./gradlew :app:testDebugUnitTest      # 完整跑法：会走完整 Kotlin / 资源编译，分钟级
```

覆盖面是 `app/src/test/java/` 下的纯逻辑：`zhicode/api/`（SSE 分帧、协议解析、失败分类）、
`zhicode/core/`（权限档位、风险分类、文件操作、上下文压缩）、`zhicode/tools/`、
`compose/model/` 与 `compose/theme/`。这些类刻意不 `import android.*`，所以能在 JVM 上直接跑。

> **工程里曾经另有一套「源码文本级结构断言」**（`app/tests/*.java` + `test-source-no-build.sh`），
> 它靠 grep 源码字符串来钉住约定，当时**有一部分是红的**，已整套删除。
> 现在**没有**任何自动化的约定守卫：`docs/` 里写的那些「不能这么写」的约定，
> 只能靠读代码与 review 来守。删掉它们的取舍是明确的 —— 换来的是一个不再常驻红状态的仓库。

---

## 5. 调试：内置沙箱与命令行

沙箱的高层控制面在独立进程 `:zhisandbox`，主进程只通过同 UID 的私有 provider 与它通信：

```bash
zhisandbox <action> [package] [json]        # 安装 / 启动 / 停止 / 清数据 / dump_ui / screenshot / log
zhidebug   <action> [package-or-pid] [json] # 进程列表 / maps / modules / 内存 / Frida
```

这两条命令由宿主层写进内置 Termux 的 `bin`（见 `SandboxShell`），与 Agent 系统提示里写的命令名一致。
Compose 界面在 `dump_ui` 里通常只呈现一个 `AndroidComposeView`，因此界面验证以**截图**为主。

沙箱宿主层的职责划分、进程角色、不得放宽的安全边界，以及历史上踩过的坑（旋转后终端空白、guest 的
`profile: Permission denied` 等）见 **[`docs/sandbox-host.md`](docs/sandbox-host.md)**。

---

## 6. 项目结构

`#` 后面是该条目的一句话说明。最后一张表是「想改什么 → 去哪个文件」，改代码时从它进最快。

### 6.1 目录结构

```
.
├── app/ # 应用模块：Compose 界面 + Agent 引擎 / 协议 / 工具 / 沙箱宿主
│ ├── build.gradle # SDK 版本、applicationId、签名、R8、依赖 —— 包名唯一来源
│ ├── proguard-rules.pro # R8 keep 规则（反射与 JNI 名字绑定）
│ └── src/
│   ├── main/
│   │ ├── java/ # 源码两棵树，展开如下
│   │ │ ├── com/termux/app/zhicode/ # Agent 侧（Java 为主）
│   │ │ │ ├── core/ # ZhiCodeEngine 主循环、PermissionGate / PermissionModePolicy、RiskClassifier、
│   │ │ │ │         ContextCompactor、FileOps、SystemPromptBuilder、SteeringQueue
│   │ │ │ ├── api/ # 模型协议层：Responses / Chat Completions / Anthropic 三适配、
│   │ │ │ │        ApiUrlPolicy 地址白名单、ProviderTransport、流式解析与失败分类
│   │ │ │ │ ├── compat/ # 跨协议映射（推理档位等）
│   │ │ │ │ └── zcode/ # 自有适配（ZcodeWire / ZcodeHarness / ZcodeDeviceMid）
│   │ │ │ ├── tools/ # 工具实现 + ToolRegistry / ToolSchemas / PathPolicy / UnifiedDiff
│   │ │ │ ├── agents/ # 子代理定义、加载与调度（SubagentManager）
│   │ │ │ ├── mcp/ tasks/ # MCP 运行时 · TaskStore
│   │ │ │ ├── storage/ # 会话（SessionStore / SessionDatabase / BlobStore /
│   │ │ │ │            BinaryValueCodec / LegacySessionImporter / ProjectPaths）、
│   │ │ │ │           设置、计划、MCP 配置、原子写（AtomicFiles）
│   │ │ │ ├── model/ # SessionConfig、ToolCall、AssistantTurn、ToolExecutionResult
│   │ │ │ ├── security/ # AndroidSecretStore（Android Keystore + AES-GCM）
│   │ │ │ ├── termux/ # TermuxShellExecutor（内置 shell 的执行入口）
│   │ │ │ └── json/ # JsonItems
│   │ │ ├── com/termux/ # Termux 上游（Apache-2.0 第三方，见 NOTICE 第 3 节）
│   │ │ │ ├── terminal/ # 终端缓冲区 / emulator / session（经 JNI 调 libtermux.so）
│   │ │ │ ├── view/ # 终端视图 / 渲染器 / 手势 / 文本选择
│   │ │ │ └── shared/termux/ # TermuxConstants —— 路径与品牌 slug 的唯一来源（已整体重写）
│   │ │ └── com/zhizhu/zhicode/ # 应用侧（Kotlin 为主）
│   │ │   ├── MainActivity.kt # setContent { ZhiCodeApp() }
│   │ │   ├── ZhiCodeApplication.kt # 进程角色判定与启动期装配
│   │ │   ├── RuntimeInstaller.kt # bootstrap 解压与安装
│   │ │   ├── TermuxTerminalPane.java # 终端面板的 View 与状态宿主
│   │ │   ├── FileTree.kt ZhiFileProvider.java ZhiDocumentsProvider.kt # 文件树 / 分享 / SAF
│   │ │   ├── UiCanvasController.java UiCanvasStore.java # UI canvas 覆盖层
│   │ │   ├── compose/ # Compose 界面层
│   │ │   │ ├── theme/ # 圆角 / 间距 / 动效 / 字阶 token + 主题入口（颜色全部来自 Miuix）
│   │ │   │ ├── model/ # UiModels（含 WorkspaceUiState）、ActionBusy、ToolKind、TurnLayout、ToolGrouping
│   │ │   │ ├── data/ # API 配置、模型目录、MCP、技能、角色卡、记忆、搜索服务、会话读取
│   │ │   │ ├── state/ # WorkspaceViewModel（StateFlow<WorkspaceUiState> + 引擎事件）
│   │ │   │ ├── engine/ # ZhiEngineController（引擎接线与流式合并）、ToolText
│   │ │   │ ├── runtime/ # EnvDoctor（环境自检）
│   │ │   │ ├── editor/ # EditorActivity 等 —— 独立进程 :editor
│   │ │   │ └── ui/ # 界面全部
│   │ │   │     ├── pages/ # **一个页面一个文件**：WorkspacePage / ChatPage / TerminalPage /
│   │ │   │     │          FilesPage / EditorPage / SettingsPage / ApiConfigPage / McpConfigPage /
│   │ │   │     │          SearchServicesPage / SkillsPage / RoleCardsPage / MemoryPage /
│   │ │   │     │          SandboxPage / UiDebugPage
│   │ │   │     ├── pages/overlays/ # 浮层：权限 / 计划审批 / 选择器 / 任务清单 / 模型 / 附件 / 环境 / 调试浮层
│   │ │   │     ├── Common.kt # Miuix 唯一转发层（Zhi* 包装）
│   │ │   │     ├── PageFeedback.kt # 非对话页的操作反馈条（含页面级数据源）
│   │ │   │     ├── AppScaffold.kt # 主题 + 导航栈 + 页面路由（不再放页面内容）
│   │ │   │     └── chat/ composer/ panes/ dialogs/ settings/ debug/ # 共享组件与外框
│   │ │   ├── sandbox/ # 沙箱宿主层（16 个类：引擎门面 / RPC / guest 桥 / Frida / 调试 / 保活）
│   │ │   └── background/ # 前台保活
│   │ ├── assets/ # bootstrap-aarch64.zip、zhicode/*.sh（6 个前缀改写钩子）、sora/textmate/
│   │ ├── jniLibs/arm64-v8a/libtermux.so # 原生 PTY 桥（Apache-2.0）
│   │ ├── res/drawable/ # 只有一个 XML —— 界面几乎全部由 Compose 绘制
│   │ ├── AndroidManifest.xml # 权限 / 组件 / 进程（:editor、:zhisandbox 都在这里）
│   │ └── baseline-prof.txt # 手写冷启动 profile
│   └── test/ # JVM 单测（java/）+ 基线数据（resources/），跑法见第 4 节
├── Bcore/ # 虚拟化运行时（BlackBox 血统，Apache-2.0）
├── black-reflection/ # Bcore 的注解驱动反射（Apache-2.0）
├── compiler/ # 编译期注解处理器，不进 APK（Apache-2.0）
├── docs/ # 七份专题文档，清单见第 11 节
├── tools/ # provenance 度量 / 历史改写 / 图标重取 / bootstrap 构建配方
├── .github/workflows/build.yml # CI：构建 debug + release，按 4 个 secret 决定是否签名
├── gradle/ gradlew gradlew.bat # Gradle wrapper（版本写死在 gradle-wrapper.properties）
├── settings.gradle # 四个模块的声明与仓库配置
├── build.gradle gradle.properties # 根构建脚本与 Gradle 开关
├── test-jvm-fast.sh # JVM 单测快路径，见第 4 节
├── LICENSE NOTICE THIRD-PARTY-LICENSES/ # 三份必须随发行物给出的许可文件
├── README.md CONTRIBUTING.md SECURITY.md # 本文件与两份约定
└── local.properties release.properties # 本机专属、已 gitignore：SDK 路径 / 签名密钥库
```

> ⚠️ `local.properties` 与 `release.properties` **不是可以清理的垃圾** —— 删了就构建不了 / 签不了名，且无法从仓库恢复。
> 同理 `gradle/wrapper/gradle-wrapper.jar` 看着像产物，其实是构建必需。

### 6.2 按功能找文件

| 想改什么 | 去这里 |
| --- | --- |
| 引擎主循环、工具调用时序、子代理 | `com/termux/app/zhicode/core/ZhiCodeEngine.java` |
| 模型请求与流式解析 | `com/termux/app/zhicode/api/` |
| 加一个工具 | `.../zhicode/tools/` + `ToolRegistry.java` + `ToolSchemas.java` |
| 权限怎么判、怎么问 | `.../zhicode/core/PermissionGate.java`、`PermissionModePolicy.java` |
| Agent 的系统提示词 | `.../zhicode/core/SystemPromptBuilder.java` |
| 会话如何落盘 | `.../zhicode/storage/SessionStore.java`（读写）＋ `SessionDatabase.java`（建表与回收） |
| 会话数据长什么样 | `~/.zhicode/sessions.db`（SQLite 单库）＋ `~/.zhicode/blobs/aa/bb/<sha256>`（大内容） |
| 落盘编码（不存 JSON 文本） | `.../zhicode/storage/BinaryValueCodec.java` |
| 旧会话怎么搬过来 | `.../zhicode/storage/LegacySessionImporter.java`（一次性，校验通过才删旧文件） |
| 读历史为什么只读最近 200 条 | `.../storage/SessionStore.java` 的 `readRowsWindow` ＋ `compose/data/SessionReader.kt` 的 `openPage` |
| 对话流为什么有 600 条上限 | `compose/state/WorkspaceViewModel.kt` 的 `maxResidentItems` / `trimResidentTail` / `jumpToLatest` |
| 会话大内容怎么回收 | `SessionDatabase.blob_refs` + `SessionStore.maintain()`（宽限期、增量 vacuum） |
| 会话怎么导出 / 导入 | `.../storage/SessionArchive.java`（唯一的 `.jsonl.gz` 交换格式入口） |
| 内存诊断日志（release 也打） | `.../storage/ZhiMemory.java`，tag `ZhiCodeMem` |
| 项目目录键（计划 / 任务用） | `.../zhicode/storage/ProjectPaths.java` |
| 内置 shell 怎么执行命令 | `.../zhicode/termux/TermuxShellExecutor.java` |
| 界面 token（圆角 / 间距 / 动效 / 字阶） | `com/zhizhu/zhicode/compose/theme/`（**颜色不在这里**） |
| 某个页面 | `compose/ui/pages/<XxxPage>.kt` —— 一个页面一个文件，文件名即入口名 |
| 某个浮层 | `compose/ui/pages/overlays/<Xxx>Overlay.kt` |
| 某个界面组件（共享） | `compose/ui/<区域>/`；先确认 `Common.kt` 里有没有现成的 `Zhi*` 包装 |
| 颜色 | `MiuixTheme.colorScheme` + `theme/ZhiColors` 的语义色（两套都随深浅切换） |
| 页面操作反馈为什么看不见 | `compose/ui/PageFeedback.kt`（非对话页 / 整页的反馈条） |
| 双击按钮会不会做两遍 | `compose/model/ActionBusy.kt` + `WorkspaceViewModel.runBusy` |
| 界面状态与事件流 | `compose/state/WorkspaceViewModel.kt` |
| 沙箱进程模型与不变式 | `com/zhizhu/zhicode/sandbox/` + [`docs/sandbox-host.md`](docs/sandbox-host.md) |
| R8 keep 规则 | `app/proguard-rules.pro`、`Bcore/consumer-rules.pro` |
| 包名 / SDK 版本 / 依赖 | `app/build.gradle` |

> 这里**不写死文件数与行数** —— 每加一个类就过期。要数自己跑：
> `find app/src/main/java -name '*.java' | wc -l`、`find app/src/main/java -name '*.kt' | wc -l`。

更细的分层、状态与引擎如何对接、以及沙箱层不得放宽的不变式见 **[`docs/architecture.md`](docs/architecture.md)**。

---

## 7. 界面层约定（要点）

- **Miuix 组件只在 `compose/ui/Common.kt` 里转发**：调用点用 `Zhi*` 包装，不直接引库，库升级只影响一个文件。
- **颜色走 `MiuixTheme.colorScheme` / `ZhiColors`，不要在调用点写死色值**：层级色与语义色都有现成 token，
  深浅两套由主题决定。
- **一个页面一个文件**：整页放 `ui/pages/<XxxPage>.kt`，浮层放 `ui/pages/overlays/`；`AppScaffold.kt` 只留主题、导航栈与路由。
- **尺寸 / 字阶取既有 token 或 Miuix 主题**，不要在调用点写新的裸数字与裸字号。
- **会落盘的动作走 `ViewModel.runBusy(...)`**：同一动作在飞时第二次点击被静默丢弃，界面用 `state.isBusy(key)` 禁用按钮。
- 输入框一律走 `ZhiTextField`，长按菜单一律走 `ZhiAnchoredActionMenu` —— 两处都有明确的失败模式（前者会丢文字色与光标位置，后者在观察器消费事件时会把点击与无障碍语义顶掉），改之前先看 [`docs/ui-miuix.md`](docs/ui-miuix.md) 第 6 节。

> **约定没有自动化守卫。** 上面这些约束写错了**不会编译失败**，而仓库里已经不再有源码文本级断言（见第 4 节），
> 所以它们只能靠读代码与 review 来守 —— 别把它们当成「已经确认满足」的结论。

有几个 Miuix 组件是**刻意不用**的（`WindowDialog`、`RadioButton`、`TabRow`、`InputField`、`SnackbarHost` 等），
弹窗上还有一批只有真机才看得出来的坑。改界面前请先读 **[`docs/ui-miuix.md`](docs/ui-miuix.md)**。

---

## 8. 性能

目标设备包含 **Android 7 / 8 那一代**（`minSdk 24`）：CPU 慢、闪存慢、GPU 弱。相关改动**不会报错、不会崩溃**，
只会掉帧与发烫 —— 它们靠约定守，而不是靠「看着还行」；而按第 4 节，这些约定目前**没有自动化守卫**。

面向该档位的取舍（流式 Markdown 的增量解析、`LazyColumn` 单个 item 的节点上限、重组路径上不做 O(n) 字符串手术、
主线程不碰磁盘 IO、热路径组件不收整份 `WorkspaceUiState`、列表贴底不用挂起动画等）逐条写在
**[`docs/performance.md`](docs/performance.md)**。

---

## 9. 安全

⚠️ **这是一个会执行任意代码的应用**：自带 shell、由模型决定执行什么命令、还能在虚拟运行时里跑任意 APK，
并提供 Frida / 内存调试能力。请把它当成开发工具，而不是普通应用。

- 安全模型、权限模式（含「跳过权限」）、密钥存储（Android Keystore + AES-GCM）、网络行为、
  **实际合并后的权限清单**与已知取舍：[`SECURITY.md`](SECURITY.md)。
- **安全漏洞请不要开公开 issue**，按 [`SECURITY.md`](SECURITY.md) 里的方式私下报告。

---

## 10. 许可与第三方

本工程**自己写的代码**按 **MIT** 发布，见 [`LICENSE`](LICENSE)。

**但发行物不是一个纯 MIT 包。** 一个 APK 里同时有：

| 类型 | 例子 | 许可 |
| --- | --- | --- |
| 本工程代码（含对上游的重写部分） | `compose/`、沙箱宿主层、工具与协议层 | MIT |
| 源码并入的第三方模块 | `Bcore/`、`black-reflection/`、`compiler/`（BlackBox 血统） | Apache-2.0 |
| 源码并入的第三方代码 | `com/termux/terminal/`、`com/termux/view/` | Apache-2.0（termux-app 的例外条款） |
| 链接进进程的原生库 | `app/src/main/jniLibs/arm64-v8a/libtermux.so` | Apache-2.0 |
| 预编译的用户空间归档 | `app/src/main/assets/bootstrap-aarch64.zip`（bash / coreutils / apt 等独立程序） | **各程序各自的许可**（含 GPLv2+/GPLv3） |
| Gradle 依赖 | Miuix（Apache-2.0）、Sora Editor（LGPL-2.1）、AndroidX 等 | 随构件分发 |
| 数据文件 | Material Symbols 图标路径、TextMate 语法与主题 | Apache-2.0 / MIT |

因此有两条硬要求：

1. **分发时必须一并提供 [`NOTICE`](NOTICE) 与 [`THIRD-PARTY-LICENSES/`](THIRD-PARTY-LICENSES)** —— MIT 要求随附版权与许可声明，
   Apache-2.0 §4 要求给接收者许可证副本、保留声明、并对修改过的文件作出声明。
2. **bootstrap 内各程序的源码出处要对接收者可得**（`termux-packages` 与本仓库的构建配方
   [`tools/termux-bootstrap-fork/`](tools/termux-bootstrap-fork)）。

`LICENSE` 只覆盖本工程自己的代码；第三方组件**不受它约束**。逐项清单（来源、许可、对应文件、是否修改）见
[`NOTICE`](NOTICE)；判断依据、复核命令与**目前尚未闭合的合规缺口**见 [`docs/licensing.md`](docs/licensing.md)。

> 本文档不构成法律意见。凡涉及「上游当时是什么许可」「某条链路是否构成衍生作品」的判断，
> [`docs/licensing.md`](docs/licensing.md) 都写明了证据强度：能核到的给命令，核不到的标成未确证。

---

## 11. 文档索引

| 文件 | 内容 |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | 代码分层、状态与引擎如何对接、沙箱层不得放宽的不变式 |
| [`docs/build-and-release.md`](docs/build-and-release.md) | 工具链、R8、Baseline Profile、签名、CI |
| [`docs/ui-miuix.md`](docs/ui-miuix.md) | Miuix 转发层、刻意不用的组件、弹窗踩过的坑、动画 token |
| [`docs/performance.md`](docs/performance.md) | 面向低端档位的每一条性能取舍与它守住的机制 |
| [`docs/sandbox-host.md`](docs/sandbox-host.md) | 沙箱宿主层的实现、进程角色、调试方式与已知问题 |
| [`docs/provenance.md`](docs/provenance.md) | Bcore 相对上游的补丁、沙箱层不变式 |
| [`docs/licensing.md`](docs/licensing.md) | 许可判断依据、复核方法、未确证事项与合规缺口 |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | 提交前的最小验证、不要顺手做的事、改界面/性能/构建前的硬约定 |
| [`SECURITY.md`](SECURITY.md) | 安全模型、权限、密钥、漏洞报告方式 |

---

## 12. 贡献

欢迎参与。提 PR 前请读 [`CONTRIBUTING.md`](CONTRIBUTING.md)：改动范围贴着要修的问题、不要顺手升级依赖、
不要把自己的临时调试代码提交上去。最小的本地验证是 `git diff --check` 与
`./gradlew :app:compileReleaseKotlin --offline`；改到纯逻辑时再跑 `bash test-jvm-fast.sh`。

---

## 13. 上游与致谢

- **IQ Code**（<https://github.com/iqisge-gif/IQ-Code-Android>，MIT，© 2026 IQge）—— 本工程的起点。
  协议层、Agent 主循环与工具集、沙箱宿主层的早期实现都从它那里起步，之后被分批重写（度量方式见
  [`tools/provenance.sh`](tools/provenance.sh) 与 [`docs/licensing.md`](docs/licensing.md)）。
  它的许可原文随发行物一同提供：[`THIRD-PARTY-LICENSES/IQ-Code-MIT.txt`](THIRD-PARTY-LICENSES/IQ-Code-MIT.txt)。
- [Miuix](https://github.com/compose-miuix-ui/miuix)（Apache-2.0）—— 全部界面组件与主题。
- [BlackBox](https://github.com/ALEX5402/NewBlackbox)（Apache-2.0）—— 虚拟化运行时引擎。
- [Dobby](https://github.com/jmpews/Dobby)（Apache-2.0）—— 内联 hook，**静态链接在 `libblackbox.so` 内**
  （证据与核对命令见 [`NOTICE`](NOTICE) 第 2.1 节与 [`docs/licensing.md`](docs/licensing.md) 第 5.4 节）。
- [Termux](https://github.com/termux)（各组件许可见 `NOTICE`）—— 内置 Linux 环境与终端模拟。
- [Rosemoe Sora Editor](https://github.com/Rosemoe/sora-editor)（LGPL-2.1）—— 编辑器与 TextMate 语法高亮。
- TextMate 语法与主题、Material Symbols 图标路径数据：来源与许可见 [`NOTICE`](NOTICE) 第 7、10 节；
  逐项清单、版权行、已知的本地修改，以及**尚未核到来源的几项**见
  [`app/src/main/assets/sora/textmate/NOTICE.md`](app/src/main/assets/sora/textmate/NOTICE.md)。

> 工程名是 **ZhiCode**；界面里助手自称「**智蛛**」—— 两者不是同一件事：前者是仓库名、应用名与包名来源，
> 后者只是界面文案里的人称。
