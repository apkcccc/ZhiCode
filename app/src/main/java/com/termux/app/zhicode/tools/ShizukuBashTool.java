package com.termux.app.zhicode.tools;

import com.termux.app.zhicode.model.SessionConfig;
import com.termux.app.zhicode.model.ToolExecutionResult;
import com.zhizhu.zhicode.shizuku.ShizukuShell;

import org.json.JSONObject;

import java.io.File;

/**
 * 以 shell 身份（uid 2000，Shizuku 用 Root 启动时是 0）执行 Android 命令。
 *
 * <h3>为什么需要它，而 Bash 不够</h3>
 * Bash 跑在应用自己的 uid 上：{@code pm install}、{@code am start}、{@code dumpsys}、
 * {@code settings put}、{@code input}、{@code screencap} 这些都要 shell 才够格。
 * Shizuku 正是「不 root 也拿到 shell」的那条路，所以这个工具补的是
 * 「应用能做的事」与「系统能做的事」之间的那道墙。
 *
 * <h3>三道门槛</h3>
 * <ol>
 *   <li><b>设置开关</b>{@code shizukuExecutionEnabled}：默认关闭。关着时工具**不暴露**给模型
 *       （schema 里就没有），而不是让它调用后收一条错误 —— 少一轮白花的请求。</li>
 *   <li><b>真实身份校验</b>：命令外面包一段 {@code id -u} 检查。这不是防用户，
 *       而是防「Shizuku 以非 shell/root 身份跑起来、命令却照常执行完」——
 *       那时模型会以为自己真的以 shell 改了某个系统设置。检查不过就 exit 126。</li>
 *   <li><b>超时</b>：默认 120 秒，上限一小时。系统命令更容易挂住
 *       （{@code dumpsys} 在大设备上能跑几分钟），没有上限会卡死整个回合。</li>
 * </ol>
 *
 * <h3>{@code cwd} 为什么只能是绝对路径</h3>
 * shell 进程的解析基准不是应用的工作目录，给它一个相对路径只会得到
 * 「目录不存在」；而默认值必须是 {@code /} —— 应用私有目录 shell 进不去。
 */
final class ShizukuBashTool implements ZhiTool {

    private static final int DEFAULT_TIMEOUT_MS = ShizukuShell.DEFAULT_TIMEOUT_MS;
    private static final int MIN_TIMEOUT_MS = 1000;
    private static final int MAX_TIMEOUT_MS = 3_600_000;
    private static final int EXIT_NOT_SHELL = 126;

    /** 见类注释第 2 点。拼在用户命令**之前**，所以它自己的退出码优先。 */
    private static final String ASSERT_SHELL =
        "uid=$(id -u); if [ \"$uid\" != 2000 ] && [ \"$uid\" != 0 ]; then "
            + "echo \"[ZhiCode] Shizuku 未以 shell/root 身份运行 (uid=$uid)\" >&2; exit "
            + EXIT_NOT_SHELL + "; fi; ";

    private static final String DISABLED = "Agent Shizuku is disabled in Settings";
    private static final String EMPTY_COMMAND = "Shizuku command is empty";

    @Override public String name() { return "Shizuku"; }

    @Override public String description() {
        return "Run an Android shell command through Shizuku (adb-shell equivalent: uid 2000, or uid 0 "
            + "when Shizuku was started with root). Use it for shell-level system work the app-uid Bash "
            + "cannot do (pm/am/settings/appops/cmd/dumpsys/input/screencap); keep ordinary file work in "
            + "Bash. The embedded Termux userland stays app-private.";
    }

    /**
     * {@code SYSTEM}：与 Root 同一档。
     *
     * <p>它改的是系统状态（装包、改设置、注入输入事件），不属于「改项目文件」，
     * 所以不该被 {@code acceptEdits} 放行 —— 那两档的字面意思只是「接受编辑」。
     */
    @Override public PermissionKind permissionKind() { return PermissionKind.SYSTEM; }

    @Override public JSONObject inputSchema() {
        JSONObject properties = new JSONObject();
        try {
            properties.put("command", ToolSchemas.string("Command to execute as the Android shell user."));
            properties.put("cwd", ToolSchemas.string(
                "Optional absolute working directory the shell user can enter (default /). "
                    + "Paths inside this app's private Termux home are not readable by the shell user."));
            properties.put("timeout_ms",
                ToolSchemas.integer("Timeout in milliseconds (maximum 3600000).", 1000));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ToolSchemas.object(properties, "command");
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input)
            throws Exception {
        return execute(config, input, null);
    }

    @Override public ToolExecutionResult execute(SessionConfig config, JSONObject input,
                                                 ProgressListener progress) throws Exception {
        if (config == null || !config.shizukuExecutionEnabled) {
            return ToolExecutionResult.error(DISABLED);
        }
        String command = input.optString("command", "").trim();
        if (command.isEmpty()) return ToolExecutionResult.error(EMPTY_COMMAND);

        String cwd = input.optString("cwd", "").trim();
        if (!cwd.isEmpty() && !new File(cwd).isAbsolute()) {
            return ToolExecutionResult.error(
                "cwd must be an absolute path the shell user can enter");
        }
        int timeout = Math.max(MIN_TIMEOUT_MS,
            Math.min(MAX_TIMEOUT_MS, input.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)));

        ShizukuShell.OutputListener live = progress == null ? null
            : (chunk, stderr, elapsed) -> progress.onProgress(chunk, stderr, elapsed);
        ShizukuShell.Result result = ShizukuShell.execute(ASSERT_SHELL + command, cwd, timeout, live);
        return ToolExecutionResult.command(result.exitCode, result.combined());
    }
}
