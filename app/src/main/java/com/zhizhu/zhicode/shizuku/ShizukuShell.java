package com.zhizhu.zhicode.shizuku;

import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 用 Shizuku 以 shell 身份执行命令 —— 等价于 {@code adb shell}，但不需要 Root。
 *
 * <h3>它和 Bash 工具的区别，只有一个：uid</h3>
 * {@code IShizukuService.newProcess} 由 Shizuku 服务端 fork 进程，实际 uid 是
 * shell(2000)；如果用户的 Shizuku 是用 Root 启动的，则可能是 0。
 * 于是 {@code pm / am / settings / cmd / dumpsys / input / screencap} 这些
 * 「只有 shell 才够格」的命令第一次变得可用。
 *
 * <p><b>反过来也成立：</b>shell 身份**进不去**本应用私有目录
 * （{@code $HOME} 是 0700 且 SELinux 隔离），所以这里刻意不把项目目录设成默认 cwd ——
 * 默认值必须是 {@code /}。普通文件读写仍然该走 Bash 工具。
 *
 * <h3>为什么自己写流式读取而不是 {@code ShizukuRemoteProcess}</h3>
 * api 里那个类的构造器是包级私有的，只能由 {@code IRemoteProcess} 构造；
 * 而它的返回值类型是 {@link ParcelFileDescriptor}，我们自己起两个线程读就够了 ——
 * 少一层反射/包访问限制，也顺手拿到「边跑边输出」。
 */
public final class ShizukuShell {

    public static final int DEFAULT_TIMEOUT_MS = 120_000;
    /** 单次调用最多保留这么多字符，防止一条 {@code logcat} 把上下文刷爆。 */
    public static final int MAX_CAPTURE_CHARS = 2_000_000;
    /** 轮询远端进程的间隔。取 250ms：比它短只是空转，比它长会让短命令白等。 */
    private static final long POLL_SLICE_MS = 250L;

    /** 实时输出回调，语义与 {@code TermuxShellExecutor.OutputListener} 一致。 */
    public interface OutputListener {
        void onOutput(String chunk, boolean stderr, long elapsedMs);
    }

