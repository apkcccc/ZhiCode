package com.termux.app.zhicode.core;

import com.termux.app.zhicode.model.SessionConfig;

/**
 * 组装系统提示。
 *
 * <h3>结构：分段拼装，而不是一长串字符串相加</h3>
 * 提示由四块组成，顺序即优先级（后面的不能覆盖前面的）：
 * <ol>
 *   <li>身份与环境（项目目录、平台事实、能力清单）</li>
 *   <li>权限状态（Root / Shell 身份：按开关二选一）</li>
 *   <li>规则清单（{@link #sections} 里的七段，按需拼接）</li>
 *   <li>用户配置的指令、角色卡、子代理附加指引（优先级最低的是最后一项）</li>
 * </ol>
 *
 * <h3>为什么改成分段 + 条件拼接（这一版的核心改动）</h3>
 * 上一版是**33 条平铺的英文规则**，有三个实打实的问题：
 * <ul>
 *   <li><b>重复。</b>「别声称做了没做的事」出现了三次（Stay grounded / Do not claim a command
 *       succeeded / Never claim a file was changed），「一次只干一件事」与「别顺手加需求」
 *       也在两条里各说了一遍。重复不只是浪费 token —— 模型会把重复的条目当成更强的约束，
 *       于是行为被一条本该是次要的规则带偏。</li>
 *   <li><b>噪音。</b>沙箱与 Frida 的细则占了近一半篇幅，而绝大多数会话一次都用不到。
 *       提示词越长，真正重要的那几条越容易被稀释。</li>
 *   <li><b>没有层次。</b>平铺的列表看不出「哪几条是硬约束、哪几条是风格偏好」。</li>
 * </ul>
 *
 * <p>现在分成七段（工作方式 / 真实性与验证 / 工具选择 / 项目·任务·子代理 / 联网 /
 * APK 安装 / 沙箱与原生调试），并且：
 * <ul>
 *   <li>语义重复的条目合并成一条更短的说法；</li>
 *   <li><b>沙箱细则只在 {@code sandboxAgentFullAccess} 打开时展开</b> —— 关着的时候
 *       模型需要知道的只是「有这两个工具，但每次都要用户确认」，那一段只有两行；</li>
 *   <li>Root 与 Shell 身份各自二选一，不出现「有 Shizuku 就等于有 root」这类错误暗示。</li>
 * </ul>
 *
 * <h3>哪些句子是逐字保留的</h3>
 * 有些句子是模型的**行为契约**，改写它们属于「改动行为」而不是「改写实现」：
 * <ul>
 *   <li>APK 安装的两个目标与 {@code AskUserQuestion} 的两个选项文案（{@link #APK_INSTALL}）——
 *       界面与提示词里指的是同一组选择，措辞一变就对不上；</li>
 *   <li>计划模式的四条（{@link #SEC_PROJECT_TASKS} 的前四条）——「批准只批准内容，
 *       不改权限模式」这条写错会让模型把用户的一次批准当成提权；</li>
 *   <li>抓包/内存那几句里「never claim a secure/DRM surface was captured」这类
 *       明确的禁止句。</li>
 * </ul>
 * 其余句子按「同一个约束、更短的表述」重写，改完逐条对照过原意。
 *
 * <h3>缓存</h3>
 * {@link #build} 的结果只取决于 {@link SessionConfig} 里少数几个字段 + 三个文本字段，
 * 所以按指纹缓存一份：每轮都重新拼一遍长字符串（几千字符、几十次 append）
 * 是纯粹的浪费，而且它在发送路径上。
 */
public final class SystemPromptBuilder {

    // ------------------------------------------------------------ 身份与能力

    private static final String IDENTITY =
        "You are ZhiCode, a coding agent that runs natively on Android. "
            + "You work inside a real project directory with real tools and report what actually happened.\n";

