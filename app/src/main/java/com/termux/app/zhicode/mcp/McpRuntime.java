package com.termux.app.zhicode.mcp;

import com.termux.app.zhicode.json.JsonItems;
import com.termux.app.zhicode.model.ToolExecutionResult;
import com.termux.app.zhicode.storage.McpConfigStore;
import com.termux.app.zhicode.storage.ZhiMemory;
import com.termux.shared.termux.TermuxConstants;
import com.zhizhu.zhicode.compose.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 的两套传输：stdio 子进程，以及 Streamable HTTP。
 *
 * <h3>每次调用都是一次完整的会话</h3>
 * stdio 与 HTTP 都是「连上 → initialize → 通知已初始化 → 发请求 → 断开」。
 * 不做连接复用是**有意的**：MCP 服务器是有状态的进程，长期挂着一个空闲连接
 * 意味着多一个要管的生命周期（谁负责在组件重建后清理它？），
 * 而这里的调用是低频的（一次工具调用一次）。断开时用
 * {@code destroy → destroyForcibly} 两步，因为有些实现不响应优雅终止。
 *
 * <h3>为什么 HTTP 侧要把 {@code _session_id} 藏起来又删掉</h3>
 * Streamable HTTP 的会话 id 在**响应头**里，而我们要把它带到后续请求上。
 * 内部用一个私有键在两次请求之间传递它，交回给调用方之前删掉 ——
 * 否则它会混进工具结果，看起来像服务器返回的一个字段。
 *
 * <h3>stdio 的分帧</h3>
 * 按 {@code Content-Length} 分帧（LSP 风格），不是按行。
 * 按行读会在正文里出现换行的那一刻错位，而错位后的表现是「JSON 解析失败」，
 * 看不出是分帧错了。
 */
public final class McpRuntime {

    private static final long TIMEOUT_MS = 30_000L;
    private static final String PROTOCOL_VERSION = "2024-11-05";
    /** 单条消息上限。超过它基本可以断定对端不是 MCP 服务器。 */
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;
    private static final int READ_BUFFER_BYTES = 8192;
    /** 轮询间隔：{@link BufferedInputStream#available()} 不阻塞，所以要自己让出 CPU。 */
    private static final long POLL_INTERVAL_MS = 5L;
    /** 挑响应时最多丢弃几条不是自己那条的消息，见 {@link #read}。 */
    private static final int MAX_SKIPPED_MESSAGES = 8;
    /** stderr 保留上限（尾部）；见 {@link StderrDrain}。 */
    private static final int STDERR_CAPTURE_BYTES = 8 * 1024;
    /** stderr 拼进错误信息时的字符上限：排障要的是原因，不是整段横幅。 */
    private static final int STDERR_REPORT_CHARS = 600;
    /** 杀进程之后等排空线程收尾的宽限期。 */
    private static final long EXIT_DRAIN_GRACE_MS = 400L;
    /** SSE 响应最多读多少行就算一个事件（防止畸形流把这里变成死循环）。 */
    private static final int MAX_EVENT_LINES = 512;
    /** Streamable HTTP 的 SSE 响应类型，见 {@link #readBody}。 */
    private static final String EVENT_STREAM_TYPE = "text/event-stream";
    /** 认不出来的内容块直接贴其 JSON 时的截断长度。 */
    private static final int NON_TEXT_REPORT_CHARS = 2000;
    /** 单次工具输出的字符上限，见 {@link #capContent}。 */
    private static final int MAX_CONTENT_CHARS = 200_000;
    private static final String CONTENT_TRUNCATED = "\n…[MCP 输出已截断]";

    private static final String KEY_SESSION_ID = "_session_id";
    private static final String HEADER_SESSION_ID = "Mcp-Session-Id";
    /** 协商版本：initialize 结果里的字段名，也是后续请求的头名，见 {@link #overHttp}。 */
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";
    private static final String METHOD_INITIALIZE = "initialize";
    private static final String METHOD_INITIALIZED = "notifications/initialized";
    private static final String METHOD_LIST_TOOLS = "tools/list";
    private static final String METHOD_CALL_TOOL = "tools/call";

