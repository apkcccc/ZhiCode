package com.termux.app.zhicode.storage;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 「按项目分目录」的那几个路径。
 *
 * <h3>为什么从 {@link SessionStore} 里搬出来</h3>
 * 会话数据已经整棵搬进 {@code ~/.zhicode/sessions.db}，不再按项目分目录存放。
 * 但**计划文本**（{@link PlanStore}）与**任务清单**（{@code TaskStore}）仍然是文件，
 * 它们继续按项目键分目录，并继续需要「规范化项目路径 → 目录名」这套算法。
 *
 * <p>把这几个静态方法留在 {@code SessionStore} 上会让「会话存储」这个类继续背着
 * 一条与它无关的职责，也会让读代码的人以为会话文件还在按项目分目录。
 * 所以算法搬到这里，调用方（PlanStore / TaskStore）只依赖本类。
 *
 * <p>算法本身与之前完全一致 —— 项目键的字符串会写进旧数据的目录名，
 * 改动它等于让已有计划与任务找不到。
 */
public final class ProjectPaths {

    /** 路径键里可读段的长度上限。 */
    private static final int MAX_SLUG_CHARS = 72;

    private ProjectPaths() {}

    /** 按项目分目录的根：{@code ~/.zhicode/projects/}。 */
    public static File projectsDirectory() {
        return new File(TermuxConstants.dataDir(), "projects");
    }

    /** 规范化项目路径：空值落到 Termux HOME；能取规范路径就取（顺带解开符号链接）。 */
    public static String canonicalProject(String projectDirectory) {
        String raw = projectDirectory == null || projectDirectory.trim().isEmpty()
                ? TermuxConstants.TERMUX_HOME_DIR_PATH
                : projectDirectory.trim();
        try {
            return new File(raw).getCanonicalPath();
        } catch (Exception notResolvable) {
            return new File(raw).getAbsolutePath();
        }
    }

    /**
     * 目录名 = 可读段 + 短哈希。
     *
     * <p>只用可读段会撞车（{@code a/b} 与 {@code a_b} 会变成同一个），只用哈希则没人看得懂。
     * 拼接之后同名目录几乎不可能出现，而人还能一眼认出是哪个项目。
     *
     * <p>超长时截<b>尾部</b>而不是头部：路径的末尾通常才是区分度所在。
     */
    public static String projectKey(String projectDirectory) {
        String canonical = canonicalProject(projectDirectory);
        String slug = canonical.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-+", "-");
        if (slug.length() > MAX_SLUG_CHARS) slug = slug.substring(slug.length() - MAX_SLUG_CHARS);
        if (slug.isEmpty()) slug = "project";
        return slug + "-" + shortHash(canonical);
    }

    /**
     * 前 6 字节的 SHA-256，即 12 个十六进制字符。
     *
     * <p>用作路径键的一部分时，碰撞概率远低于实际会遇到的目录数。
     * 拿不到摘要算法时退回 {@code hashCode} —— 质量差一些，但比抛异常好。
     */
    public static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(12);
            for (int i = 0; i < 6; i++) hex.append(String.format(Locale.US, "%02x", digest[i] & 255));
            return hex.toString();
        } catch (Exception unavailable) {
            return String.format(Locale.US, "%012x", (long) value.hashCode() & 0xffffffffffffL);
        }
    }
}