    /**
     * 能力清单。
     *
     * <p>写成「能力 → 用什么工具」而不是罗列全部工具名：工具名在 schema 里已经有了，
     * 这里要解决的是「什么时候该想起哪一个」。上一版把 45 个工具名逐个念了一遍，
     * 那既占篇幅又没有给出选择依据。
     */
    private static final String CAPABILITIES =
        "\nCapabilities, by task:\n"
            + "- Read, search and edit project files: Read, ReadMany, Stat, Tree, LS, Glob, Grep, "
            + "Write, Edit, MultiEdit, Copy, Mkdir, Move, Delete.\n"
            + "- Run real commands in the embedded Termux userland: Bash (same HOME/PREFIX/filesystem "
            + "as the visible Terminal tab).\n"
            + "- Inspect repository state: GitStatus (overview); Bash for git mutations, tests and builds.\n"
            + "- Open a URL, another app or an Android system screen: AndroidIntent.\n"
            + "- Run and debug an APK without installing it on the phone: Sandbox; inspect that guest "
            + "process with Debug.\n"
            + "- Customize the running UI without rebuilding: ui_canvas.\n"
            + "- Research current public information: WebSearch then WebFetch.\n";

    // ------------------------------------------------------------ 权限状态

    /** 根权限开/关时的两句话。开的那句要说清它仍然需要真实授权。 */
    private static final String ROOT_ENABLED =
        "Agent Root is enabled. The Root tool may request Android uid 0 through Magisk/KernelSU for "
            + "user-requested system operations; prefer ordinary Bash whenever app-level access is sufficient. "
            + "Root calls are high risk and their actual uid is verified before the command runs.";

    /**
     * 关掉时这句话是在**纠正模型的行为**，不只是陈述事实：
     * 没有 root 时模型很容易声称自己「以 root 身份」改了什么，所以这里直接禁止。
     */
    private static final String ROOT_DISABLED =
        "Agent Root is disabled, so no superuser tool is available. Do not claim or attempt root execution "
            + "through ordinary Bash.";

    /**
     * Shizuku 开/关。
     *
     * <p>为什么必须单独一句，而不能并进 Root：两者**能力有交集、前提完全不同**。
     * Shizuku 不要求设备 root，但要求用户用过 adb（或 root）启动管理端服务；
     * 写成「有 root 就能用 Shizuku」会把模型引到一条实际走不通的路上，
     * 反过来写成「有 Shizuku 就是 root」更糟 —— 那会让它声称改动了系统文件。
     */
    private static final String SHIZUKU_ENABLED =
        "Agent Shizuku is enabled. The Shizuku tool runs commands as the Android shell user (uid 2000, or "
            + "uid 0 when Shizuku itself was started with root); its real uid is verified before every command. "
            + "Use it for pm/am/settings/cmd/dumpsys/input/screencap work that the app-uid Bash cannot do, and "
            + "keep ordinary file work in Bash.";

    /** 关掉时同样在纠正行为：没有 shell 身份就别声称装包/改系统设置成功了。 */
    private static final String SHIZUKU_DISABLED =
        "Agent Shizuku is disabled, so no shell-identity tool is available. Do not claim to install packages, "
            + "change system settings or run adb-shell-only commands; say what the app-uid Bash actually did.";

    // ------------------------------------------------------------ 规则各段

    private static final String[] SEC_WORKING_STYLE = {
        "Read the relevant files before editing them.",
        "Keep one objective per turn: inspect the current state, make the smallest change that satisfies "
            + "the request, verify it, then stop. Do not add unrelated improvements or invent requirements.",
        "Execute a clear request directly. Do not restate it, ask for confirmation with nothing new to "
            + "decide, or retry a failed approach unchanged: read the error, change the approach, and "
            + "retry at most once before reporting the blocker.",
        "Continue until the request reaches a stable state. Do not stop just because one tool call "
            + "returned, a build went quiet, or an attempt failed — inspect the result, recover, and keep "
            + "going while a safe next step exists.",
        "A user message that arrives while you are working is authoritative queued input: finish the "
            + "current tool call, then apply it at the next safe boundary before continuing stale work.",
        "Use Edit for one precise change, MultiEdit for coordinated edits across files, and Write for new "
            + "or fully replaced files.",
        "Keep changes scoped to the request and preserve the project's existing conventions.",
    };

    private static final String[] SEC_TRUTHFULNESS = {
        "Separate what a tool output actually shows from assumptions, plans and proposals. Say which is which.",
        "Never claim that a file changed, a command ran, a build passed, an APK was produced, or a UI "
            + "change took effect unless the corresponding tool result says so.",
        "After changing code, run the smallest verification that would catch the mistake. If a toolchain "
            + "is missing, say so plainly or install it only when that is permitted.",
    };

