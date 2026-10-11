package com.termux.app.zhicode.agents;

import com.termux.app.zhicode.core.ZhiCodeEngine;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * 一个正在跑（或跑完）的子代理任务。
 *
 * <h3>为什么字段这么杂</h3>
 * 这个对象同时是三种东西的汇合点，刻意没有拆开：
 * <ol>
 *   <li><b>界面要的状态</b>：{@link #status} / {@link #progress} / {@link #output}；</li>
 *   <li><b>调度要的句柄</b>：{@link #future}（取消）、{@link #done}（等待结束）、
 *       {@link #engine}（往里投递后续指令）；</li>
 *   <li><b>恢复要的痕迹</b>：{@link #sessionId} / {@link #worktreePath}。</li>
 * </ol>
 * 拆成三个对象意味着它们之间要有一致的更新顺序，而这里的更新本身就来自
 * 多个线程（工作线程写、界面线程读），合并成一个对象 + volatile 字段更省事。
 *
 * <h3>可见性约定</h3>
 * {@link #output} 与三个 final 字段在构造后就固定；其余可变字段一律 {@code volatile}。
 * {@link #output} 单独说明：它是被工作线程 append、界面线程读的可变对象，
 * 本身不线程安全 —— 累加过程中界面看到的可能是半行，这是**有意接受**的
 * （子代理的输出本来就是逐块追加的文本，多看到半个字不影响使用，
 * 而为它加锁会让每次输出都走一次同步）。
 */
public final class AgentTask {

    public final String id;
    public final String description;
    /** 子代理类型名，对应 {@link AgentDefinition#name}。 */
    public final String agentType;

    /** 累计输出。见类注释里的可见性约定。 */
    public final StringBuilder output = new StringBuilder();
    public final long startedAt = System.currentTimeMillis();

    /** 等待结束用的闩。任务结束时 countDown，等待方用 await。 */
    public final CountDownLatch done = new CountDownLatch(1);

    /** {@code running} / {@code completed} / {@code failed} / {@code cancelled}。 */
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    public volatile String status = STATUS_RUNNING;
    public volatile String progress = "Starting…";
    /** 结束时间；未结束时为 0（界面据此判断要不要显示耗时）。 */
    public volatile long finishedAt;
    public volatile Throwable error;
    public volatile ZhiCodeEngine engine;
    public volatile Future<?> future;
    /** 子代理的会话标识（{@code sessions.db} 里的 id）；尚未创建时为空串。 */
    public volatile String sessionId = "";
    /** 隔离工作树的路径；空串表示没有隔离。 */
    public volatile String worktreePath = "";

    /** 只允许同包的 {@link SubagentManager} 创建：任务的生命周期由它负责。 */
    AgentTask(String id, String description, String agentType) {
        this.id = id;
        this.description = description;
        this.agentType = agentType;
    }

    /**
     * 给界面用的 JSON 快照。
     *
     * <p>键名是界面契约（它是被 Compose 侧读取的，不是内部结构）。
     * {@link #output} 每次都会整段拷进去：这个快照是按需生成的（刷新进度时），
     * 不是高频路径。
     *
     * <p>编码失败（只会因为值里出现了不可编码的东西）时返回已填好的部分，
     * 不抛异常：进度快照失败不该把一个正在正常运行的任务带崩。
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("task_id", id)
                .put("description", description)
                .put("agent_type", agentType)
                .put("status", status)
                .put("progress", progress)
                .put("started_at", startedAt)
                .put("finished_at", finishedAt)
                .put("output", output.toString())
                .put("worktree", worktreePath);
            if (sessionId != null && !sessionId.isEmpty()) json.put("session_id", sessionId);
            if (error != null) json.put("error", String.valueOf(error));
        } catch (Exception ignored) {
            // 见上：快照失败不升级成任务失败。
        }
        return json;
    }
}
