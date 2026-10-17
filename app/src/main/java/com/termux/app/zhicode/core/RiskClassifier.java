package com.termux.app.zhicode.core;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「高风险」标签的**唯一**判定处。
 *
 * <h3>为什么要按内容判，而不是按工具种类判</h3>
 *
 * 原先的判定是 {@code kind == SYSTEM || kind == SHELL}，也就是「凡是跑 shell 的
 * 都算高风险」。这在手机上等于**每个命令都挂红标**：{@code ls} 和
 * {@code rm -rf /} 长得一模一样。红标一旦天天出现就不再是信息，用户学会的是
 * 忽略它 —— 真正危险的那条命令反而混在里面看不见。所以这里按**命令内容**判。
 *
 * <h3>默认不危险</h3>
 *
 * 判定从「不危险」出发，只有命中下面这些**说得出来由**的模式才升级：
 *
 * <ul>
 *   <li><b>提权 / 换身份</b>：{@code sudo}、{@code su}、{@code doas}、{@code run-as}。</li>
 *   <li><b>不可逆的破坏</b>：{@code rm -rf}、{@code mkfs*}、{@code dd of=}、
 *       {@code fdisk}、{@code shred}，以及往 {@code /system}、{@code /vendor}、
 *       {@code /data/adb} 这类应用目录之外的路径写。</li>
 *   <li><b>改系统状态</b>：{@code pm install/uninstall/clear}、{@code am force-stop}、
 *       {@code setprop}、{@code svc}、{@code mount}、{@code reboot}、杀进程。</li>
 *   <li><b>装东西 / 改依赖</b>：{@code pkg/apt/dpkg install}、{@code pip install}、
 *       {@code npm install -g}。</li>
 *   <li><b>把网络上的东西直接喂给 shell</b>：{@code curl ... | sh}、
 *       {@code wget ... | bash}、{@code eval}。</li>
 *   <li><b>改历史</b>：{@code git push --force}、{@code git reset --hard}、
 *       {@code git clean -f}。</li>
 * </ul>
 *
 * 其余（{@code ls} / {@code cat} / {@code grep} / {@code git status} /
 * {@code ./gradlew ...} / {@code mkdir} / {@code cp} / {@code chmod +x} /
 * {@code rm file.txt}）一律**不**打标。宁可漏标也不滥标：滥标会被学会忽略。
 *
 * <h3>不用 android.*</h3>
 *
 * 这个类刻意只依赖 {@code org.json}，所以能被
 * {@code test-jvm-fast.sh} 直接跑单测（见 {@code RiskClassifierTest}）。
 */
public final class RiskClassifier {

    private RiskClassifier() { }

    /** 一律高危的可执行名（提权 / 分区 / 内核模块 / 挂载 / 关机）。 */
    private static final Set<String> ALWAYS_RISKY = new HashSet<>(Arrays.asList(
        "sudo", "su", "doas", "run-as",
        "reboot", "shutdown", "halt", "poweroff",
        "mount", "umount", "insmod", "rmmod", "modprobe",
        "iptables", "ip6tables", "nft",
        "chroot", "nsenter", "unshare",
        "fdisk", "parted", "shred", "fastboot",
        "setprop", "svc",
        "kill", "killall", "pkill", "eval"
    ));

    /** {@code pm} 里会改系统状态的子命令；{@code pm list} / {@code pm path} 不算。 */
    private static final Set<String> RISKY_PM = new HashSet<>(Arrays.asList(
        "install", "uninstall", "clear", "enable", "disable",
        "grant", "revoke", "hide", "unhide", "suspend", "unsuspend",
        "set-permission-flags", "set-distracting-restriction"
    ));

    /** {@code am} 里会改系统状态的子命令；{@code am get-current-user} 之类不算。 */
    private static final Set<String> RISKY_AM = new HashSet<>(Arrays.asList(
        "start", "startservice", "start-activity", "broadcast",
        "force-stop", "kill", "instrument", "send-trim-memory"
    ));

    /** 包管理器的「装/删/升级」。这些动的是环境本身，不是工程文件。 */
    private static final Set<String> PACKAGE_MANAGERS = new HashSet<>(Arrays.asList(
        "pkg", "apt", "apt-get", "dpkg", "pip", "pip3", "npm", "yarn", "pnpm", "gem", "cargo"
    ));

    /** 包管理器里算「装/删/升级」的子命令。{@code pkg list-installed} 是只读。 */
    private static final Set<String> RISKY_PKG_VERBS = new HashSet<>(Arrays.asList(
        "install", "remove", "uninstall", "upgrade", "dist-upgrade", "full-upgrade",
        "purge", "autoremove", "-i", "-r", "--install"
    ));

    /** 应用目录之外、写进去就回不来的路径前缀。 */
    private static final List<String> PROTECTED_PREFIXES = Arrays.asList(
        "/system", "/vendor", "/product", "/sbin", "/data/adb", "/init.rc", "/boot"
    );