    /** 一次执行的结果。字段形状对齐 Termux 侧那个 Result，方便上层复用同一套展示。 */
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;

        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
        }

        /**
         * 拼成一段给人看的文本。
         *
         * <p>超时补一行说明，且**退出码按 124**（与 {@code timeout(1)} 同一个约定）：
         * 只说「超时」不给退出码的话，上层无法把它与「命令自己 exit 1」区分开。
         */
        public String combined() {
            StringBuilder out = new StringBuilder();
            if (!stdout.isEmpty()) out.append(stdout);
            if (!stderr.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                out.append("[stderr]\n").append(stderr);
            }
            if (timedOut) out.append("\n[command timed out]");
            if (out.length() == 0) out.append("(no output)");
            return out.toString();
        }
    }

    private ShizukuShell() { }

    /** Shizuku 已授权且服务在线。 */
    public static boolean ready() {
        try {
            return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 以 shell 身份执行 {@code command}。
     *
     * @param cwd 远端工作目录；空值用 {@code /}（见类注释：shell 进不去应用私有目录）。
     *            只接受绝对路径，相对路径由调用方按自己的项目目录解析。
     * @param timeoutMs 超时后 destroy 远端进程并返回 {@code timedOut=true}、退出码 124。
     */
    public static Result execute(String command, String cwd, int timeoutMs, OutputListener listener)
            throws Exception {
        if (command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("command is empty");
        }
        IShizukuService service = service();
        String dir = cwd == null || cwd.trim().isEmpty() ? "/" : cwd.trim();
        IRemoteProcess process = service.newProcess(new String[]{"sh", "-c", command}, null, dir);
        if (process == null) {
            throw new IllegalStateException("Shizuku 没有返回进程（命令被拒绝或参数非法）");
        }
        long started = System.currentTimeMillis();
        StreamDrain out = null;
        StreamDrain err = null;
        try {
            closeQuietly(process.getOutputStream());
            out = new StreamDrain(process.getInputStream(), false, started, listener);
            err = new StreamDrain(process.getErrorStream(), true, started, listener);
            out.start();
            err.start();
            long deadline = started + Math.max(1, timeoutMs);
            boolean finished = false;
            while (true) {
                if (process.waitForTimeout(POLL_SLICE_MS, TimeUnit.MILLISECONDS.toString())) {
                    finished = true;
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    destroyQuietly(process);
                    throw new InterruptedException("Shizuku command cancelled");
                }
                if (System.currentTimeMillis() >= deadline) break;
            }
            if (!finished) {
                destroyQuietly(process);
                out.joinQuietly(1_500L);
                err.joinQuietly(1_500L);
                return new Result(124, out.text(), err.text(), true);
            }
            int exitCode;
            try {
                exitCode = process.exitValue();
            } catch (Throwable e) {
                exitCode = 1;
            }
            out.joinQuietly(1_500L);
            err.joinQuietly(1_500L);
            return new Result(exitCode, out.text(), err.text(), false);
        } finally {
            if (out == null || err == null) destroyQuietly(process);
            if (out != null) out.close();
            if (err != null) err.close();
        }
    }

    public static Result execute(String command, String cwd, int timeoutMs) throws Exception {
        return execute(command, cwd, timeoutMs, null);
    }

    /**
     * 取远端服务。
     *
     * <p>三步检查的顺序是有意为之：先「服务在不在」，再「我们有没有被授权」，
     * 最后才 asInterface。反过来的话，服务没起来时报的是「授权不足」，
     * 用户会去管理端里翻授权列表 —— 而那里根本没有这一项。
     */
    private static IShizukuService service() {
        try {
            if (!Shizuku.pingBinder()) {
                throw new IllegalStateException("Shizuku 服务未运行：请先打开 Shizuku 并启动服务");
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("ZhiCode 还没有 Shizuku 授权：请在 设置 → Agent 与安全 里申请");
            }
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            if (service == null) throw new IllegalStateException("Shizuku 服务不可用");
            return service;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable e) {
            String message = e.getMessage();
            throw new IllegalStateException("连接 Shizuku 失败：" + (message == null ? e.toString() : message));
        }
    }

    private static void destroyQuietly(IRemoteProcess process) {
        try { process.destroy(); } catch (Throwable ignored) { }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try { if (descriptor != null) descriptor.close(); } catch (Throwable ignored) { }
    }

    /** 逐块读取远端流，边读边回调，并限制累计字符数。 */
    private static final class StreamDrain extends Thread {
        private final ParcelFileDescriptor descriptor;
        private final boolean stderr;
        private final long startedAt;
        private final OutputListener listener;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        StreamDrain(ParcelFileDescriptor descriptor, boolean stderr, long startedAt,
                    OutputListener listener) {
            this.descriptor = descriptor;
            this.stderr = stderr;
            this.startedAt = startedAt;
            this.listener = listener;
            // 守护线程：进程退出时代码可能已经不要这份输出了，别让它拖住 VM 退出。
            setDaemon(true);
        }

        @Override public void run() {
            InputStream in = null;
            try {
                if (descriptor == null) return;
                in = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    if (buffer.size() < MAX_CAPTURE_CHARS) buffer.write(chunk, 0, read);
                    if (listener != null) {
                        listener.onOutput(new String(chunk, 0, read, StandardCharsets.UTF_8), stderr,
                            System.currentTimeMillis() - startedAt);
                    }
                }
            } catch (Throwable ignored) {
                // 管道在 destroy 之后会抛 EBADF/IOException；这就是正常收尾。
            } finally {
                try { if (in != null) in.close(); } catch (Throwable ignored) { }
            }
        }

        String text() { return new String(buffer.toByteArray(), StandardCharsets.UTF_8); }

        void joinQuietly(long millis) {
            try { super.join(millis); } catch (InterruptedException e) { interrupt(); }
        }

        void close() { closeQuietly(descriptor); }
    }
}
