package com.zhizhu.zhicode.compose.data

import com.zhizhu.zhicode.compose.model.SkillEntry
import com.zhizhu.zhicode.compose.model.SkillFile
import com.zhizhu.zhicode.compose.model.SkillFileNode
import com.zhizhu.zhicode.compose.model.SkillScope
import com.termux.shared.termux.TermuxConstants
import java.io.File

/**
 * Skill 的读写。
 *
 * ## 目录约定（必须与引擎一致）
 *
 * 引擎的 `SkillTool` 在这两个位置找 `SKILL.md`：
 * - 项目级：`<projectDirectory>/.zhicode/skills/<name>/SKILL.md`
 * - 用户级：`$HOME/.zhicode/skills/<name>/SKILL.md`
 *
 * 所以这里**不能**自己发明目录结构，否则界面上建好的技能 Agent 根本找不到
 * （`SkillTool` 会返回 `Skill not found: <name>`）。
 * 两边的根目录都取自 `TermuxConstants.dataDirIn`，是同一个来源。
 *
 * ## 命名约束
 *
 * 名字直接当目录名用，所以必须限制字符集：`SkillTool` 会拒绝含 `/` 或 `..` 的名字，
 * 界面这边更要在**创建时**就挡住——让非法名字连目录都建不出来，
 * 好过建完之后 Agent 拒绝加载（那种错误信息离用户很远）。
 */
internal object SkillStore {

    /** 名字规则与引擎侧一致：字母、数字、`.`、`_`、`-`，长度 1–64。 */
    private val NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    private const val FILE_NAME = "SKILL.md"
    private const val SKILLS_DIR = "skills"

    /**
     * 详情页文件树的深度上限与节点总数上限。
     *
     * 都不是"优化"：技能目录是用户（或 Agent）可写的，`walkTopDown()` 遇到目录符号
     * 链接会跟着走，一个循环链接就能让递归停不下来；而几千个文件一次性渲染会直接
     * 卡住详情页。宁可少列几层，也不能让界面卡死。
     */
    private const val TREE_MAX_DEPTH = 4
    private const val TREE_MAX_FILES = 400

    fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

    /**
     * 技能目录内**附加文件**的名字规则。
     *
     * 与技能名同一套字符集，但额外挡掉 `.` 与 `..` —— 正则里的 `.` 是字面点，
     * 所以 `..` 本身能通过 `matches`，必须单独拒绝。
     *
     * ⚠️ 这个函数**不是**安全边界：它只挡掉明显的写法。真正的兜底是
     * [fileInSkillDir] 里的 `canonicalPath` 前缀比对 —— 名字是由界面文本直接驱动的，
     * 只靠字符集校验的代码在遇到新写法时很容易失效。
     */
    fun isValidFileName(name: String): Boolean =
        NAME_PATTERN.matches(name) && name != "." && name != ".."

    /**
     * 解析技能目录里的一个文件，并确认它**没有跑出技能目录**。
     *
     * 两道防线：
     * 1. [isValidFileName] 挡掉 `/`、`\`、`.`、`..` 这些明显写法；
     * 2. `canonicalPath` 前缀比对 —— 防的是第 1 步被绕过，以及符号链接把路径引出目录。
     *    这一步是安全边界，不能删。
     */
    private fun fileInSkillDir(dir: File, fileName: String): File {
        require(isValidFileName(fileName)) { "文件名只能包含字母、数字、. _ -（1–64 个字符）" }
        val target = File(dir, fileName)
        val root = dir.canonicalPath + File.separator
        require(target.canonicalPath.startsWith(root)) { "文件名不合法：$fileName" }
        return target
    }

    /**
     * 解析技能目录里的一个**相对路径**（允许 `sub/doc.md` 这种子目录）。
     *
     * 关键点：**逐段**过 [fileInSkillDir]，不是把整串 `sub/doc.md` 直接 resolve。
     * 直接 resolve 的话 `../x.md` 会绕过"单段名字"那道校验（它只在整串里找一个
     * `..` 是分不开的），于是能写到技能目录外面去。逐段校验则每一段都必须是一个
     * 普通名字，且每一段都重新做一次 canonicalPath 前缀比对。
     */
    private fun fileInSkillDirByPath(dir: File, relativePath: String): File {
        require(relativePath.isNotBlank()) { "文件名不能为空" }
        var target = dir
        relativePath.split('/').forEach { segment ->
            require(segment.isNotEmpty()) { "路径不合法：$relativePath" }
            target = fileInSkillDir(target, segment)
        }
        return target
    }

