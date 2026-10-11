package com.termux.app.zhicode.storage;

import android.util.Log;

/**
 * 内存与「会话在内存里有多大」的诊断日志。
 *
 * <h3>为什么必须有一条</h3>
 *
 * <p>真机上一路排查「打开长会话就崩 / 滑动卡顿」时，唯一能拿到手的只有系统 logcat：
 * ART 会打 GC 行，MIUI 会打掉帧行，但**应用这边一条都没有** ——
 * 因为既有的诊断（{@code ZhiFrameTrace}）只在 debug 构建里启用，
 * 而装到手机上的是 release。于是每一轮都只能靠"堆占用涨了多少"去反推，
 * 反推出来的结论无法证伪。
 *
 * <p>更具体地说，有三件事一直没有数：
 * <ol>
 *   <li>界面侧常驻了多少条对话（分页窗口有没有真的封顶）；</li>
 *   <li>引擎侧的历史有多少条、序列化出来有多大（那才是常驻的大头）；</li>
 *   <li>上面两件事发生的那一刻，堆还剩多少。</li>
 * </ol>
 *
 * <p>所以这里只做一件事：在**关键节点**（打开会话、翻一页、回到最新、恢复引擎历史）
 * 打一行固定格式的日志。它刻意**不打帧**、不做缓冲、不落文件 ——
 * 那些会反过来影响被测对象，而这一行只在用户动作上打一次，开销与一次日志调用同量级。
 *
 * <h3>为什么不放在 {code ZhiFrameTrace} 里</h3>
 *
 * <p>那个对象整体被 {@code BuildConfig.DEBUG} 守住（每个入口都先判 enabled），
 * 而本类要解决的恰恰是"release 里看不见"。两者的启用条件相反，放一起会让
 * 下一个人以为改一下它就能在 release 生效。
 *
 * <h3>tag 与格式</h3>
 *
 * <p>tag 固定 {@value #TAG}，一行一个事件，字段是 {@code key=value} 用空格分隔，
 * 便于 {@code grep} 与人工比对两份日志之间的差值。举例：
 *
 * <pre>
 * ZhiCodeMem transcript n=200 hasOlder=true hasNewer=false
 *            engineMsgs=10412 historyChars=7.3M heap=402/512MB
 * </pre>
 */
public final class ZhiMemory {

    /** 固定 tag：`logcat -s ZhiCodeMem` 就能只看这一条线。 */
    public static final String TAG = "ZhiCodeMem";

    private ZhiMemory() {}

    /**
     * 当前堆占用与上限（MB）。
     *
     * <p>用 {@link Runtime#maxMemory()} 而不是 {@code ActivityManager.getMemoryClass()}：
     * 后者返回的是**没算 largeHeap** 的那个数，而本工程开了 largeHeap，
     * 拿它当分母会得出"用了 160%"这种毫无意义的百分比。
     */
    public static String heap() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            return (used / (1024 * 1024)) + "/" + (runtime.maxMemory() / (1024 * 1024)) + "MB";
        } catch (Throwable ignored) {
            return "?/?";
        }
    }

    /**
     * 一个字符串占多少内存的**紧凑读法**。
     *
     * <p>用它而不是字节数，是因为这一行的目的就是"看一眼就知道多大"：
     * {@code 7654604} 要在脑子里换算，{@code 7.3M} 不用。
     *
     * <p>注意 Java 的 {@code String} 是 2 字节/字符（可能再按编码压缩到 1 字节），
     * 所以这里报的是**字符数**意义上的体量，不假装是精确的堆占用。
     */
    public static String size(long chars) {
        if (chars < 0) return "?";
        if (chars < 1024) return chars + "B";
        if (chars < 1024 * 1024) return (chars / 1024) + "K";
        return round1(chars / (1024.0 * 1024.0)) + "M";
    }

    /**
     * 打一行诊断。
     *
     * <p>永远不会抛：这一行是给人看的，绝不能因为它自己失败而影响被测路径
     * （典型场景是它在 OOM 边缘被调用，而字符串拼接本身也要分配）。
     */
    public static void log(String message) {
        try {
            Log.i(TAG, message + " heap=" + heap());
        } catch (Throwable ignored) {
            // 连日志都打不出来（堆已经耗尽）时保持安静：调用方的主流程比这一行重要。
        }
    }

    private static String round1(double value) {
        long scaled = Math.round(value * 10.0);
        return (scaled / 10) + "." + (scaled % 10);
    }
}
