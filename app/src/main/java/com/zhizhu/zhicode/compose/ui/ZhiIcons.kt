package com.zhizhu.zhicode.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.zhizhu.zhicode.compose.model.ToolKind

/**
 * 全局图标集：**语义名 → 字形**的唯一映射表。
 *
 * ## 来源：Material Symbols（Rounded, Fill1）
 *
 * 每个属性都指向 [ZhiMaterialIcons] 里的一个字形，那里面存的是
 * `google/material-design-icons` 上游 SVG 的路径数据（Apache-2.0）。
 * 为什么是这一套、为什么把数据拷进来而不是加依赖，写在
 * `ZhiMaterialIcons.kt` 的文件头 —— 那里是字形，这里是**语义**。
 *
 * ## 三轮反馈的落点
 *
 * 这一份映射表被用户打回过三次，每一次的结论都留在下面，因为
 * "为什么不能用某个看着很像的字形"是最容易在下次重构里丢掉的信息：
 *
 * ### 第一轮：原版 IQ Code 的图标不对
 *
 * 原版把"跑命令"挂成待办清单、把"运行环境"挂成对勾、失败与关闭共用一个字形。
 * 于是本表确立了两条规矩，至今仍是它存在的理由：
 *
 * 1. **一个字形只能属于一个语义**（`IconSetTest` 第 4 节逐对检查）；
 * 2. **名字要按"这一行要表达的意思"取，不按字形取**（所以有 `runtime` 而不是 `Wrench`）。
 *
 * ### 第二轮：`miuix-icons` 里"名字对、形状不对"
 *
 * 曾经整集换成 `top.yukonga.miuix.kmp.icon.MiuixIcons.*`。逐个读那份源码的
 * `PathNode` 之后发现三处：
 *
 * - `MiuixIcons.More` 是**竖排**三点（三个圆心的 x 全是 521），旧代码用
 *   `Modifier.rotate(90f)` 把它掰成横排 —— 属于用变换掩盖选错了字形；
 * - `MiuixIcons.ExpandMore` / `ExpandLess` 是「L 形框 + 圆点 + L 形框」，
 *   **不是箭头**（这就是用户说的"怪怪的框"）；
 * - `MiuixIcons.Theme` 是「横长圆角块 + 上方小块」的滚筒形状，不是明暗切换。
 *
 * 这三处当时只能自绘。现在它们在 Material Symbols 里都有直系字形
 * （`more_horiz` / `expand_more` / `contrast`），所以自绘文件整个删掉了。
 *
 * ### 第三轮：换成本集
 *
 * > 算了和，还是把扁平化（不是线条）和 rikka 等 md3 图标结合起来，沙盒的有点不太好看，MCP 也是
 *
 * - 「扁平化（不是线条）」：本集是**实心**字形（`fill1`）。曾经差点走错 ——
 *   rikkahub 实际用的 HugeIcons / Lucide **两套都是线条**，
 *   直接抄过来正好是用户明确排除的那一种。
 * - 「沙盒的有点不太好看」：`sandbox` 从自绘的线框箱子换成 `deployed_code`
 *   （圆角实心箱 + `</>`，也正是"能装代码的箱子"）。
 * - 「MCP 也是」：`mindMap` 从自绘的扳手形状换成 `hub`（中心节点 + 四向连线）。
 *
 * ## 为什么属性是 `@Composable get()`
 *
 * [rememberVectorPainter] 是 `@Composable` 的（它要 `remember` 住结果），
 * 所以取图标这件事天然只能发生在组合里。写成
 * `val menu: Painter @Composable get() = vector(ZhiMaterialIcons.Menu)` 之后：
 *
 * - 调用点是 `Icon(painter = ZhiIcons.menu, ...)`，取图标只发生在组合中 ——
 *   这正是我们想要的约束（图标不该在任何后台线程或纯逻辑里被解析）；
 * - 字形那一层与语义这一层经过同一个 [vector] 适配，调用点看不出区别，
 *   换整套字形时也只需要改这一个文件。
 *
 * ## 与 `Icon` 的配合
 *
 * `Icon` 的 `painter` 重载会给 painter 套一层 `ColorFilter.tint(tint)`，
 * 默认 `SrcIn` 混合 —— 也就是**整体替换**成 tint 颜色。所以 `ImageVector` 里
 * 写死的 `SolidColor(Color.Black)` 只是占位，换主题、换语义色都不需要改字形。
 */
object ZhiIcons {

    /**
     * [ImageVector] → `Painter` 的唯一通道。
     *
     * 字形那一层（[ZhiMaterialIcons]）与语义这一层都从这里过，
     * 于是"图标是怎么来的"在类型上只剩一个入口。
     */
    @Composable
    private fun vector(imageVector: ImageVector): Painter = rememberVectorPainter(imageVector)