    /** 某个作用域的技能根目录。 */
    private fun scopeDir(projectPath: String, scope: SkillScope): File = when (scope) {
        SkillScope.PROJECT -> File(projectPath)
        SkillScope.USER -> File(TermuxConstants.TERMUX_HOME_DIR_PATH)
    }

    /** 写入目标根目录。新建、保存、界面显示的路径都以它为准。 */
    fun root(projectPath: String, scope: SkillScope): File =
        File(TermuxConstants.dataDirIn(scopeDir(projectPath, scope)), SKILLS_DIR)

    fun directoryOf(projectPath: String, scope: SkillScope, name: String): File =
        File(root(projectPath, scope), name)

    fun fileOf(projectPath: String, scope: SkillScope, name: String): File =
        File(directoryOf(projectPath, scope, name), FILE_NAME)

    /**
     * 列出所有技能。
     *
     * 项目级与用户级都会扫；两层里同名时两份都保留并标明作用域
     * ——用户可能故意在项目里放一个覆盖版本，悄悄隐藏用户级那一条会让人以为它被删了。
     *
     * ⚠️ **但两个根目录是同一个目录时只列一次**。默认项目目录就是 `$HOME`
     * （用户级技能也在 `$HOME/.zhicode/skills`），这时两层指向同一个文件夹，
     * 不去重的话同一个技能会被列成两条（「项目级」+「用户级」），
     * 而磁盘上只有一个文件 —— 删除其中一条会把另一条也一起弄没。
     * 这是实际在界面上观察到的现象，不是理论问题。
     */
    fun list(projectPath: String): List<SkillEntry> {
        val out = mutableListOf<SkillEntry>()
        // 按**规范路径**去重（不是名字）：软链接指到同一个目录也算同一个。
        val seen = mutableSetOf<String>()
        SkillScope.entries.forEach { scope ->
            val rootDir = root(projectPath, scope)
            val children = rootDir.listFiles() ?: return@forEach
            children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { dir ->
                if (!seen.add(dir.canonicalPath)) return@forEach
                // 走同一个解析点（而不是 File(dir, FILE_NAME)）：路径解析只留一处，
                // 以后要改规则时不会漏掉这条。这里的 dir 来自 listFiles，
                // 但"相信上一个函数的输出"正是这类漏洞的来源。
                val file = fileInSkillDir(dir, FILE_NAME)
                if (!file.isFile) return@forEach
                out += SkillEntry(
                    name = dir.name,
                    scope = scope,
                    path = file.absolutePath,
                    summary = describe(file),
                    sizeLabel = humanSize(file.length()),
                )
            }
        }
        return out
    }

    /**
     * 取 SKILL.md 的 `description` 作为列表副标题。
     *
     * 只读文件开头若干行：技能文件可能很长，为了列表显示把整个文件读进来不值得。
     * 读不到就明确说"未写说明"，而不是显示空串让人以为是加载失败。
     */
    private fun describe(file: File): String = runCatching {
        val head = file.bufferedReader().use { reader ->
            val lines = mutableListOf<String>()
            while (lines.size < 40) {
                val line = reader.readLine() ?: break
                lines += line
            }
            lines.joinToString("\n")
        }
        // 与 nameFromContent 同一条规则：只在 frontmatter 块里找。
        // 全文扫描会把正文里的 `description:` 当成元数据。
        val description = frontMatterBlock(head)?.let { bareValue(frontMatterValue(it, "description").orEmpty()) }
        description?.takeIf { it.isNotBlank() } ?: "未写说明"
    }.getOrElse { "无法读取：${it.message ?: "未知原因"}" }