    /** 自增的 JSON-RPC 请求 id。 */
    private final AtomicLong nextId = new AtomicLong(1L);
    private final McpConfigStore store = new McpConfigStore();

    // ------------------------------------------------------------------ 对外

    /**
     * 列出已启用的服务器，并各自查一次工具清单。
     *
     * <p>单个服务器出错**不**影响其它服务器：那一行会带上 {@code ok:false} 与错误原因。
     * 让整次列举失败会让一个配错的服务器挡住所有可用的服务器。
     *
     * <h3>被用户禁用的工具会从这里消失</h3>
     * 这是「那个开关真的有用」的落点：{@code mcp_list} 是模型发现工具的**唯一入口**，
     * 把它过滤掉，模型就永远看不到那个工具名。每个工具还会带上 {@code enabled} 与
     * {@code approval} 两个字段，界面据此渲染开关。
     *
     * <p>不在这里过滤也可以（{@link #call} 还会再挡一次），但那样模型会先花一次调用
     * 去试一个注定失败的工具，然后把失败信息读进上下文。
     */
    public JSONArray listServers() {
        JSONArray rows = new JSONArray();
        for (McpConfigStore.Server server : store.load()) {
            if (!server.enabled) continue;
            JSONObject row = new JSONObject();
            try {
                row.put("server", server.name).put("type", server.type).put("scope", server.scope);
                JSONArray tools = new JSONArray();
                for (JSONObject tool : JsonItems.of(toolsOf(server))) {
                    String name = tool.optString("name", "");
                    boolean toolEnabled = server.isToolEnabled(name);
                    // 标记写在**过滤之前**：界面要看到"这个工具被关掉了"，
                    // 只把启用的发下去的话，用户会以为那个工具从服务器上消失了。
                    tool.put("enabled", toolEnabled);
                    tool.put("approval", server.isToolApprovalRequired(name));
                    if (toolEnabled) tools.put(tool);
                }
                row.put("ok", true).put("tools", tools);
            } catch (Exception failure) {
                // 一行日志：`mcp_list` 是「测试连接」唯一的落点，
                // 界面上只会看到一句错误，原因得在这里留痕。
                ZhiMemory.log("mcpList server=" + server.name + " failed=" + messageOf(failure));
                try {
                    row.put("ok", false).put("error", messageOf(failure));
                } catch (Exception ignored) {
                    // JSONObject.put 在这里不会失败；真失败也只能给出这一行。
                }
            }
            rows.put(row);
        }
        return rows;
    }

    /** 调用某个服务器上的一个工具。 */
    public ToolExecutionResult call(String serverName, String toolName, JSONObject arguments) {
        McpConfigStore.Server server = store.find(serverName);
        if (server == null || !server.enabled) {
            return ToolExecutionResult.error("MCP server unavailable: " + serverName);
        }
        if (toolName == null || toolName.trim().isEmpty()) {
            return ToolExecutionResult.error("MCP tool name is empty");
        }
        // 被用户禁用的工具在这里**再挡一次**。不能只靠 listServers 里过滤掉：
        // 模型可能在关闭之前就见过这个名字（或从对话历史里记住了），
        // 而"列表里没有"不构成任何保护。
        if (!server.isToolEnabled(toolName)) {
            return ToolExecutionResult.error(
                "MCP tool disabled by user: " + serverName + "/" + toolName);
        }
        long startedAt = System.currentTimeMillis();
        try {
            JSONObject params = new JSONObject()
                .put("name", toolName)
                .put("arguments", arguments == null ? new JSONObject() : arguments);
            JSONObject result = session(server, METHOD_CALL_TOOL, params);
            JSONArray content = result == null ? null : result.optJSONArray("content");
            // MCP 把「工具执行失败」放在 isError 里而不是 JSON-RPC 错误里：
            // 前者是可预期的业务失败，后者是协议层失败。两者都要映射成工具失败，
            // 但只有后者会被上面的 catch 接住。
            if (result != null && result.optBoolean("isError", false)) {
                String text = flattenContent(content);
                log(server, toolName, startedAt, true);
                // 失败但没有正文时不能给空字符串：空结果会被上层读成
                // "命令没有输出"，而不是"这次调用失败了"。
                return ToolExecutionResult.error(capContent(
                    text.isEmpty() ? "MCP 工具报告失败，但没有返回原因" : text));
            }
            String text = capContent(flattenContent(content));
            log(server, toolName, startedAt, false);
            return ToolExecutionResult.ok(text);
        } catch (Exception failure) {
            log(server, toolName, startedAt, true);
            return ToolExecutionResult.error("MCP call failed: " + messageOf(failure));
        }
    }