    // ── 顶栏 ────────────────────────────────────────────────────────────────

    /** 打开侧栏（汉堡）。 */
    val menu: Painter @Composable get() = vector(ZhiMaterialIcons.Menu)

    /** 明暗主题：半明半暗的圆。 */
    val theme: Painter @Composable get() = vector(ZhiMaterialIcons.Contrast)

    /** 悬浮球：实心圆。与沙箱的箱子是两个不同的东西。 */
    val floatingBall: Painter @Composable get() = vector(ZhiMaterialIcons.Circle)

    val settings: Painter @Composable get() = vector(ZhiMaterialIcons.Settings)

    // ── 工作区页签 ──────────────────────────────────────────────────────────

    /** 对话：气泡。 */
    val chat: Painter @Composable get() = vector(ZhiMaterialIcons.ChatBubble)

    /**
     * 变更 / diff：一个方框，里面上 `+` 下 `−`。
     *
     * `difference` 正是这个形状。不要退回去用"文档 + 铅笔"那类：那一族读起来是
     * "编辑文件"，与"这一轮改了哪些文件"不是一件事。
     */
    val changes: Painter @Composable get() = vector(ZhiMaterialIcons.Difference)

    /** 终端：窗口 + 提示符 + 光标横杠。 */
    val terminal: Painter @Composable get() = vector(ZhiMaterialIcons.Terminal)

    /** 文件页签：**实心**文件夹。 */
    val files: Painter @Composable get() = vector(ZhiMaterialIcons.Folder)

    // ── 侧栏 ────────────────────────────────────────────────────────────────

    /** 新建会话：圆里的 `+`（与输入器那个裸 `+` 区分开）。 */
    val newSession: Painter @Composable get() = vector(ZhiMaterialIcons.AddCircle)

    /** 项目历史：时钟。 */
    val projectHistory: Painter @Composable get() = vector(ZhiMaterialIcons.History)

    /** 项目目录：房子（这是工作区的根）。 */
    val projectPath: Painter @Composable get() = vector(ZhiMaterialIcons.Home)

    /** 角色卡：人像。 */
    val roleCard: Painter @Composable get() = vector(ZhiMaterialIcons.Person)

    /**
     * 技能：拼图块（`extension` 就是拼图）。
     *
     * 旧代码拿待办清单顶过，于是"技能"和"跑命令"在界面上长得一模一样 ——
     * 这正是"一个字形只能属于一个语义"这条规矩要消灭的那类错误。
     */
    val skill: Painter @Composable get() = vector(ZhiMaterialIcons.Extension)

    /**
     * 沙箱：圆角实心箱 + `</>`（`deployed_code`）。
     *
     * ⚠️ 上一版是**自绘的线框箱子**，用户看过之后的原话是「沙盒的有点不太好看」。
     * 自绘曲线很难与整套实心字形同调，所以换成上游字形。
     * 备选是 `inventory_2`（纯箱子，没有代码标记）—— 如果觉得 `</>` 太抢眼可以换它。
     */
    val sandbox: Painter @Composable get() = vector(ZhiMaterialIcons.DeployedCode)

    /** 运行环境：扳手（侧栏那一行是"内置 Termux 环境就绪"）。 */
    val runtime: Painter @Composable get() = vector(ZhiMaterialIcons.Build)

    // ── 输入器 ──────────────────────────────────────────────────────────────

    /** 附件：裸 `+`。 */
    val attach: Painter @Composable get() = vector(ZhiMaterialIcons.Add)

    /** 发送：纸飞机。 */
    val send: Painter @Composable get() = vector(ZhiMaterialIcons.Send)

    /** 停止生成：实心圆角方块。与"暂停"（两条竖杠）不是一件事。 */
    val stop: Painter @Composable get() = vector(ZhiMaterialIcons.Stop)

    /** 向右的小箭头（"查看全部"这类跳转）。与 [expand] 同源，都是同一个 `›`。 */
    val arrowRight: Painter @Composable get() = vector(ZhiMaterialIcons.ChevronRight)

    // ── 通用 ────────────────────────────────────────────────────────────────

    val close: Painter @Composable get() = vector(ZhiMaterialIcons.Close)

    /** 编辑：铅笔。 */
    val edit: Painter @Composable get() = vector(ZhiMaterialIcons.Edit)