    /**
     * 从 YAML 前置元数据里取一个字段。
     *
     * 只做最朴素的 `key: value` 匹配，不引 YAML 解析器：技能头部就那两三行，
     * 而且用户手写的内容各种不规范，严格解析反而更容易整个失败。
     */
    private fun frontMatterValue(text: String, key: String): String? {
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("$key:")) return trimmed.removePrefix("$key:").trim()
        }
        return null
    }

    /**
     * 取 YAML 前置元数据块（首个 `---` 行到下一个 `---` 行之间）。
     *
     * **只在这个块里找键**，不全文扫描。正文里出现 `name: xxx` 是很常见的
     * （代码示例、说明文字都会），全文扫描会把那种行当成技能名，
     * 于是目录名和内容对不上，而用户完全看不出为什么。
     */
    private fun frontMatterBlock(content: String): String? {
        val lines = content.lineSequence().toList()
        val start = lines.indexOfFirst { it.trim() == "---" }
        if (start < 0) return null
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.trim() == "---" }
        if (end < 0) return null
        return rest.take(end).joinToString("\n")
    }

    /** 去掉 YAML 里数值两侧的引号：`name: "my-skill"` 与 `name: my-skill` 等价。 */
    private fun bareValue(raw: String): String {
        val text = raw.trim()
        if (text.length >= 2) {
            val first = text.first()
            if ((first == '"' || first == '\'') && text.last() == first) {
                return text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    /**
     * 从**粘贴进来的 SKILL.md 内容**里解析技能名。
     *
     * 这是「手动添加」的入口：用户直接粘一整份 SKILL.md，名字由内容的
     * frontmatter 决定，不再单独问一遍 —— 与参考实现（rikkahub 的添加技能对话框）一致。
     *
     * 解析不出来返回 null，由调用方**报错并禁用提交**，而不是回退到某个默认名：
     * 拿不到名字时猜一个（比如取文件名、取第一行）会建出一个用户没打算建的目录。
     *
     * 引擎侧（`SkillTool`）并不解析 frontmatter，它只是把整份文件当文本加载 ——
     * 所以这个解析只服务于"目录叫什么"，不影响 Agent 能不能用这个技能。
     */
    fun nameFromContent(content: String): String? =
        frontMatterBlock(content)?.let { bareValue(frontMatterValue(it, "name").orEmpty()) }
            ?.takeIf { it.isNotBlank() }

    /**
     * 技能目录里的文件**树**（详情页用）。
     *
     * 为什么是树而不是平铺一层：技能可以带子目录 —— 参考文档、脚本、示例按目录分组
     * 是很常见的分发形态（参考实现的详情页就是递归树）。平铺会把 `reference/api.md`
     * 显示成 `api.md`，用户看不出它在哪一层，也没法在界面上重建同样的结构。
     *
     * 排序：目录在前、文件在后；文件里 `SKILL.md` 排最前 —— 它是技能本体，
     * 按字母序混排会让本体淹没在附件里。
     *
     * 深度与文件数都有上限，见 [TREE_MAX_DEPTH] / [TREE_MAX_FILES]。
     */
    fun listTree(projectPath: String, scope: SkillScope, skillName: String): List<SkillFileNode> {
        val dir = directoryOf(projectPath, scope, skillName)
        if (!dir.isDirectory) return emptyList()
        return buildNodes(dir, prefix = "", depth = 0, seen = intArrayOf(0))
    }

    /**
     * 递归建树。
     *
     * ⚠️ 两个上限都不是"优化"，是**必要条件**：
     * - 不用 `walkTopDown()`：它会跟着目录符号链接走，一个循环链接就能让界面
     *   无限递归（而且是在主线程上）。这里只走真实目录，且限制深度。
     * - `seen` 统计已列出的节点数，到顶就停 —— 技能目录是用户可写的，
     *   往里面扔几千个文件是完全可能的，一次性全部渲染会直接卡住详情页。
     */
    private fun buildNodes(dir: File, prefix: String, depth: Int, seen: IntArray): List<SkillFileNode> {
        val children = dir.listFiles().orEmpty()
        val dirs = mutableListOf<SkillFileNode>()
        val files = mutableListOf<SkillFile>()
        children.forEach { child ->
            if (seen[0] >= TREE_MAX_FILES) return@forEach
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            when {
                child.isDirectory && depth < TREE_MAX_DEPTH -> {
                    seen[0]++
                    dirs += SkillFileNode.DirNode(
                        name = child.name,
                        relativePath = relative,
                        children = buildNodes(child, relative, depth + 1, seen),
                    )
                }

                child.isFile -> {
                    seen[0]++
                    files += SkillFile(
                        name = child.name,
                        relativePath = relative,
                        sizeLabel = humanSize(child.length()),
                        primary = relative == FILE_NAME,
                    )
                }
                // 目录但已到深度上限：整棵子树不再展开（列出来也只会被截断成空目录）
            }
        }
        val sortedFiles = files.sortedWith(compareBy({ !it.primary }, { it.name.lowercase() }))
        return dirs + sortedFiles.map { SkillFileNode.FileNode(it) }
    }

    /**
     * 检查 `SKILL.md` 里声明的 `name` 与**目录名**是否一致。
     *
     * 一致返回 null，否则返回可以原样展示给用户的原因。
     *
     * 为什么必须有这道校验：技能目录名才是引擎 `SkillTool` 用来定位技能的键
     * （`skills/<name>/SKILL.md`），而内容里的 `name` 只是文本。改成不一致之后，
     * 界面上技能叫 A、内容说自己是 B —— Agent 按名字加载时找一个不存在的目录，
     * 而用户完全看不出为什么。参考实现（rikkahub 的 `SkillDetailVM.saveFile`）
     * 也是这么挡的：`name != skillName` 直接拒绝保存。
     */
    fun nameConflict(skillName: String, content: String): String? = when (val declared = nameFromContent(content)) {
        null -> "SKILL.md 的 frontmatter 里必须有一行 name: $skillName"
        skillName -> null
        else -> "name 必须是「$skillName」，现在写的是「$declared」；要改名请新建技能"
    }

    /**
     * 列出技能目录里的文件（扁平，含子目录里的文件）。
     *
     * 界面上用的是 [listTree]；这里是给"附加整个技能"这类需要全部文件的调用方准备的。
     */
    fun listFiles(projectPath: String, scope: SkillScope, skillName: String): List<SkillFile> =
        flatten(listTree(projectPath, scope, skillName))

    /** 把树摊平成文件列表（深度优先，保持树里的顺序）。 */
    fun flatten(nodes: List<SkillFileNode>): List<SkillFile> = nodes.flatMap { node ->
        when (node) {
            is SkillFileNode.FileNode -> listOf(node.file)
            is SkillFileNode.DirNode -> flatten(node.children)
        }
    }

    /** 读技能目录里的一个文件（[relativePath] 可以是 `sub/doc.md`）。 */
    fun readFile(projectPath: String, scope: SkillScope, skillName: String, relativePath: String): Result<String> =
        runCatching {
            val file = fileInSkillDirByPath(directoryOf(projectPath, scope, skillName), relativePath)
            if (!file.isFile) throw IllegalArgumentException("找不到文件：$relativePath")
            file.readText()
        }

    /**
     * 写技能目录里的一个文件（不存在则创建，子目录会自动建）。
     *
     * 路径先过 [fileInSkillDirByPath] 的两道校验 —— 这条路径是**由界面输入框直接驱动**的，
     * 没有校验的话 `../` 能写到技能目录外面去。
     *
     * 写 `SKILL.md` 时额外过 [nameConflict]：内容里的 `name` 必须还是这个技能的名字。
     */
    fun writeFile(
        projectPath: String,
        scope: SkillScope,
        skillName: String,
        relativePath: String,
        body: String,
    ): Result<Unit> = runCatching {
        val dir = directoryOf(projectPath, scope, skillName)
        if (!dir.isDirectory && !dir.mkdirs()) throw IllegalStateException("无法创建目录：${dir.absolutePath}")
        if (relativePath == FILE_NAME) {
            nameConflict(skillName, body)?.let { throw IllegalArgumentException(it) }
        }
        val target = fileInSkillDirByPath(dir, relativePath)
        target.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IllegalStateException("无法创建目录：${it.absolutePath}") }
        target.writeText(body)
        Unit
    }

    /**
     * 删除技能目录里的一个文件。
     *
     * `SKILL.md` **不能**用这个入口删：它是技能本体，删掉目录还在，列表里就会出现
     * 一个"看得见但打不开"的技能（`SkillStore.list` 会跳过没有 SKILL.md 的目录，
     * 于是那个目录变成幽灵）。要删就走 [delete] 整个技能一起删。
     */
    fun deleteFile(projectPath: String, scope: SkillScope, skillName: String, relativePath: String): Result<Unit> =
        runCatching {
            require(relativePath != FILE_NAME) { "SKILL.md 是技能本体，不能单独删除；要删请删整个技能" }
            val target = fileInSkillDirByPath(directoryOf(projectPath, scope, skillName), relativePath)
            if (!target.isFile) throw IllegalArgumentException("找不到文件：$relativePath")
            if (!target.delete()) throw IllegalStateException("无法删除：$relativePath")
            Unit
        }

    /** 技能目录里是否已有该文件（新建文件对话框用它挡重名）。 */
    fun fileExists(projectPath: String, scope: SkillScope, skillName: String, relativePath: String): Boolean =
        runCatching { fileInSkillDirByPath(directoryOf(projectPath, scope, skillName), relativePath).isFile }
            .getOrDefault(false)

    fun read(projectPath: String, scope: SkillScope, name: String): Result<String> =
        readFile(projectPath, scope, name, FILE_NAME)

    /**
     * 新建技能。
     *
     * [content] 为 null 时写一份空模板；「手动添加」会把自己粘贴的整份 SKILL.md 传进来。
     * 注意 [name] **不由这里从内容里推断**：目录名必须与用户在界面上确认过的名字一致
     * （界面已经用 [nameFromContent] 解析并在提交前给了用户反馈）。
     *
     * 若目录已存在**不覆盖**：用户可能已经写了内容，静默覆盖等于丢数据。
     * 返回 true 表示"新建了文件"，false 表示"已存在，直接打开编辑"。
     */
    fun create(
        projectPath: String,
        scope: SkillScope,
        name: String,
        content: String? = null,
    ): Result<Boolean> = runCatching {
        require(isValidName(name)) { "名称只能包含字母、数字、. _ -（1–64 个字符）" }
        // 带内容建目录时也过一遍名字一致性：界面那边名字是由内容解析出来的，
        // 正常情况下必然一致；但"两个来源恰好一致"不该是**约定**，而是**校验过的**。
        if (content != null) nameConflict(name, content)?.let { throw IllegalArgumentException(it) }
        val dir = directoryOf(projectPath, scope, name)
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("无法创建目录：${dir.absolutePath}")
        if (!dir.isDirectory) throw IllegalStateException("同名路径已存在且不是目录：${dir.absolutePath}")
        val file = fileInSkillDir(dir, FILE_NAME)
        if (file.exists()) return@runCatching false
        file.writeText(content ?: template(name))
        true
    }

    /**
     * 删除技能。
     *
     * 用递归删除：技能目录里可能还有参考文档等附加文件，
     * 只删 `SKILL.md` 会留下一个空壳目录，列表里看起来像"技能还在但打不开"。
     */
    fun delete(projectPath: String, scope: SkillScope, name: String): Result<Unit> = runCatching {
        val dir = directoryOf(projectPath, scope, name)
        if (!dir.isDirectory) throw IllegalArgumentException("找不到技能目录：${dir.absolutePath}")
        if (!dir.deleteRecursively()) throw IllegalStateException("无法删除：${dir.absolutePath}")
        Unit
    }

    /** 新技能的起始内容。带上前置元数据，用户照着填即可。 */
    private fun template(name: String): String = """
        |---
        |name: $name
        |description: 请描述这个 Skill 的用途
        |---
        |
        |# $name
        |
        |在这里编写给 ZhiCode 的技能说明。
        |
    """.trimMargin()

    /**
     * 「手动添加」对话框里预填的**示例**内容。
     *
     * 直接进输入框（而不是在框下面另起一块示例），用户改一改就能存：
     * 手上有的是"某个技能的说明文字"，未必知道 frontmatter 那两行是必须的，
     * 把必需形状摆在光标底下比任何提示都短。
     *
     * ⚠️ 名字刻意是个**占位符**（含 `<>` 与中文，过不了 [isValidName]）：
     * 这样一打开对话框时「保存」是**禁用**的，用户必须先把它换成自己的名字 ——
     * 若这里填一个合法默认名，点两下就会建出一个用户没打算建的技能。
     */
    val starterTemplate: String = """
        |---
        |name: <技能名，只能用字母、数字、. _ ->
        |description: 一句话说明这个技能在什么时候该用
        |---
        |
        |# <技能名>
        |
        |写给 ZhiCode 的说明：什么情况下用它、要按什么步骤做、有哪些注意事项。
        |
    """.trimMargin()

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