    /**
     * 界面用的判定入口。
     *
     * @param toolName 工具名（{@code call.name}，大小写不敏感）
     * @param input    工具入参；可以是 {@code null}（没有入参的工具）
     * @return 是否给这次调用挂「高风险」红标
     */
    public static boolean isHighRisk(String toolName, JSONObject input) {
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.US).trim();
        JSONObject in = input == null ? new JSONObject() : input;

        switch (tool) {
            case "bash":
            case "shell":
            case "termux":
                return isRiskyCommand(in.optString("command", ""));
            case "root":
            case "rootbash":
                // 这个工具本身就是「以 root 跑」，没有温和的用法。
                return true;
            case "shizuku":
                // shell 身份不是 root，判据与 Bash 同一套（看命令干了什么），
                // 而不是一律红标 —— 否则「dumpsys 看一眼」和「pm uninstall」会被画成同一件事。
                return isRiskyCommand(in.optString("command", ""));
            case "delete":
            case "del":
                // 递归删目录。单文件删除仍走普通确认。
                return in.optBoolean("recursive", false);
            case "androidintent":
                // 装 APK 会改系统里的应用集合；打开链接/页面不改任何东西。
                return "install_apk".equalsIgnoreCase(in.optString("operation", "intent"));
            case "debug":
            case "zhibug":
                return isRiskyDebugAction(in.optString("action", ""));
            case "sandbox":
                return isRiskySandboxAction(in.optString("action", ""));
            default:
                return false;
        }
    }

    /** {@code Debug} 里会改内存 / 注入 / 发信号的动作。 */
    private static boolean isRiskyDebugAction(String action) {
        switch (action == null ? "" : action.toLowerCase(Locale.US)) {
            case "memory_write":
            case "frida_write":
            case "frida_patch":
            case "frida_protect":
            case "frida_eval":
            case "frida_install":
            case "frida_load":
            case "load_library":
            case "signal":
            case "gc":
                return true;
            default:
                return false;
        }
    }

    /** {@code Sandbox} 里会装/删应用、清数据的动作。 */
    private static boolean isRiskySandboxAction(String action) {
        switch (action == null ? "" : action.toLowerCase(Locale.US)) {
            case "install":
            case "uninstall":
            case "clear_data":
            case "stop":
                return true;
            default:
                return false;
        }
    }

    /**
     * 命令内容判定：逐段看，任何一段命中危险模式就整体算高危。
     *
     * <p>按 {@code ;} {@code &&} {@code ||} {@code |} 和换行切段。
     * 切段是必要的：{@code ls && sudo rm -rf /} 只看第一个 token 会被判成无害。
     */
    public static boolean isRiskyCommand(String command) {
        if (command == null) return false;
        String raw = command.trim();
        if (raw.isEmpty()) return false;

        // 「把网络内容喂给 shell」是管道级的形状，先整条看一眼。
        if (pipesIntoShell(raw)) return true;
        if (writesToProtectedPath(raw)) return true;

        for (String segment : raw.split("[;&|\\n]+")) {
            if (isRiskySegment(segment)) return true;
        }
        return false;
    }

    private static boolean isRiskySegment(String segment) {
        List<String> tokens = tokens(segment);
        if (tokens.isEmpty()) return false;
        String exe = baseName(tokens.get(0));
        String rest = join(tokens, 1);

        if (ALWAYS_RISKY.contains(exe)) return true;
        // mkfs / mkfs.ext4 / mke2fs …
        if (exe.startsWith("mkfs") || exe.startsWith("mke2fs")) return true;

        if ("dd".equals(exe)) return rest.contains("of=");
        if ("pm".equals(exe)) return RISKY_PM.contains(arg(tokens, 1));
        if ("am".equals(exe)) return RISKY_AM.contains(arg(tokens, 1));
        if ("rm".equals(exe)) return riskyRemove(tokens);
        if ("chmod".equals(exe)) return riskyChmod(tokens);
        if ("chown".equals(exe)) return hasFlag(tokens, "R") || hasFlag(tokens, "h");
        if ("git".equals(exe)) return riskyGit(tokens);
        if (PACKAGE_MANAGERS.contains(exe)) return riskyPackageManager(tokens);
        return false;
    }

    /**
     * {@code rm} 只在两种情况下高危。
     *
     * <p>「递归 + 强制」是一对：{@code rm -rf build/} 在工程里是日常动作，
     * 但 {@code rm -rf $HOME} 或 {@code rm -rf /} 是不可逆的。所以再看一眼目标 ——
     * 目标是根目录 / 家目录 / 通配符 / 家目录之外的绝对路径才算高危，
     * 相对路径（含 {@code build/}、{@code node_modules/}）保持普通确认。
     */
    private static boolean riskyRemove(List<String> tokens) {
        boolean recursive = hasFlag(tokens, "r") || hasLongFlag(tokens, "--recursive");
        String target = lastNonFlag(tokens);
        if (target == null) return false;
        if (dangerousTarget(target)) return true;
        return recursive && outsideHomeTarget(target);
    }

    /** {@code chmod +x script.sh} 是日常；{@code 777} / 递归改权限等于把它全局打开。 */
    private static boolean riskyChmod(List<String> tokens) {
        // 模式位不一定是第二个 token（`chmod -R 777 dir`），取第一个非选项参数。
        String mode = firstNonFlag(tokens);
        if (mode.equals("777") || mode.equals("666") || mode.contains("a+rwx")) return true;
        return (hasFlag(tokens, "R") || hasLongFlag(tokens, "--recursive")) && mode.contains("7");
    }

    /** 根 / 家目录 / 通配符 —— 删这些没有「只删错一点」的可能。 */
    private static boolean dangerousTarget(String target) {
        String t = target.trim();
        return t.equals("/") || t.equals("/*") || t.equals("*")
            || t.equals("~") || t.equals("~/") || t.equals("$HOME") || t.equals("${HOME}")
            || t.equals("..") || t.equals("../");
    }

    /** 家目录之外的**绝对**路径。相对路径不管 —— 那种错误看得见、也回得来。 */
    private static boolean outsideHomeTarget(String target) {
        String t = target.trim();
        if (!t.startsWith("/")) return false;
        return !t.startsWith("/data/data/") && !t.startsWith("/data/user/")
            && !t.startsWith("/data/local/tmp") && !t.startsWith("/storage/emulated")
            && !t.startsWith("/sdcard");
    }

    private static boolean riskyGit(List<String> tokens) {
        String sub = arg(tokens, 1);
        switch (sub) {
            case "push":
                return hasFlag(tokens, "f") || hasLongFlag(tokens, "--force")
                    || hasLongFlag(tokens, "--force-with-lease");
            case "reset":
                return hasLongFlag(tokens, "--hard");
            case "clean":
                return hasFlag(tokens, "f");
            default:
                return false;
        }
    }

    private static boolean isNodePackageManager(String exe) {
        return "npm".equals(exe) || "yarn".equals(exe) || "pnpm".equals(exe);
    }

    private static boolean riskyPackageManager(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            if (RISKY_PKG_VERBS.contains(tokens.get(i).toLowerCase(Locale.US))) {
                // npm/yarn 不带 -g 时只动当前工程的 node_modules，按普通确认处理。
                // pip 没有这个豁免：Termux 里没有 venv 的话 `pip install` 装的是环境本身。
                if (isNodePackageManager(baseName(tokens.get(0)))
                        && !tokens.contains("-g") && !tokens.contains("--global")) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    /** {@code ... | sh} / {@code ... | bash} / {@code ... | sudo bash}。 */
    private static boolean pipesIntoShell(String command) {
        String c = command.replace(" ", "");
        return c.contains("|sh") || c.contains("|bash") || c.contains("|zsh")
            || c.contains("|sudosh") || c.contains("|sudobash");
    }

    /** 往应用目录外的系统路径写：重定向、或 rm/mv/cp/chmod/chown/tee 指过去。 */
    private static boolean writesToProtectedPath(String command) {
        String c = " " + command + " ";
        boolean touchesVerb = c.contains(" rm ") || c.contains(" mv ") || c.contains(" cp ")
            || c.contains(" chmod ") || c.contains(" chown ") || c.contains(" tee ")
            || c.contains(">") || c.contains(" truncate ");
        if (!touchesVerb) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (c.contains(" " + prefix) || c.contains(">" + prefix)) return true;
        }
        return false;
    }

    // ---- 小工具（不引正则，手机上也快） ----------------------------------

    private static List<String> tokens(String segment) {
        String s = segment == null ? "" : segment.trim();
        if (s.isEmpty()) return java.util.Collections.emptyList();
        // 去掉前导的 VAR=value 赋值，否则 `FOO=1 ./gradlew` 的 exe 会变成 FOO=1。
        String[] parts = s.split("\\s+");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String p : parts) {
            if (out.isEmpty() && p.contains("=") && !p.startsWith("-")) continue;
            out.add(p);
        }
        return out;
    }

    private static String join(List<String> tokens, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < tokens.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    private static String arg(List<String> tokens, int index) {
        return index < tokens.size() ? tokens.get(index).toLowerCase(Locale.US) : "";
    }

    private static String baseName(String token) {
        String t = token == null ? "" : token;
        int slash = t.lastIndexOf('/');
        if (slash >= 0) t = t.substring(slash + 1);
        return t.toLowerCase(Locale.US);
    }

    /** 短标志：{@code -rf} 里含 r。 */
    private static boolean hasFlag(List<String> tokens, String letter) {
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.startsWith("-") && !t.startsWith("--") && t.length() > 1
                    && t.substring(1).toLowerCase(Locale.US).contains(letter.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasLongFlag(List<String> tokens, String flag) {
        return tokens.contains(flag);
    }

    private static String lastNonFlag(List<String> tokens) {
        String found = null;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("-")) found = t;
        }
        return found;
    }

    private static String firstNonFlag(List<String> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("-")) return t.toLowerCase(Locale.US);
        }
        return "";
    }
}