    /**
     * 折叠控制的一对：折叠态 `›`（点了展开）↔ 展开态 `⌄`（点了收起）。
     *
     * ⚠️ 这一对**只留给确实需要指示方向的表头**（思考过程、MCP、技能列表）。
     * 工具行与工具组表头**不**显示箭头：那一整行本身就是可点的展开/收起开关，
     * 再挂一个箭头等于用图标重复表达同一件事（用户的原话：「可以去掉」）。
     *
     * 上游没有 `chevron_down` 这个字形 —— 向下的箭头就叫 `expand_more`。
     * 两个方向都来自同一族，所以并排切换时笔画粗细与长短是一致的。
     */
    val expand: Painter @Composable get() = vector(ZhiMaterialIcons.ChevronRight)
    val collapse: Painter @Composable get() = vector(ZhiMaterialIcons.ExpandMore)

    val refresh: Painter @Composable get() = vector(ZhiMaterialIcons.Refresh)
    val info: Painter @Composable get() = vector(ZhiMaterialIcons.Info)

    /**
     * 清屏：橡皮擦（`ink_eraser`）。
     *
     * 这里先后换过两次：自绘的三条递减横杠 → Miuix 的 `Clear`（橡皮擦 + 底线）→
     * 本集的 `ink_eraser`。**橡皮擦**才是"擦掉"最直接的隐喻，三条横杠只是"列表"。
     */
    val clear: Painter @Composable get() = vector(ZhiMaterialIcons.InkEraser)

    /** 返回 / 返回上一级：左箭头。 */
    val back: Painter @Composable get() = vector(ZhiMaterialIcons.ArrowBack)

    /** 消息 / 工具行的「更多操作」：**横排**三点。 */
    val more: Painter @Composable get() = vector(ZhiMaterialIcons.MoreHoriz)

    /** 竖排三点（终端标题栏这类竖向排布的地方）。与 [more] 是不同的控件。 */
    val moreVert: Painter @Composable get() = vector(ZhiMaterialIcons.MoreVert)

    val search: Painter @Composable get() = vector(ZhiMaterialIcons.Search)

    /**
     * 「新建」：裸 `+`。
     *
     * 与 [attach] **共用同一个字形**（`Add`），但语义名分开。
     *
     * 为什么不去另找一个字形：`+` 就是"新建"这件事最短的表达，而小米文件管理器的
     * 那一行也只有一个 `+`（`res/layout/phone_file_explorer_list.xml` 的
     * `@id/action_create`）。换一个"文档 + 加号"之类的字形反而会让这一行更挤。
     *
     * 为什么名字要分开：`IconSetTest` 的两两撞脸检查是**按语义名**比字形的，
     * 而这里两者本来就该是同一个字形。真按字形判重的话，
     * 就会为了"通过检查"把它换成某个更差的形状 —— 那条检查的注释里
     * （`terminal` 与 `tool(COMMAND)`）已经写过这层意思：
     * **同一个字形可以用在两个语义上，只要它表达的确实是同一件事**。
     * 而"附件"与"新建"在界面上从不同屏出现（一个在输入器、一个在文件面板），
     * 不存在"让人分不清"的场景。
     */
    val add: Painter @Composable get() = vector(ZhiMaterialIcons.Add)

    /** 新建：带圆环的加号，和附件动作区分开。 */
    val newEntry: Painter @Composable get() = vector(ZhiMaterialIcons.AddCircle)

    /** 文件操作：复制。 */
    val copy: Painter @Composable get() = vector(ZhiMaterialIcons.ContentCopy)

    /** 文件操作：粘贴。 */
    val paste: Painter @Composable get() = vector(ZhiMaterialIcons.ContentPaste)

    /** 文件页网格显示。 */
    val grid: Painter @Composable get() = vector(ZhiMaterialIcons.GridView)

    /** 编辑器自动换行。 */
    val wrapText: Painter @Composable get() = vector(ZhiMaterialIcons.WrapText)

    /** 文件页列表显示。 */
    val list: Painter @Composable get() = vector(ZhiMaterialIcons.List)

    /** 完成：圆里的对勾。 */
    val done: Painter @Composable get() = vector(ZhiMaterialIcons.CheckCircle)

    /** 保存：软盘。 */
    val save: Painter @Composable get() = vector(ZhiMaterialIcons.Save)

    /** 失败：圆里的感叹号。与 [close] 不是同一个字形。 */
    val failed: Painter @Composable get() = vector(ZhiMaterialIcons.Error)

    /** 尚未开始 / 进行中：空心圆环（注意不是带点的圆）。 */
    val pending: Painter @Composable get() = vector(ZhiMaterialIcons.RadioButtonUnchecked)

    /** 等待授权：圆圈问号（需要你决定）。 */
    val awaiting: Painter @Composable get() = vector(ZhiMaterialIcons.Help)

    /** 选中标记：裸对勾（列表行的单选态）。 */
    val check: Painter @Composable get() = vector(ZhiMaterialIcons.Check)

