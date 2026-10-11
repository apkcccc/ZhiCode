package com.termux.app.zhicode.storage;

import java.io.File;
import java.io.IOException;

/**
 * 按项目保存计划文本（Markdown）。
 *
 * <p>位置：{@code ~/.zhicode/projects/<项目键>/plans/<workflowId>.md}。
 * 一份计划一个文件，名字就是工作流 id。
 *
 * <h3>写入走 {@link AtomicFiles}</h3>
 * 计划文本会被「提交审批 → 用户阅读」这条链路读到，而写入恰好发生在用户点击
 * ExitPlanMode 的瞬间。读到写了一半的内容意味着用户看到的是一份残缺的计划，
 * 而它还是被批准的那一份 —— 所以这里的原子性不是防御性编程，是必需的。
 */
public final class PlanStore {

    /** workflowId 会成为文件名，长度上限来自常见文件系统的名字限制（255 字节）。 */
    private static final int MAX_WORKFLOW_ID_LENGTH = 128;

    /** 单份计划的大小上限。计划是给人看的文本，到这个量级说明调用方传错了东西。 */
    private static final int MAX_PLAN_BYTES = 4 * 1024 * 1024;

    private static final String PLANS_DIR = "plans";
    private static final String PLAN_SUFFIX = ".md";
    /** 合法的工作流 id：字母数字开头，其余为字母数字与 {@code . _ -}。 */
    private static final String WORKFLOW_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]*";

    private final File directory;

    public PlanStore(String projectDirectory) {
        directory = new File(ProjectPaths.projectsDirectory(),
            ProjectPaths.projectKey(projectDirectory) + File.separator + PLANS_DIR);
    }

    public File getDirectory() {
        return directory;
    }

    public File getPlanFile(String workflowId) {
        return new File(directory, requireWorkflowId(workflowId) + PLAN_SUFFIX);
    }

    /**
     * 原子写入一份计划。
     *
     * @return 发布后的文件
     */
    public synchronized File write(String workflowId, String planText) throws IOException {
        AtomicFiles.ensureDirectory(directory, "计划目录");
        return AtomicFiles.publishText(getPlanFile(workflowId), planText);
    }

    /**
     * 读一份计划。
     *
     * <p>「文件不存在」与「计划是空的」都返回空串：调用方在两种情况下要做的事一样
     * （当作还没有计划），把它们分开只会多一个分支。真正需要区分的是**读失败**，
     * 那个会抛出去。
     */
    public String read(String workflowId) throws IOException {
        File file = getPlanFile(workflowId);
        if (!file.isFile()) return "";
        return new String(AtomicFiles.readBytes(file, MAX_PLAN_BYTES),
            java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean exists(String workflowId) {
        return getPlanFile(workflowId).isFile();
    }

    /** 删除是幂等的：本来就不存在也算成功，调用方不必先查一次。 */
    public synchronized boolean delete(String workflowId) {
        File file = getPlanFile(workflowId);
        return !file.exists() || file.delete();
    }

    /**
     * 校验 workflow id。
     *
     * <p>它会直接拼进文件名，所以必须挡住路径穿越。用白名单而不是黑名单：
     * 只允许字母数字开头、其余为字母数字与 {@code . _ -}。
     * 另外显式排除 {@code .} 与 {@code ..} —— 它们**能**匹配上面那个模式
     * （点号在允许集合里），而它们会让路径指向目录本身或上级。
     */
    private static String requireWorkflowId(String workflowId) {
        String id = workflowId == null ? "" : workflowId.trim();
        boolean pathLike = id.matches(WORKFLOW_ID_PATTERN)
            && !".".equals(id) && !"..".equals(id)
            && id.length() <= MAX_WORKFLOW_ID_LENGTH;
        if (!pathLike) {
            throw new IllegalArgumentException("非法的计划 workflow ID: " + workflowId);
        }
        return id;
    }
}