    /**
     * 单次工具输出的长度闸门。
     *
     * <p>没有闸门时，一个"返回整份文件/整张表"的 MCP 服务器可以给出接近
     * {@link #MAX_MESSAGE_BYTES} 的一条消息，而它会被**原样**存进会话、再原样发给模型：
     * 512MB 堆上这是能直接把应用打死的一条路径。取值比 WebFetch 的 100000 宽松
     * （MCP 返回结构化数据的场合更多），但远低于消息上限。
     */
    private static String capContent(String text) {
        if (text == null || text.length() <= MAX_CONTENT_CHARS) return text == null ? "" : text;
        return text.substring(0, MAX_CONTENT_CHARS) + CONTENT_TRUNCATED;
    }

    /**
     * 一行诊断日志。
     *
     * <p>MCP 出问题时最先要看的就是「哪台服务器、哪个工具、花了多久」，
     * 而这些信息在工具结果里是没有的（结果只有正文）。走 `ZhiMemory` 而不是
     * `ZhiFrameTrace`：后者每个入口都被 `BuildConfig.DEBUG` 挡着，发布包里等于不存在，
     * 而 MCP 恰恰是只在发布包里被用到的东西。
     */
    private static void log(McpConfigStore.Server server, String toolName, long startedAt, boolean failed) {
        ZhiMemory.log("mcpCall server=" + server.name + " tool=" + toolName
            + " ok=" + !failed + " ms=" + (System.currentTimeMillis() - startedAt));
    }

    /**
     * 某个远端工具是否被用户标了「需要审批」。
     *
     * <p>给 {@code McpTool.requiresApproval} 用。**只读配置，不连服务器** ——
     * 权限判定发生在每次工具调用上，那里绝不能去起进程或发 HTTP：
     * 一次判定要等 30 秒（连接超时），而且会在用户还没批准之前就把服务器拉起来了。
     *
     * @return 服务器或工具不存在时返回 false（不拦）
     */
    public boolean requiresApproval(String serverName, String toolName) {
        McpConfigStore.Server server = store.find(serverName);
        if (server == null || !server.enabled) return false;
        if (toolName == null || toolName.trim().isEmpty()) return false;
        // 被禁用的工具不在这里拦：它会被 call() 拒绝。这里答的是"要不要问用户"，
        // 而一个注定失败的调用不值得弹窗。
        if (!server.isToolEnabled(toolName)) return false;
        return server.isToolApprovalRequired(toolName);
    }

    private JSONArray toolsOf(McpConfigStore.Server server) throws Exception {
        JSONObject result = session(server, METHOD_LIST_TOOLS, new JSONObject());
        JSONArray tools = result == null ? null : result.optJSONArray("tools");
        return tools == null ? new JSONArray() : tools;
    }

    // ------------------------------------------------------------------ 传输

    private JSONObject session(McpConfigStore.Server server, String method, JSONObject params)
            throws Exception {
        String type = server.type;
        if ("stdio".equalsIgnoreCase(type)) return overStdio(server, method, params);
        if ("http".equalsIgnoreCase(type) || "sse".equalsIgnoreCase(type)) {
            return overHttp(server, method, params);
        }
        throw new IOException("unsupported MCP transport: " + type);
    }

    /**
     * 起子进程走 stdio。
     *
     * <p>环境变量在子进程自己的环境上改（{@code builder.environment()}），
     * 不动本进程的 —— 后者会让 MCP 服务器的影响扩散到整个应用。
     */
    private JSONObject overStdio(McpConfigStore.Server server, String method, JSONObject params)
            throws Exception {
        if (server.command == null || server.command.trim().isEmpty()) {
            throw new IOException("MCP stdio command is empty");
        }
        ProcessBuilder builder = new ProcessBuilder(commandLine(server));
        applyEnvironment(builder.environment(), server.env);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);

