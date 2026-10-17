package com.zhizhu.zhicode.compose.model

/**
 * 第三方组件清单（「关于 → 第三方组件许可」的数据源）。
 *
 * ## 为什么不是一个依赖
 *
 * rikkahub 与 Miuix 示例都用 `aboutlibraries` 插件在构建期扫依赖、生成 JSON。
 * 本工程**不引入**它，理由有三，且都是具体的：
 *
 * 1. 这个环境是离线的（`--offline` 构建），新插件拉不下来；
 * 2. 它只扫得到**声明在 Gradle 里**的依赖，而本工程最需要声明的几项恰恰不是依赖：
 *    `libtermux.so`、`libblackbox.so`（内含静态链接的 Dobby）、Termux bootstrap 归档、
 *    `assets/sora/textmate` 的数据文件、内联进 `ZhiMaterialIcons.kt` 的图标路径；
 * 3. 许可名仍然要人写映射（插件给的是坐标，不是中文可读的许可条款名）。
 *
 * 所以这里手写一份**可核对**的清单：每条的 [name] 必须能在根目录 `NOTICE` 里找到同名小节
 * （`LicensesTest` 会逐条比对这个字符串），[license] 必须与 `NOTICE` 里那一节声明的一致。
 * 手写的东西靠测试与源文档对齐，而不是靠"我记得"。
 *
 * ## 字段含义
 *
 * @property name 组件名（= `NOTICE` 里的小节标题，去掉编号）
 * @property license 许可（与 `NOTICE` 一致）
 * @property version 版本或"随发行物"一类的说明；没有版本的写 `—`
 * @property detail 给用户看的补充：这项为什么在这里、有没有改动、有什么已知缺口
 */
data class LicenseEntry(
    val name: String,
    val license: String,
    val version: String,
    val detail: String,
)

/**
 * 全部第三方组件。
 *
 * <p>顺序与根 `NOTICE` 的小节顺序一致 —— 这不是巧合，而是刻意的：
 * 两份文档并排看时顺序一致，核对的人就不用来回跳。
 */