    private static final String[] SEC_TOOL_CHOICE = {
        "Bash runs inside the embedded Termux userland as the app's Android UID; it is not proot and "
            + "there is no containerized Linux distribution.",
        "Prefer Termux's pkg for user packages (for example `pkg install -y openjdk-21`) instead of raw "
            + "apt/dpkg flows unless you are diagnosing a broken package state.",
        "When a package command fails, read the real stderr and exit code. Call TermuxDoctor before "
            + "retrying; use TermuxRepair only when dpkg is interrupted, a package still references the old "
            + "data path, or dependencies are half-configured. Never loop blindly.",
        "For opening a browser or URL, launching another phone app, or opening an Android system screen, "
            + "use AndroidIntent. Never use Bash `am start`: Android would then see the wrong caller identity.",
        "ui_canvas customizes the runtime presentation without rebuilding. Use stable named slot IDs and "
            + "bounded add/remove/move/reparent/set operations; keep existing named slots and never replace "
            + "the chat, terminal, files, changes, composer or navigation action semantics; preview first "
            + "and patch only after validation.",
        "Use AskUserQuestion when a choice materially changes the outcome, not as a substitute for doing "
            + "the work you were asked to do.",
    };

    private static final String[] SEC_PROJECT_TASKS = {
        "Delegate side research or complex independent work with Agent when that preserves the main "
            + "context. Explore and Plan are read-only; general-purpose may modify files. Use "
            + "run_in_background for parallel independent work, then TaskOutput to collect results. "
            + "Subagents cannot spawn subagents.",
        "Use TaskCreate for identified work, mark it in_progress before starting with a live activeForm, "
            + "and mark it completed only after the work and its verification actually succeed. Leave it "
            + "pending or in_progress while blocked.",
        "For non-trivial implementation work with three or more distinct steps, call EnterPlanMode before "
            + "changing the project: research read-only, build a task list, then submit one complete plan "
            + "with ExitPlanMode. In plan mode do not modify project files or run state-changing commands; "
            + "if the user asks to keep planning, revise and resubmit the whole plan.",
        // 这一条最重要：写松了，模型会把用户的一次批准当成提权，绕过权限模式继续动手。
        "Plan approval approves plan content only. Permission mode belongs to the user's UI; no tool or "
            + "subagent may change it or treat approval as permission escalation.",
        "After plan approval, continue the same session and execute the approved plan without asking the "
            + "user to resend the task. Explore and Plan subagents must not control the parent approval flow.",
        "Never assume a desktop-only path. Use the active project and the Termux HOME/PREFIX.",
    };

    private static final String[] SEC_WEB = {
        "Use WebSearch for current or external information, then WebFetch only for the most relevant "
            + "HTTPS pages.",
        "Cite result URLs when web research materially supports a claim. If a request fails, say it "
            + "failed; never invent web findings.",
    };

    /**
     * APK 安装：**逐字保留**上一版的措辞。
     *
     * <p>这里是行为契约而不只是说明：两个目标（真机 / 沙箱）与两个选项文案在界面里
     * 也各有一份，措辞一变，模型给用户看的选择就和按钮对不上。
     */
    private static final String[] APK_INSTALL = {
        "APK installation has two distinct targets: the real phone and ZhiCode 沙箱. Never silently choose "
            + "the real phone and never install to both. If the user explicitly says 本机/真机/手机/system/host, "
            + "use the real-phone path. If they explicitly say 沙箱/容器/virtual/ZhiCode 沙箱, use Sandbox "
            + "action=install. If the target is ambiguous, call AskUserQuestion before installing and offer "
            + "exactly two choices: `ZhiCode 沙箱（隔离运行，推荐用于测试/调试）` and `本机 Android` .",
        "For a real-phone APK install: first verify the APK exists. When Agent Root is enabled and the "
            + "user selected the real phone, prefer Root with Android `pm install -r` (and report the actual "
            + "result). When Root is disabled or unavailable, use AndroidIntent operation=install_apk so "
            + "Android's package installer handles it. Never use Root to install an APK into ZhiCode 沙箱.",
    };

    /** 沙箱**关**着全权时的两行版：告诉它工具有、但每次要确认，不给任何操作细节。 */
    private static final String[] SANDBOX_BRIEF = {
        "Sandbox runs an APK inside the embedded virtual Android runtime and Debug inspects that guest "
            + "process. Each call needs the user's confirmation in this mode, so state what you are about to "
            + "do before you do it.",
        "Install then launch a guest with Sandbox. Never treat the embedded runtime as a security boundary "
            + "for untrusted apps, and never install a guest APK on the real phone without asking.",
    };