        Process process;
        try {
            process = builder.start();
        } catch (IOException failed) {
            throw new IOException(describeStartFailure(server, failed), failed);
        }
        // ⚠️ stderr 必须**有人读**。管道有 64KB 的缓冲，写满之后服务器会永久卡在
        // write 上，而它一卡，我们这一侧看到的只是"stdout 一直没有响应"
        // —— 超时 30 秒之后报一句 MCP response timeout，原因完全看不出来。
        // 顺手把它接进错误信息：命令没找到、缺依赖、配置写错都只在这里有痕迹。
        StderrDrain drain = new StderrDrain(process.getErrorStream(), STDERR_CAPTURE_BYTES);
        drain.start();
        try {
            BufferedInputStream in = new BufferedInputStream(process.getInputStream());
            BufferedOutputStream out = new BufferedOutputStream(process.getOutputStream());
            long initId = write(out, METHOD_INITIALIZE, initializeParams(), true);
            read(in, initId);
            // 通知没有 id，也不会有响应 —— 这是协议规定的，不是我们偷懒。
            write(out, METHOD_INITIALIZED, new JSONObject(), false);
            long requestId = write(out, method, params, true);
            return read(in, requestId);
        } catch (Exception failure) {
            String stderr = drain.tail();
            // 只有"closed stdout"这一句时，用户不知道是命令没找到、缺依赖还是配置写错了。
            if (stderr.isEmpty()) throw failure;
            throw new IOException(messageOf(failure) + "；服务器 stderr: " + stderr, failure);
        } finally {
            process.destroy();
            // 有些实现在收到 SIGTERM 之后仍然挂着。
            if (process.isAlive()) process.destroyForcibly();
            drain.finish(EXIT_DRAIN_GRACE_MS);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getOutputStream());
            closeQuietly(process.getErrorStream());
        }
    }

    /**
     * 子进程环境。
     *
     * <h3>为什么必须自己给 PATH / LD_LIBRARY_PATH</h3>
     * 本进程是 Android **应用**进程，它的 PATH 里没有 Termux 的 bin 目录
     * （Bash 工具早就设了这两个变量，见 {@code TermuxShellExecutor}，MCP 这一侧一直漏着）。
     * 而 MCP 服务器几乎全是 Termux 里的东西 —— `node`、`npx`、`python3`。
     * 文档里照抄 `"command": "npx"` 时，{@link ProcessBuilder} 会直接抛
     * {@code Cannot run program "npx": error=2, No such file or directory}：
     * 一句和真实原因隔着两层的话。
     *
     * <p>用户写在 {@code env} 里的键值对**最后**应用，所以它们仍然可以覆盖这里的默认值。
     */
    private void applyEnvironment(Map<String, String> environment, JSONObject pairs) {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        File temporary = new File(prefix, "tmp");
        if (!temporary.isDirectory()) temporary.mkdirs();
        String inherited = environment.get("PATH");
        environment.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        environment.put("PREFIX", prefix);
        environment.put("TMPDIR", temporary.getAbsolutePath());
        // 前缀而不是替换：系统自带的解释器（/system/bin/sh 之类）也要找得到。
        environment.put("PATH", inherited == null || inherited.isEmpty()
            ? prefix + "/bin"
            : prefix + "/bin:" + inherited);
        environment.put("LD_LIBRARY_PATH", prefix + "/lib");
        environment.put("LANG", "en_US.UTF-8");
        applyPairs(environment, pairs);
    }

    /**
     * 走 Streamable HTTP。
     *
     * <p>三步：initialize 拿到会话 id（可能在响应头里）与**协商版本** →
     * 发已初始化通知 → 发真正的请求。会话 id 用私有键在请求之间传递，见类注释。
     *
     * <h3>协商版本为什么要带在后续请求上</h3>
     * 2025-03-26 之后的规范要求：`initialize` 之后的所有请求都要带
     * {@code MCP-Protocol-Version} 头，值是**服务器在 initialize 结果里答的那个版本**
     * （服务器可以回一个它支持的、不同于我们请求的版本）。
     * 不带这个头时，严格实现的服务器直接回 400 —— 表现出来是
     * 「连得上、但工具清单和调用全部失败」，而错误只有一句 "MCP HTTP 400"。
     */
    private JSONObject overHttp(McpConfigStore.Server server, String method, JSONObject params)
            throws Exception {
        if (server.url == null || server.url.trim().isEmpty()) {
            throw new IOException("MCP HTTP URL is empty");
        }
        JSONObject init = post(server, requestEnvelope(METHOD_INITIALIZE, initializeParams()), null, null);
        String sessionId = init == null ? null : init.optString(KEY_SESSION_ID, null);
        String negotiated = init == null ? "" : init.optString(KEY_PROTOCOL_VERSION, PROTOCOL_VERSION);
        post(server, requestEnvelope(METHOD_INITIALIZED, new JSONObject()), sessionId, negotiated);
        JSONObject result = post(server, requestEnvelope(method, params), sessionId, negotiated);
        if (result != null) result.remove(KEY_SESSION_ID);
        return result;
    }

    private JSONObject post(McpConfigStore.Server server, JSONObject body, String sessionId,
                            String protocolVersion)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server.url).openConnection();
        connection.setConnectTimeout((int) TIMEOUT_MS);
        connection.setReadTimeout((int) TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null && !sessionId.isEmpty()) {
            connection.setRequestProperty(HEADER_SESSION_ID, sessionId);
        }
        // initialize 自己不带（协议就是这么写的）：它是协商版本的那一次。
        if (protocolVersion != null && !protocolVersion.isEmpty()) {
            connection.setRequestProperty(HEADER_PROTOCOL_VERSION, protocolVersion);
        }
        for (String name : namesOf(server.headers)) {
            connection.setRequestProperty(name, server.headers.optString(name, ""));
        }

        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream out = new BufferedOutputStream(connection.getOutputStream())) {
            out.write(payload);
            out.flush();
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("MCP HTTP " + status + ": " + readErrorBody(connection));
        }
        // 通知不应答，所以这里必须提前返回一个「没有响应」的表示，
        // 否则会阻塞在读一个永远不来的响应体上。
        if (METHOD_INITIALIZED.equals(body.optString("method"))) return null;

        JSONObject result = parseEnvelope(readBody(connection));
        String returnedSession = connection.getHeaderField(HEADER_SESSION_ID);
        if (returnedSession != null && result != null) result.put(KEY_SESSION_ID, returnedSession);
        return result;
    }

    /**
     * 读响应体。
     *
     * <h3>为什么 SSE 不能 {@code readAll}</h3>
     * Streamable HTTP 允许用 {@code text/event-stream} 作答，而这种流**不保证关闭**：
     * 服务器把事件推完就挂着连接（等后续通知）。早先这里一律读到 EOF，
     * 于是每次工具调用都要干等到读超时（30 秒）才抛 "Read timed out" ——
     * 服务器明明早就答完了。所以 SSE 只读到**第一个事件的结束空行**就收工。
     */
    private static String readBody(HttpURLConnection connection) throws IOException {
        String contentType = connection.getContentType();
        if (contentType != null && contentType.toLowerCase().contains(EVENT_STREAM_TYPE)) {
            return readFirstEvent(connection.getInputStream());
        }
        return readAll(connection.getInputStream());
    }

    /** 读到第一个 SSE 事件的空行；返回拼好的 {@code data:} 内容，交给 {@link #parseEnvelope}。 */
    private static String readFirstEvent(InputStream raw) throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(raw, StandardCharsets.UTF_8), READ_BUFFER_BYTES);
        StringBuilder event = new StringBuilder();
        for (int line = 0; line < MAX_EVENT_LINES; line++) {
            String text = reader.readLine();
            if (text == null) break;
            if (text.isEmpty()) {
                // 空行 = 一个事件结束。还没读到 data 的是心跳行，继续等下一个事件。
                if (event.length() > 0) break;
                continue;
            }
            if (text.startsWith("data:")) {
                if (event.length() > 0) event.append('\n');
                event.append(text.substring(5).trim());
            }
            // event: / id: / retry: 对结果没有影响，忽略。
        }
        return event.toString();
    }

    // ------------------------------------------------------------ 协议报文

    /**
     * initialize 的参数。
     *
     * <p>客户端版本取自构建配置，而不是写字面量：写死一个版本号会在下次改版本时
     * 变成一句假话，而 MCP 服务器常常把它显示在日志与「已连接客户端」列表里。
     */
    private static JSONObject initializeParams() throws Exception {
        return new JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject()
                .put("name", "ZhiCode")
                .put("version", BuildConfig.VERSION_NAME));
    }

    private JSONObject requestEnvelope(String method, JSONObject params) throws Exception {
        return new JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", nextId.getAndIncrement())
            .put("method", method)
            .put("params", params);
    }

    /** stdio 侧写一条消息。通知（{@code withId=false}）不带 id，见协议。 */
    private long write(BufferedOutputStream out, String method, JSONObject params, boolean withId)
            throws IOException {
        try {
            JSONObject envelope = new JSONObject()
                .put("jsonrpc", "2.0").put("method", method).put("params", params);
            long id = withId ? nextId.getAndIncrement() : -1L;
            if (withId) envelope.put("id", id);
            byte[] payload = envelope.toString().getBytes(StandardCharsets.UTF_8);
            out.write(("Content-Length: " + payload.length + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            return id;
        } catch (Exception failure) {
            throw new IOException(failure);
        }
    }

    /**
     * 读一条 {@code Content-Length} 分帧的响应。
     *
     * <p>返回值统一成「result 对象」：协议信封是
     * {@code {"id":…,"result":{…}}}，而调用方要的是里面那个 {@code result}。
     * 遇到 {@code error} 键则抛错 —— 协议层错误不能当成空结果吞掉，
     * 那会让「服务器拒绝了这次调用」变成「服务器返回了空」。
     *
     * <h3>为什么要按 id 挑消息</h3>
     * 服务器可以在任何时候发**通知**（JSON-RPC 里没有 id 的那种：日志、进度、
     * {@code notifications/tools/list_changed}）。早先这里读到一条就当成响应，
     * 于是一条通知会把整次握手打乱：`initialize` 拿到的是通知的内容，
     * 会话 id 拿不到，工具清单变成空的 —— 表现出来是「配置没错但一个工具都没有」。
     * 现在按请求 id 挑，不是自己那条就丢掉继续读（有次数上限，见 [MAX_SKIPPED_MESSAGES]）。
     *
     * @param expectedId 本次请求的 id；{@code < 0} 表示不挑（协议里通知没有响应）
     */
    private JSONObject read(BufferedInputStream in, long expectedId) throws Exception {
        for (int skipped = 0; skipped < MAX_SKIPPED_MESSAGES; skipped++) {
            JSONObject envelope = readMessage(in);
            if (expectedId < 0L) return unwrap(envelope);
            Object id = envelope.opt("id");
            if (id != null && envelope.optLong("id", Long.MIN_VALUE) == expectedId) {
                return unwrap(envelope);
            }
            // 不是我们要的那一条：多半是通知，或上一条请求迟到的响应。
        }
        throw new IOException("MCP response not received for request " + expectedId);
    }

    /** 读一条消息的**信封**（不分帧、不拆 result），见 {@link #read}。 */
    private JSONObject readMessage(BufferedInputStream in) throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int length = -1;
        String line;
        do {
            line = readLine(in, deadline);
            if (line == null) throw new IOException("MCP closed stdout");
            if (line.toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        } while (!line.isEmpty());

        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("invalid MCP content length");
        }
        return new JSONObject(new String(readFully(in, length, deadline), StandardCharsets.UTF_8));
    }

    /**
     * 拆信封：有 {@code error} 就抛，有 {@code result} 就取它，否则返回信封本身。
     *
     * <p>「否则返回信封本身」是为了兼容不回 result 的实现 —— 对它们来说报文里
     * 的内容就是结果。这不是猜测：MCP 规范里有若干通知类响应就是如此。
     */
    private static JSONObject unwrap(JSONObject envelope) throws IOException {
        if (envelope.has("error")) {
            JSONObject error = envelope.optJSONObject("error");
            throw new IOException(error == null ? "MCP error" : error.optString("message", "MCP error"));
        }
        JSONObject result = envelope.optJSONObject("result");
        return result == null ? envelope : result;
    }

    /**
     * 解析 HTTP 响应：可能是裸 JSON，也可能是 SSE。
     *
     * <p>SSE 分支把多个 {@code data:} 行拼起来（协议允许分帧），
     * 并丢掉结束标记 —— 留着它会让后面的 JSON 解析失败。
     */
    private static JSONObject parseEnvelope(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("data:")) text = joinEventData(text);
        return unwrap(new JSONObject(text));
    }

    private static String joinEventData(String payload) {
        StringBuilder joined = new StringBuilder();
        for (String line : payload.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            String value = line.substring(5).trim();
            if ("[DONE]".equals(value)) continue;
            if (joined.length() > 0) joined.append('\n');
            joined.append(value);
        }
        return joined.toString();
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 拼命令行，并把「短名字」解析成绝对路径。
     *
     * <p>查 PATH 这一步 {@link ProcessBuilder} 自己也会做（PATH 已由
     * {@link #applyEnvironment} 设好），这里先解析一遍是为了**能给出原因**：
     * 解析不到时 {@link #describeStartFailure} 就能直接说清
     * 「$PREFIX/bin 里没有这个命令」，而不是让 Java 抛一句 error=2。
     */
    private static List<String> commandLine(McpConfigStore.Server server) {
        List<String> command = new ArrayList<>();
        command.add(resolveCommand(server.command));
        command.addAll(server.args);
        return command;
    }

    private static String resolveCommand(String raw) {
        String command = raw == null ? "" : raw.trim();
        // 带斜杠的一律按路径处理：用户写绝对路径或相对路径都应该原样交给系统。
        if (command.isEmpty() || command.indexOf('/') >= 0) return command;
        File candidate = new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, command);
        return candidate.isFile() && candidate.canExecute() ? candidate.getAbsolutePath() : command;
    }

    /** 启动失败时把「为什么」补全：error=2 本身离原因太远，见 {@link #applyEnvironment}。 */
    private static String describeStartFailure(McpConfigStore.Server server, IOException failure) {
        String command = server.command == null ? "" : server.command.trim();
        StringBuilder message = new StringBuilder("MCP stdio 无法启动：").append(messageOf(failure));
        if (!command.isEmpty() && command.indexOf('/') < 0
            && !new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, command).isFile()) {
            message.append("；在 ").append(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH)
                .append(" 里找不到 ").append(command)
                .append("，请先安装它，或在配置里直接写它的绝对路径");
        }
        return message.toString();
    }

    /**
     * 排空子进程的 stderr，并保留最后一段。
     *
     * <p>它是**必须存在**的，不是"顺手收集日志"：管道缓冲只有 64KB，没人读的话
     * 服务器写满就永久阻塞（见 {@link #overStdio}）。所以即使超出保留上限，
     * 也继续读、只是把多出来的部分丢掉。
     *
     * <p>保留的是**尾部**：真正的错误信息（缺依赖、端口占用、鉴权失败）
     * 总在最后几行，前面全是启动横幅。
     */
    private static final class StderrDrain extends Thread {

        private final InputStream stream;
        private final int capacity;
        private final StringBuilder captured = new StringBuilder();

        StderrDrain(InputStream stream, int capacity) {
            super("mcp-stderr");
            setDaemon(true);
            this.stream = stream;
            this.capacity = capacity;
        }

        @Override public void run() {
            byte[] buffer = new byte[4096];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (Throwable closed) {
                // 进程已经退出/流被关掉：这是正常的收尾路径，不是错误。
            }
        }

        private synchronized void append(String text) {
            captured.append(text);
            if (captured.length() <= capacity) return;
            // 只留尾部（见类注释），多出来的直接丢。
            captured.delete(0, captured.length() - capacity);
        }

        synchronized String tail() {
            String text = captured.toString().trim();
            return text.length() <= STDERR_REPORT_CHARS
                ? text
                : "…" + text.substring(text.length() - STDERR_REPORT_CHARS);
        }

        void finish(long millis) {
            try {
                join(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 把 JSON 对象的键值对写进环境或请求头；空键跳过。 */
    private static void applyPairs(Map<String, String> target, JSONObject pairs) {
        for (String key : namesOf(pairs)) target.put(key, pairs.optString(key, ""));
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 收尾关不掉就算了，它不会污染错误信息。
        }
    }

    private static List<String> namesOf(JSONObject object) {
        List<String> names = new ArrayList<>();
        if (object == null) return names;
        JSONArray keys = object.names();
        if (keys == null) return names;
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i, "");
            if (!key.isEmpty()) names.add(key);
        }
        return names;
    }

    /**
     * 把 MCP 的内容块数组压成一段文本，块之间用换行分隔。
     *
     * <h3>为什么非文本块不能直接跳过</h3>
     * 早先这里只取 {@code text} 字段，其余静默丢弃 —— 于是"截图类"服务器
     * （返回 image 块）在模型眼里是**什么都没返回**，而模型接着就会把
     * "工具没有输出"当成事实继续往下推。丢内容比丢格式严重得多，所以这里给
     * 非文本块留一个可读的占位（图片、资源、以及未知类型都走最后那一支）。
     */
    private static String flattenContent(JSONArray content) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (JSONObject item : JsonItems.of(content)) {
            String text = item.optString("text", "");
            if (text.isEmpty()) text = describeNonText(item);
            if (text.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(text);
        }
        return out.toString();
    }

    /** 非文本内容块的占位说明；认不出来时退回它自己的 JSON。 */
    private static String describeNonText(JSONObject item) {
        String type = item.optString("type", "");
        if ("image".equals(type)) {
            return "[图片内容：" + item.optString("mimeType", "未知类型") + "，本工具只能转述文本]";
        }
        if ("audio".equals(type)) {
            return "[音频内容：" + item.optString("mimeType", "未知类型") + "]";
        }
        if ("resource".equals(type)) {
            JSONObject resource = item.optJSONObject("resource");
            if (resource != null) {
                String inner = resource.optString("text", "");
                return inner.isEmpty() ? "[资源：" + resource.optString("uri", "") + "]" : inner;
            }
            return "[资源内容]";
        }
        if (type.isEmpty()) return "";
        String raw = item.toString();
        return raw.length() <= NON_TEXT_REPORT_CHARS
            ? raw
            : raw.substring(0, NON_TEXT_REPORT_CHARS) + "…";
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String readErrorBody(HttpURLConnection connection) {
        try {
            return readAll(connection.getErrorStream());
        } catch (Exception unreadable) {
            return "";
        }
    }

    /**
     * 读一行（以 {@code \n} 结尾）。
     *
     * <p>{@link BufferedInputStream#available()} 返回 0 不代表流结束，
     * 所以要轮询等待而不是直接返回 —— 直接返回会把「还没到」当成「没有数据」，
     * 于是分帧头读一半就失败。轮询期间响应中断。
     */
    private static String readLine(BufferedInputStream in, long deadline) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (System.currentTimeMillis() < deadline) {
            if (in.available() == 0) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
                continue;
            }
            int next = in.read();
            if (next < 0) return null;
            if (next == '\n') {
                return buffer.toString(StandardCharsets.US_ASCII.name()).replace("\r", "");
            }
            buffer.write(next);
        }
        throw new IOException("MCP response timeout");
    }

    /** 读指定字节数。读不满就是超时 —— 报文不完整时继续解析只会得到错位的 JSON。 */
    private static byte[] readFully(BufferedInputStream in, int length, long deadline) throws IOException {
        byte[] payload = new byte[length];
        int offset = 0;
        while (offset < length && System.currentTimeMillis() < deadline) {
            int read = in.read(payload, offset, length - offset);
            if (read < 0) break;
            offset += read;
        }
        if (offset != length) throw new IOException("MCP response timeout");
        return payload;
    }

    private static String messageOf(Exception failure) {
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }
}
