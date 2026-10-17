package com.termux.app.zhicode.tools;

import android.content.Context;

import com.termux.app.zhicode.mcp.McpRuntime;
import com.termux.app.zhicode.model.SessionConfig;
import com.termux.app.zhicode.model.ToolExecutionResult;
import com.termux.app.zhicode.termux.TermuxShellExecutor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：把名字映射到实现，并向模型提供全部 schema。
 *
 * <h3>顺序是有意义的</h3>
 * 用 {@link LinkedHashMap} 而不是 {@code HashMap}：{@link #apiSchemas()} 的输出顺序
 * 会直接进入每次请求的提示词。顺序不稳定会让提示词前缀每次都变，
 * 于是上游的提示词缓存**永远不命中**，成本与延迟都会变差。所以工具按固定次序登记。
 *
 * <h3>重名是覆盖而不是报错</h3>
 * {@link #register} 的行为是覆盖。这里刻意不抛错：`mcp_list` / `mcp_call` 是两个
 * 同实现的实例（只有 list 标志不同），将来如果由 MCP 配置动态生成，
 * 覆盖是唯一合理的行为。
 */
public final class ToolRegistry {

    private static final String UNKNOWN_TOOL = "Unknown tool: ";

    /** 名字 → 实现。见类注释：必须是保持插入顺序的 Map。 */
    private final Map<String, ZhiTool> byName = new LinkedHashMap<>();

    /**
     * MCP 运行时。界面（「测试连接」与工具清单）要用同一个实例。
     *
     * <p>必须是**这一个**：它持有 MCP 的会话与进程状态，另建一个会让同一次测试
     * 与真实调用落在不同的运行时上，两边看到的服务器状态可能不一致。
     */
    private final McpRuntime mcpRuntime;

    public ToolRegistry(Context context) {
        TermuxShellExecutor shell = new TermuxShellExecutor(context);
        AndroidIntentBridge intentBridge = new AndroidIntentBridge(context);
        McpRuntime mcp = new McpRuntime();
        this.mcpRuntime = mcp;

        for (ZhiTool tool : builtIns(context, shell, intentBridge, mcp)) register(tool);
    }

    /** 见 {@link #mcpRuntime}。 */
    public McpRuntime mcpRuntime() {
        return mcpRuntime;
    }

    /**
     * 内置工具清单。
     *
     * <p>按用途分四组，顺序即提示词里的顺序：
     * 命令行/系统 → 联网 → 工作区与文件 → 任务与流程。
     * 「最常用的排前面」不是排序依据 —— 排序依据是稳定性（见类注释）。
     */
    private static List<ZhiTool> builtIns(Context context, TermuxShellExecutor shell,
                                          AndroidIntentBridge intentBridge, McpRuntime mcp) {
        return Arrays.asList(
            // 命令行与系统交互
            new BashTool(shell, intentBridge),
            new RootBashTool(shell),
            new ShizukuBashTool(),
            new AndroidIntentTool(intentBridge),
            new ZhiSandboxTool(context),
            new ZhiDebugTool(context, shell),
            new UiCanvasTool(context),

            // 诊断与联网
            new GitStatusTool(shell),
            new TermuxDoctorTool(shell),
            new TermuxRepairTool(shell),
            new WebSearchTool(),
            new WebFetchTool(),

            // 工作树
            new EnterWorktreeTool(shell),
            new ExitWorktreeTool(shell),

            // 文件读写
            new ReadTool(),
            new ReadManyTool(),
            new StatTool(),
            new TreeTool(),
            new ListTool(),
            new GlobTool(),
            new GrepTool(),
            new WriteTool(),
            new EditTool(),
            new MultiEditTool(),
            new CopyTool(),
            new MoveTool(),
            new MkdirTool(),
            new DeleteTool(),

            // 任务与流程
            new TaskCreateTool(),
            new TaskGetTool(),
            new TaskListTool(),
            new TaskUpdateTool(),
            new SleepTool(),
            new TodoWriteTool(),
            new SkillTool(),
            new EnterPlanModeTool(),
            new ExitPlanModeTool(),

            // MCP：同一实现的两个入口，见类注释
            new McpTool(mcp, true),
            new McpTool(mcp, false));
    }

    /** 登记（或按名字覆盖）一个工具。 */
    public void register(ZhiTool tool) {
        byName.put(tool.name(), tool);
    }

    /** 按名字取工具；未登记返回 {@code null}。 */
    public ZhiTool get(String name) {
        return byName.get(name);
    }

    /** 全部工具的 schema，按登记顺序。 */
    public JSONArray apiSchemas() {
        JSONArray schemas = new JSONArray();
        for (ZhiTool tool : byName.values()) schemas.put(describe(tool));
        return schemas;
    }

    /**
     * 单个工具的 schema。
     *
     * <p>四处 put 都是字面量键 + 本类自己造的值，失败只可能是 JSONObject 实现变了，
     * 所以直接转成 {@link IllegalStateException}。
     */
    private static JSONObject describe(ZhiTool tool) {
        JSONObject schema = new JSONObject();
        try {
            schema.put("name", tool.name());
            schema.put("description", tool.description());
            schema.put("input_schema", tool.inputSchema());
            return schema;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public ToolExecutionResult execute(SessionConfig config, String name, JSONObject input) throws Exception {
        return execute(config, name, input, null);
    }

    /**
     * 执行一次工具调用。
     *
     * <p>名字不认识时返回**错误结果**而不是抛异常：模型偶尔会发明工具名，
     * 把「这个名字不存在」作为工具结果回给它，它就能自己改过来；
     * 抛异常则会中断整轮对话。
     */
    public ToolExecutionResult execute(SessionConfig config, String name, JSONObject input,
                                       ZhiTool.ProgressListener progress) throws Exception {
        ZhiTool tool = byName.get(name);
        if (tool == null) return ToolExecutionResult.error(UNKNOWN_TOOL + name);
        return tool.execute(config, input, progress);
    }
}