val THIRD_PARTY_LICENSES: List<LicenseEntry> = listOf(
    LicenseEntry(
        name = "IQ Code",
        license = "MIT",
        version = "—",
        detail = "本工程的起点。协议层、Agent 主循环与工具集、沙箱宿主层的早期实现都从它那里起步，" +
            "之后被分批重写。重写不免除 MIT 的署名义务，这份致谢是**自愿**保留的 —— " +
            "归属度量工具一直在拿本工程与原版逐行比对，派生关系本来就是自证的，" +
            "把出处写清楚比含糊过去更诚实。",
    ),
    LicenseEntry(
        name = "BlackBox 虚拟化引擎（Bcore / black-reflection / compiler）",
        license = "Apache-2.0",
        version = "—",
        detail = "Android 虚拟运行时：guest 进程管理、Activity/Service 代理、系统服务 hook。" +
            "经上游 IQ Code 的副本搬入。本工程对 Bcore 的构建脚本与源码有改动；" +
            "black-reflection 与 compiler 的源码未改动。逐模块的修改声明在各自目录的 NOTICE 里。",
    ),
    LicenseEntry(
        name = "静态链接在 libblackbox.so 内的 Dobby",
        license = "Apache-2.0",
        version = "—",
        detail = "内联 hook 引擎。它不是一个独立文件，而是被静态链接进 Bcore 的预编译原生库 " +
            "libblackbox.so，因此不会出现在依赖列表里 —— 只能从二进制内容上认出来。" +
            "本工程未修改其源码，也未对它做过完整的静态内容审计。",
    ),
    LicenseEntry(
        name = "Termux 终端模拟与视图",
        license = "Apache-2.0",
        version = "—",
        detail = "terminal-emulator 与 terminal-view 两个子库，以及原生 PTY 桥接库 libtermux.so。" +
            "termux-app 整体是 GPLv3-only，但其根许可证明确给这两个子库开了 Apache-2.0 的例外 —— " +
            "libtermux.so 是被 System.load 链接进本应用进程的，所以这一点决定了本工程不必整体转为 GPL。",
    ),
    LicenseEntry(
        name = "termux-shared · TermuxConstants",
        license = "MIT",
        version = "—",
        detail = "该库的许可证把 termux-shared 整体定为 MIT，但其中 termux/ 目录是 GPLv3-only，" +
            "并点名排除 TermuxConstants.java 与 TermuxPropertyConstants.java。" +
            "本工程用的是后者，且**整体重写**过：路径在运行期由包名派生，不再写成常量。",
    ),
    LicenseEntry(
        name = "Termux 用户空间（bootstrap 归档）",
        license = "各程序各自的许可",
        version = "—",
        detail = "归档内是数百个独立可执行程序，各自适用各自的许可，其中包含 GPL-3.0（bash / coreutils / " +
            "sed / grep）、GPL-2.0+（apt / dpkg）、Apache-2.0（openssl）以及 LGPL / MIT / BSD 变体。" +
            "它们是**独立可执行文件**，由本应用以子进程方式启动，与本工程代码不构成衍生作品 —— " +
            "GPL 明确允许把它们与其它许可的程序聚合分发。",
    ),
    LicenseEntry(
        name = "通过 Gradle 依赖引入的组件",
        license = "Apache-2.0（除已注明项）",
        version = "见构建清单",
        detail = "以 Maven 依赖引入、不修改也不内联源码，各自许可随构件分发：" +
            "Miuix 0.9.4（ui/core/icons/preference/shader/squircle/nav/blur-android）、" +
            "activity-compose、lifecycle-viewmodel/runtime-compose、profileinstaller。" +
            "构建期由 AGP 自动并入的 AndroidX / Jetpack Compose 运行时构件同理。",
    ),
    LicenseEntry(
        name = "Material Symbols 图标",
        license = "Apache-2.0",
        version = "—",
        detail = "不是依赖，而是把上游字形的路径数据拷进了源码（ZhiMaterialIcons.kt）。" +
            "路径数据本身逐字未改，只多了一层视口换算。仓库里有重新取用的脚本，" +
            "重跑后 git diff 应为空 —— 那是核对「文件里这份数据还是上游那份」的方法。",
    ),
    LicenseEntry(
        name = "MaterialFiles 交互参考",
        license = "GPL-3.0 · 未复制源码",
        version = "—",
        detail = "本工程参考它的独立文件页与顶部层级交互形态，**未复制**其任何源码或资源。" +
            "因此这一项是设计参考，不是随发行物链接或内嵌的第三方代码。",
    ),
    LicenseEntry(
        name = "Rosemoe Sora Editor",
        license = "LGPL-2.1",
        version = "0.24.6",
        detail = "编辑器使用它的增量文本布局、缩放、滚动与 TextMate 语法高亮；未复制其源码。" +
            "已知缺口：LGPL 通常还涉及「允许使用者替换为修改后的库版本」这一条，" +
            "本工程尚未评估这一点。",
    ),
    LicenseEntry(
        name = "TextMate 语法、语言配置与主题",
        license = "MIT / Apache-2.0",
        version = "—",
        detail = "随应用分发的数据文件（assets/sora/textmate）。来源包含 Visual Studio Code、" +
            "vscode-kotlin、vscode-logfile-highlighter 等上游项目，已核实的四项均为 MIT；" +
            "另有若干项尚未核到来源或许可。逐项清单在那份文件自带的 NOTICE.md 里。",
    ),
    LicenseEntry(
        name = "ZhiCode 自身",
        license = "MIT",
        version = "—",
        detail = "Compose 界面层、沙箱宿主层、引擎与工具集、诊断与安全边界。" +
            "第三方组件**不受**该许可约束；分发时必须同时提供 LICENSE、NOTICE 与 " +
            "THIRD-PARTY-LICENSES/ 三份文件。",
    ),
)

/** 本工程自身的许可（「关于」页那一行显示它）。 */
const val OWN_LICENSE = "MIT"

/** 项目主页。关于页的「查看源码」与许可页的「完整声明」都指向仓库而不是某次构建的产物。 */
const val PROJECT_URL = "https://github.com/iqisge-gif/IQ-Code-Android"