    // ── 文件 ────────────────────────────────────────────────────────────────

    /** 目录：**打开的**文件夹，与页签上那个合口的 [files] 区分开。 */
    val directory: Painter @Composable get() = vector(ZhiMaterialIcons.FolderOpen)

    /** 文件：文档（右上角折角）。 */
    val file: Painter @Composable get() = vector(ZhiMaterialIcons.Description)

    val delete: Painter @Composable get() = vector(ZhiMaterialIcons.Delete)

    /** 图片附件：照片。曾经借过 [floatingBall]，那是"悬浮球"，与图片无关。 */
    val image: Painter @Composable get() = vector(ZhiMaterialIcons.Image)

    // ── 设置行（彩色圆角块里的白字形） ────────────────────────────────────────
    //
    // 设置页每一行的行首都是「彩色圆角方块 + 白字形」（底色与几何见
    // ui/settings/SettingsIconPlate.kt）。这一组是那十行专用的语义名。

    /** 搜索服务列表（入口行）：云。 */
    val cloud: Painter @Composable get() = vector(ZhiMaterialIcons.Cloud)

    /** 搜索结果条数（数值项）：列表。 */
    val listCount: Painter @Composable get() = vector(ZhiMaterialIcons.List)

    /** 联网超时（数值项）：计时器。 */
    val timeout: Painter @Composable get() = vector(ZhiMaterialIcons.Timer)

    /** API 配置（入口行）：链接（接口地址 + 密钥）。 */
    val link: Painter @Composable get() = vector(ZhiMaterialIcons.Link)

    /** 推理强度（选项项）：滑杆。 */
    val tune: Painter @Composable get() = vector(ZhiMaterialIcons.Tune)

    /** 权限模式（选项项）：锁。 */
    val lock: Painter @Composable get() = vector(ZhiMaterialIcons.Lock)

    /** 上下文压缩（开关）：向内的双向箭头。 */
    val compress: Painter @Composable get() = vector(ZhiMaterialIcons.Compress)

    /** 终端字符模式输入（开关）：键盘。 */
    val keyboard: Painter @Composable get() = vector(ZhiMaterialIcons.Keyboard)

    /**
     * MCP 服务器配置（入口行）：中心节点 + 四向连线（`hub`）。
     *
     * ⚠️ 上一版是自绘的"工具关系网"（`ZhiVectorIcons.Wrench` 那一族的形状），
     * 用户看过之后的原话是「MCP 也是」（不好看）。
     */
    val mindMap: Painter @Composable get() = vector(ZhiMaterialIcons.Hub)

    /** UI 调试页（入口行）：组件分层陈列。 */
    val layers: Painter @Composable get() = vector(ZhiMaterialIcons.Layers)

    // ── 工具类别的图标 ──────────────────────────────────────────────────────

    /**
     * 工具卡片图标，按工具类别映射。
     *
     * ⚠️ `COMMAND` 必须是**终端**：旧代码挂待办清单，
     * 于是"跑了一条命令"和"任务清单"在界面上是同一个图标。
     *
     * ⚠️ `OTHER` 用**代码括号**，不能用扳手 —— 扳手已经给了 [runtime]
     * （"运行环境"= 构建/环境），两个不同的东西共用一个字形正是要消灭的问题
     * （见 `IconSetTest` 的第 4 节：两两不许撞脸）。
     */
    @Composable
    fun tool(kind: ToolKind): Painter = when (kind) {
        ToolKind.SEARCH -> vector(ZhiMaterialIcons.Search)
        ToolKind.READ -> vector(ZhiMaterialIcons.Description)
        ToolKind.EDIT -> vector(ZhiMaterialIcons.Edit)
        ToolKind.COMMAND -> vector(ZhiMaterialIcons.Terminal)
        ToolKind.OTHER -> vector(ZhiMaterialIcons.Code)
    }

    /** 按工具名取图标（授权弹窗等只知道工具名的场景）。 */
    @Composable
    fun tool(toolName: String): Painter = when (toolName) {
        "Grep", "Glob", "Search" -> vector(ZhiMaterialIcons.Search)
        "Read", "ReadMany", "LS", "List", "Tree", "Stat" -> vector(ZhiMaterialIcons.Description)
        "Write", "Edit", "MultiEdit" -> vector(ZhiMaterialIcons.Edit)
        "Delete" -> vector(ZhiMaterialIcons.Delete)
        "Move" -> vector(ZhiMaterialIcons.DriveFileMove)
        "Bash", "Root", "Shizuku" -> vector(ZhiMaterialIcons.Terminal)
        else -> vector(ZhiMaterialIcons.Code)
    }
}