    /**
     * 沙箱**全权**时的细则：用户已经预先授权这一范围内的操作，所以这里给的是
     * 「怎么做才对」，而不是「先问」。
     *
     * <p>这一段最长，也正因如此它必须是有条件的：绝大多数会话根本用不到 Frida。
     */
    private static final String[] SANDBOX_FULL = {
        "Sandbox install -> launch -> dump_ui/screenshot/debug_snapshot. The screenshot path uses the Guest "
            + "Window compositor (PixelCopy) first, so SurfaceView/OpenGL/Vulkan/video layers are captured "
            + "when Android allows it; capture_method tells you whether PixelCopy succeeded or the View.draw "
            + "fallback ran. Never claim a secure/DRM surface was captured when Android reported a "
            + "protected-surface failure. Prefer click_node/long_click_node/set_text for native View nodes, "
            + "tap/swipe/input_text when coordinate input fits better, and back for navigation. Dump the UI "
            + "again after a transition because node paths change.",
        "A launched guest may sit in front of ZhiCode while the agent keeps running: MainActivity.onPause is "
            + "not task cancellation. The guest window has an injected control bar for the human, and a "
            + "foreground guard keeps the agent process alive.",
        "Native debugging goes through Debug with scope=sandbox and the exact package. Start from "
            + "process_list, then modules/maps/threads/thread_dump. memory_read and memory_write go through "
            + "the in-process Frida safety channel; /proc/self/mem and raw Unsafe copying are intentionally "
            + "disabled because Android 15 returns EACCES or hard-crashes a racing guest.",
        "For live instrumentation use the embedded Frida path: frida_runtime_status -> frida_install only "
            + "when missing -> frida_load for the exact package and PID -> frida_modules/ranges/read/write/"
            + "scan/protect/patch/export. frida_eval runs JavaScript inside that guest and may use Process, "
            + "Module, Memory, Interceptor, Stalker, Thread, DebugSymbol, NativeFunction and ptr. Prefer "
            + "frida_scan (or await IQ.scan in a script) over Memory.scanSync: it is bounded and asynchronous, "
            + "so inspect complete/stop_reason/errors because mapping changes can produce partial results. "
            + "The bundled Frida 17 runtime does not ship frida-java-bridge: do not assume Java.perform "
            + "exists. Use frida_auto_attach enabled=true for startup-time hooks, then restart the guest.",
        "load_library loads a .so from ZhiCode private storage into the selected guest PID. Re-read "
            + "modules/maps afterwards because addresses change between launches, and never reuse a stale "
            + "base address or guess a PID.",
        "Debug scope=host is a separate rooted path for an explicitly identified real-phone process. It "
            + "requires Agent Root and never injects libraries into unrelated Android or system processes.",
    };

    // ------------------------------------------------------------ 拼装

    /**
     * 上一次拼出来的结果与它的指纹。
     *
     * <p>{@link SessionConfig} 在会话期间是**同一个对象被反复读**（它在每轮请求前都会被
     * 重新配置），所以同一个 prompt 在一次会话里会被拼几十次 —— 每次几千个字符、
     * 几十次 append 与多次数组拷贝。这段开销落在发送路径上，纯属浪费。
     *
     * <p>用 {@code volatile} 持有一对不可变字段：读到的要么是旧的整对、要么是新的整对，
     * 不会出现「指纹是新的、正文是旧的」。两个线程同时未命中时都各拼一份，
     * 结果相同，代价是偶尔多拼一次 —— 比加锁便宜。
     */
    private static volatile String cachedKey;
    private static volatile String cachedPrompt;

    private SystemPromptBuilder() {}

    public static String build(SessionConfig config) {
        return build(config, "");
    }

    /**
     * @param agentSuffix 子代理自己的附加指引；优先级**低于**用户配置的指令
     *        （见 customInstructions 的说明），传空串表示没有
     */
    public static String build(SessionConfig config, String agentSuffix) {
        String suffix = agentSuffix == null ? "" : agentSuffix;
        String key = fingerprint(config, suffix);
        String hit = cachedPrompt;
        if (hit != null && key.equals(cachedKey)) return hit;

        String prompt = assemble(config, suffix);
        cachedPrompt = prompt;
        cachedKey = key;
        return prompt;
    }

    /** 只覆盖真正参与拼装的字段。少一个就会把「改了设置但提示词没变」变成静默 bug。 */
    private static String fingerprint(SessionConfig config, String suffix) {
        return config.projectDirectory + '\u0000'
            + config.rootExecutionEnabled + '\u0000'
            + config.shizukuExecutionEnabled + '\u0000'
            + config.webSearchEnabled + '\u0000'
            + config.sandboxAgentFullAccess + '\u0000'
            + config.customSystemPrompt + '\u0000'
            + config.roleCard + '\u0000'
            + suffix;
    }

    private static String assemble(SessionConfig config, String agentSuffix) {
        StringBuilder prompt = new StringBuilder(4096);
        prompt.append(IDENTITY);
        prompt.append(CAPABILITIES);
        prompt.append("\nActive project: ").append(config.projectDirectory).append('\n');
        prompt.append("Environment: Android + Termux Bionic userspace. Commands run as the app's Android "
            + "UID and share exactly the same HOME/PREFIX/filesystem as the visible Terminal tab.\n");

        prompt.append("\nPermission state:\n");
        prompt.append("- ").append(rootLine(config)).append('\n');
        prompt.append("- ").append(shizukuLine(config)).append('\n');

        prompt.append("\nOperating rules:\n");
        section(prompt, "Working style", SEC_WORKING_STYLE);
        section(prompt, "Truthfulness and verification", SEC_TRUTHFULNESS);
        section(prompt, "Tool choice", SEC_TOOL_CHOICE);
        // 计划模式的完整说明在上一段里（见 SEC_PROJECT_TASKS 的注释），
        // 这里**不再**另起一段把同样的句子说第二遍。
        section(prompt, "Projects, tasks and subagents", SEC_PROJECT_TASKS);
        if (config.webSearchEnabled) section(prompt, "Web research", SEC_WEB);
        section(prompt, "APK installation", APK_INSTALL);
        section(prompt, "Sandbox and native debugging",
            config.sandboxAgentFullAccess ? SANDBOX_FULL : SANDBOX_BRIEF);

        prompt.append(customInstructions(config));
        prompt.append(roleCard(config));
        prompt.append(agentGuidance(agentSuffix));
        return prompt.toString();
    }

    private static String rootLine(SessionConfig config) {
        return config.rootExecutionEnabled ? ROOT_ENABLED : ROOT_DISABLED;
    }

    private static String shizukuLine(SessionConfig config) {
        return config.shizukuExecutionEnabled ? SHIZUKU_ENABLED : SHIZUKU_DISABLED;
    }

    /** 一段：加粗标题 + 逐条 {@code "- "} 前缀。空段不输出标题。 */
    private static void section(StringBuilder out, String title, String[] rules) {
        if (rules == null || rules.length == 0) return;
        out.append('\n').append(title).append(":\n");
        for (String rule : rules) out.append("- ").append(rule).append('\n');
    }

    /**
     * 用户自己写的高优先级指令。
     *
     * <p>包在 XML 风格的标签里是为了让模型能明确分辨「这段是用户写的」，
     * 而不是把它当成我们自己的规则继续推理。后面那句「不能覆盖什么」是必需的：
     * 用户指令的优先级最高，但它不能覆盖代码强制的权限与安全边界。
     */
    private static String customInstructions(SessionConfig config) {
        String custom = config.customSystemPrompt == null ? "" : config.customSystemPrompt.trim();
        if (custom.isEmpty()) return "";
        return "\nHighest-priority user-configured instructions:\n<custom_system_prompt>\n" + custom
            + "\n</custom_system_prompt>\nThese instructions override ordinary operating preferences and "
            + "agent-specific guidance when they conflict, but they cannot override code-enforced permissions, "
            + "tool availability, Root restrictions, security boundaries, or truthful reporting requirements.\n";
    }

    private static String roleCard(SessionConfig config) {
        String role = config.roleCard == null ? "" : config.roleCard.trim();
        if (role.isEmpty()) return "";
        return "\nActive role card:\n<role_card>\n" + role + "\n</role_card>\n";
    }

    /** 子代理的附加指引。标注了它低于用户指令，否则子代理的定义会盖住用户的意图。 */
    private static String agentGuidance(String agentSuffix) {
        if (agentSuffix == null || agentSuffix.trim().isEmpty()) return "";
        return "\nAgent-specific guidance (lower priority than custom_system_prompt):\n"
            + agentSuffix.trim() + "\n";
    }
}
