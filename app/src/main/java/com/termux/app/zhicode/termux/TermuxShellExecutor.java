package com.termux.app.zhicode.termux;

import android.content.Context;

import com.termux.app.zhicode.storage.ZhiMemory;
import com.termux.shared.termux.TermuxConstants;
import com.zhizhu.zhicode.sandbox.SandboxShell;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 在嵌入式 Termux 的 Bionic 用户空间里直接跑命令（不经 proot）。
 *
 * <h3>为什么这么绕：退出码需要一个可信来源</h3>
 * {@link Process#waitFor()} 给出的退出码在两种情况下是错的：命令自己的
 * wrapper 被杀（拿不到真实码）、以及命令启动了后台进程导致父进程先退出。
 * 所以真正的命令被包了一层：写 pid → 跑命令 → 把「随机 token:退出码」写进
 * 一个控制文件 → {@code exit}。控制文件与进程退出两个信号由
 * {@link BashCompletionCoordinator} 裁定，见那里的说明。
 *
 * <h3>{@code setsid} 与进程组</h3>
 * 取消与超时都要终止**整棵进程树**。有 {@code setsid} 时整棵树在一个进程组里，
 * 可以直接对 {@code -pid} 发信号；没有时只能自己遍历 {@code /proc} 拼出树
 * （见 {@link #terminateProcessTree}）。root 模式两者都不可靠 —— 那棵树在
 * root 的命名空间里，必须再用一次 {@code su} 去杀。
 *
 * <h3>为什么必须设置 {@code LD_LIBRARY_PATH}</h3>
 * 内置 Termux 的 ELF **不再**被改写前缀（见 RuntimeInstaller）。它们内嵌的
 * DT_RUNPATH 仍指向不存在的 {@code /data/data/com.termux/files/usr/lib}，
 * 而 DT_RUNPATH 只是提示、会被 {@code LD_LIBRARY_PATH} 覆盖，所以这里**必须**
 * 设置它，否则所有二进制都找不到 libc++、zlib 等。
 *
 * <p>注意这与旧实现相反：旧版把 bootstrap 的 ELF 等长改写成真实路径，因此刻意
 * {@code remove} 掉 {@code LD_LIBRARY_PATH}（旧注释说「forcing LD_LIBRARY_PATH
 * can break apt/dpkg/java subprocesses」）。现在 ELF 保持原样，移除它会让
 * 所有二进制起不来。
 */
public final class TermuxShellExecutor {

    public static final int DEFAULT_TIMEOUT_MS = 120_000;

    /** 单个流最多保留的字符数。两个流各自独立计算。 */
    public static final int MAX_CAPTURE_CHARS = 2_000_000;

    /** 控制文件的轮询间隔。 */
    private static final long CONTROL_POLL_MS = 40L;
    /** 拿到控制文件后，等进程真正退出的宽限期。 */
    private static final long CONTROL_EXIT_GRACE_MS = 250L;
    /** 等 pid 文件出现的宽限期（shell 起来到写 pid 之间的时间）。 */
    private static final long PID_FILE_GRACE_MS = 300L;
    /** 收集线程的 join 超时。 */
    private static final long THREAD_JOIN_MS = 1500L;
    /** 主动等待循环的单次步长，同时也是心跳间隔。 */
    private static final long WAIT_STEP_MS = 500L;
    /** 终止时的宽限期：先 TERM，等一等，再 KILL。 */
    private static final long TERM_GRACE_MS = 180L;
    private static final long TERM_GRACE_SHORT_MS = 120L;
    private static final long ROOT_KILL_TIMEOUT_MS = 2000L;

    private static final int EXIT_TIMED_OUT = 124;
    /** 已有包管理任务在跑。借用 sysexits 的 EX_TEMPFAIL。 */
    private static final int EXIT_PACKAGE_BUSY = 75;
    /** root 模式下 cwd 进不去。SYSEXITS 的 EX_OSFILE。 */
    private static final int EXIT_BAD_CWD = 72;

    private static final String TRUNCATION_MARKER = "\n…output truncated…";
    private static final String STDERR_HEADER = "[stderr]\n";
    private static final String NO_OUTPUT = "(no output)";
    private static final String TIMEOUT_MARKER = "\n[command timed out]";

    /**
     * 全局的包管理事务锁。
     *
     * <p>apt/dpkg 在整机范围内只有一把锁。两个并发的 apt 都会失败，
     * 而失败信息（"Could not get lock"）看起来像权限问题。所以在**启动之前**
     * 就用一把进程内的锁挡住：第二个调用直接返回「已有任务在跑」。
     * 这是静态的，因为同一个应用里可能有多个执行器实例（工具、终端、子代理）。
     */
    private static final ReentrantLock PACKAGE_TRANSACTION = new ReentrantLock();

    /** 命令行输出的实时投递。 */
    public interface OutputListener {
        /** chunk 为空表示这是一次心跳；stderr 表示该片段来自标准错误。 */
        void onOutput(String chunk, boolean stderr, long elapsedMs);
    }

    /** 一次执行的结果。 */
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
         * 合并成给模型看的一段文本。
         *
         * <p>标准错误前加一个 {@code [stderr]} 标题：模型常常把混在一起的输出
         * 当成全部来自标准输出，于是把一行警告当成命令的答案。
         *
         * <p>两者都空时给 {@code (no output)} 而不是空串：屏幕上「什么都没有」
         * 与「工具没跑」看起来一样。
         *
         * <p>容量**必须先算出来**。两个流各自的上限是 {@link #MAX_CAPTURE_CHARS}
         * （每个 2M 字符），不预分配的话 StringBuilder 会一路翻倍扩容，
         * 每次扩容都要把已有内容整段复制一遍 —— 合并一份 4M 字符的输出会额外
         * 产生几十兆的临时垃圾，而这正发生在「一条大输出的命令刚跑完」的时刻。
         */
        public String combined() {
            int expected = stdout.length() + stderr.length() + STDERR_HEADER.length() + 16;
            StringBuilder out = new StringBuilder(Math.max(16, expected));
            if (!stdout.isEmpty()) out.append(stdout);
            if (!stderr.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                out.append(STDERR_HEADER).append(stderr);
            }
            if (timedOut) out.append(TIMEOUT_MARKER);
            if (out.length() == 0) out.append(NO_OUTPUT);
            return out.toString();
        }
    }

    private final Context context;

    public TermuxShellExecutor(Context context) {
        this.context = context.getApplicationContext();
    }

    public Result execute(String command, String cwd, int timeoutMs) throws Exception {
        return execute(command, cwd, timeoutMs, null);
    }

    public Result execute(String command, String cwd, int timeoutMs, OutputListener listener)
            throws Exception {
        return run(command, cwd, timeoutMs, listener, false);
    }

    /** 经 Magisk/KernelSU 的 su 执行。调用方必须先取得用户的显式授权。 */
    public Result executeAsRoot(String command, String cwd, int timeoutMs) throws Exception {
        return executeAsRoot(command, cwd, timeoutMs, null);
    }

    public Result executeAsRoot(String command, String cwd, int timeoutMs, OutputListener listener)
            throws Exception {
        return run(command, cwd, timeoutMs, listener, true);
    }

    // ================================================================== 主体

    private Result run(String command, String cwd, int timeoutMs, OutputListener listener, boolean asRoot)
            throws Exception {
        requireRuntimeInstalled();
        String requestedWorking = resolveRequestedWorking(cwd, asRoot);
        // Java 无法在 su 启动之前 chdir 到只有 root 能进的目录，所以 root 模式
        // 用 HOME 作为 ProcessBuilder 的工作目录，真正的 cd 交给 shell 去做。
        File working = asRoot ? new File(TermuxConstants.TERMUX_HOME_DIR_PATH) : new File(requestedWorking);

        String rawCommand = command == null ? "" : command;
        boolean packageCommand = !asRoot && isPackageCommand(rawCommand.toLowerCase(Locale.US));
        boolean packageLockHeld = false;
        if (packageCommand) {
            packageLockHeld = PACKAGE_TRANSACTION.tryLock();
            if (!packageLockHeld) {
                return new Result(EXIT_PACKAGE_BUSY, "",
                    "[ZhiCode] 已有 ZhiCode 包管理任务正在运行。不会再启动第二个 apt/pkg/dpkg，"
                        + "请等待当前任务完成或先停止它。\n", false);
            }
            cleanupOrphanedPackageManagers(listener);
        }

        try {
            return runLocked(rawCommand, requestedWorking, working, timeoutMs, listener,
                asRoot, packageCommand);
        } finally {
            if (packageLockHeld) PACKAGE_TRANSACTION.unlock();
        }
    }

    /**
     * 真正的执行。所有可释放的资源都在这里创建与关闭，且**每条退出路径**都走同一个
     * cleanup —— 取消、超时、异常都不得留下活着的子进程。
     */
    private Result runLocked(String rawCommand, String requestedWorking, File working, int timeoutMs,
                             OutputListener listener, boolean asRoot, boolean packageCommand)
            throws Exception {
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        String executionId = android.os.Process.myPid() + "-" + System.nanoTime()
            + "-" + UUID.randomUUID();
        File pidFile = temporaryFile(prefix, (asRoot ? "root" : "exec") + "-" + executionId + ".pid");
        File controlFile = temporaryFile(prefix, "complete-" + executionId + ".status");
        String controlToken = UUID.randomUUID().toString().replace("-", "");
        long startedAt = System.currentTimeMillis();

        Process process = null;
        Capture stdout = null;
        Capture stderr = null;
        Thread outThread = null;
        Thread errThread = null;
        Thread waiter = null;
        Thread controlThread = null;
        ControlWatcher controlWatcher = null;
        BashCompletionCoordinator completion = new BashCompletionCoordinator();
        // 只在 catch(InterruptedException) 里置位；它让 finally 知道「已经播报过取消」，
        // 不必再补一句“正在清理”。
        boolean interrupted = false;
        boolean terminationRequested = false;
        boolean isolateProcessGroup = false;
        // 见 catch 块：收尾期间清掉中断位，跑完在 finally 末尾还回去。
        boolean deferredInterrupt = false;

        try {
            File tempDirectory = new File(prefix + "/tmp");
            if (!tempDirectory.isDirectory() && !tempDirectory.mkdirs() && !tempDirectory.isDirectory()) {
                throw new IllegalStateException("Cannot create Termux temporary directory: " + tempDirectory);
            }
            createPrivateControlFile(pidFile);
            createPrivateControlFile(controlFile);

            String shellPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            File setsid = new File(prefix + "/bin/setsid");
            isolateProcessGroup = setsid.isFile() && setsid.canExecute();

            ProcessBuilder builder = buildProcess(
                rawCommand, requestedWorking, prefix, shellPath, setsid, working,
                asRoot, isolateProcessGroup, pidFile, controlFile, controlToken, packageCommand);
            process = builder.start();

            stdout = new Capture(process.getInputStream(), false, listener, startedAt);
            stderr = new Capture(process.getErrorStream(), true, listener, startedAt);
            outThread = start(stdout, "zhi-bash-stdout");
            errThread = start(stderr, "zhi-bash-stderr");
            waiter = startWaiter(process, completion);
            controlWatcher = new ControlWatcher(controlFile, controlToken, completion);
            controlThread = start(controlWatcher, "zhi-bash-control");

            boolean finished = awaitCompletion(completion, listener, startedAt, timeoutMs);
            boolean timedOut = !finished;

            if (timedOut) {
                terminationRequested = true;
                terminateAndConfirm(asRoot, isolateProcessGroup, pidFile, process, completion,
                    listener, "命令超时，正在停止 Bash 子进程…");
            } else if (completion.source() == BashCompletionCoordinator.Source.CONTROL
                    && !completion.awaitProcessExit(CONTROL_EXIT_GRACE_MS)) {
                // wrapper 报告完了、进程却还在：说明它留下了后台子进程。
                terminationRequested = true;
                terminateAndConfirm(asRoot, isolateProcessGroup, pidFile, process, completion,
                    listener, "命令已完成，正在清理遗留 Bash 进程…");
            }

            stopCaptures(stdout, stderr, process, controlWatcher, controlThread, outThread, errThread, waiter);
            heartbeat(listener, startedAt);

            if (!timedOut && !completion.hasExitCode()) {
                throw new IllegalStateException("Bash ended without a trusted exit status");
            }
            int resultExit = timedOut ? EXIT_TIMED_OUT : completion.exitCode();
            return new Result(resultExit, valueOf(stdout), valueOf(stderr), timedOut);
        } catch (InterruptedException cancelled) {
            interrupted = true;
            terminationRequested = true;
            /*
             * ⚠️ 收尾之前**先清掉中断位**，收尾全部跑完再还回去。
             *
             * 取消其实是**两次**中断：`ZhiCodeEngine.cancel()` 先 `Future.cancel(true)`
             * 再 `worker.interrupt()`。第一次被 awaitCompletion 消费掉（标志随之清除），
             * 第二次可能正好落在下面这段收尾里 —— 而收尾要轮询 pid 文件、要 join 线程、
             * 要 sleep。带着中断位的这些调用**每一个都会立刻抛**：
             *   · pid 读不到 → 终止从"杀整个进程组"降级成"只 SIGTERM 直接子进程"，
             *     而那个直接子进程是 setsid，它一死，真正的命令就在自己的会话里活下来；
             *   · join 全部变成空操作 → 连"它有没有退出"都不再确认。
             *
             * 这就是"按下停止、工具却一直不停，最后只能把应用杀掉"的来源。
             * 收尾必须完整跑一遍，所以这里清掉标志；跑完在 finally 末尾还回去，
             * 让上层看到的仍然是"被取消"。
             */
            deferredInterrupt = Thread.interrupted();
            terminateAndConfirm(asRoot, isolateProcessGroup, pidFile, process, completion,
                listener, "任务已取消，正在停止 Bash 子进程…");
            throw cancelled;
        } finally {
            // 任何异常/取消路径都不能让 apt/dpkg 活过 Bash 父进程。
            if (process != null && !completion.processExited()) {
                if (!terminationRequested) {
                    terminateAndConfirm(asRoot, isolateProcessGroup, pidFile, process, completion,
                        listener, interrupted ? null : "正在清理 Bash 子进程…");
                } else if (!awaitExitQuietly(completion, CONTROL_EXIT_GRACE_MS)) {
                    // 取消/超时那条路径已经杀过一次，可它**还活着**。
                    // 旧实现这里什么都不做（`terminationRequested` 已经为真），
                    // 于是那唯一一次失败就永久生效 —— 命令跑到自己的超时为止。
                    // 现在补一次强制终止，并留下日志。
                    forceKill(asRoot, isolateProcessGroup, pidFile, process, completion, listener);
                }
            }
            closeProcessStreams(process);
            if (stdout != null) stdout.close();
            if (stderr != null) stderr.close();
            if (controlWatcher != null) controlWatcher.stop();
            if (controlThread != null) controlThread.interrupt();
            joinThread(outThread, CONTROL_EXIT_GRACE_MS);
            joinThread(errThread, CONTROL_EXIT_GRACE_MS);
            joinThread(controlThread, CONTROL_EXIT_GRACE_MS);
            joinThread(waiter, CONTROL_EXIT_GRACE_MS);
            deleteQuietly(pidFile);
            deleteQuietly(controlFile);
            // 见 catch 块：收尾期间中断位是清掉的，跑完再还回去。
            if (deferredInterrupt) Thread.currentThread().interrupt();
        }
    }

    /**
     * 等待完成，期间投递心跳。
     *
     * <p>心跳不能省：长命令（下载、编译）可能几分钟不产生输出，而界面按
     * 「最后一次收到东西的时间」判断工具是不是卡住了。没有心跳的表现为
     * 「进度条停住」，用户会去取消一个正常运行的命令。
     *
     * <p>{@link InterruptedException} 不在这里吞：取消就是靠中断这个线程实现的，
     * 吞掉它会让「取消」要等到命令自己跑完才生效。
     */
    private static boolean awaitCompletion(BashCompletionCoordinator completion,
                                           OutputListener listener, long startedAt, int timeoutMs)
            throws InterruptedException {
        long deadline = startedAt + Math.max(1, timeoutMs);
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) return false;
            if (completion.await(Math.min(WAIT_STEP_MS, remaining))) return true;
            heartbeat(listener, startedAt);
        }
    }

    private static void heartbeat(OutputListener listener, long startedAt) {
        if (listener == null) return;
        try {
            listener.onOutput("", false, System.currentTimeMillis() - startedAt);
        } catch (Throwable ignored) {
            // 监听方（界面）出错不该影响命令本身。
        }
    }

    // ================================================================ 命令行

    /**
     * 组装要执行的进程。
     *
     * <p>三条分支的差别只在「谁来做 cd、谁来隔离进程组」：普通模式让
     * ProcessBuilder 设工作目录并（可能）用 setsid；root 模式必须把 cd 写进
     * shell 命令里，因为 su 之后的进程不在我们的目录上下文里。
     */
    private ProcessBuilder buildProcess(String rawCommand, String requestedWorking, String prefix,
                                        String shellPath, File setsid, File working, boolean asRoot,
                                        boolean isolateProcessGroup, File pidFile, File controlFile,
                                        String controlToken, boolean packageCommand) {
        // 命令本体：先写 pid，跑命令，再把「token:退出码」写进控制文件。
        String commandShell = quote(shellPath) + " -lc " + quote(rawCommand);
        String wrapped = "umask 077;"
            + " printf '%s\\n' $$ > " + quote(pidFile.getAbsolutePath()) + ";"
            + " " + commandShell + ";"
            + " zhicode_exit=$?;"
            + " printf '%s:%s\\n' " + quote(controlToken) + " \"$zhicode_exit\" > "
            + quote(controlFile.getAbsolutePath()) + ";"
            + " exit \"$zhicode_exit\"";

        ProcessBuilder builder;
        if (asRoot) {
            // root 模式下 cd 必须由 shell 自己做：su 之后进程的 cwd 是 HOME，
            // 而用户要的工作目录可能只有 root 能进。
            String launcher = "exec "
                + (isolateProcessGroup ? quote(setsid.getAbsolutePath()) + " --wait " : "")
                + quote(shellPath) + " -lc " + quote(wrapped);
            String rootWrapped = "umask 077; cd " + quote(requestedWorking)
                + " || { echo 'Root cwd does not exist or is inaccessible' >&2; exit " + EXIT_BAD_CWD + "; }; "
                + rootEnvironmentExports(prefix) + launcher;
            builder = new ProcessBuilder(findSuBinary(), "-c", rootWrapped);
        } else if (isolateProcessGroup) {
            builder = new ProcessBuilder(setsid.getAbsolutePath(), "--wait", shellPath, "-lc", wrapped);
        } else {
            builder = new ProcessBuilder(shellPath, "-lc", wrapped);
        }
        builder.directory(working);
        applyEnvironment(builder.environment(), prefix, requestedWorking, packageCommand);
        return builder;
    }

    /**
     * 交给 su 的那一份环境变量。
     *
     * <p>只带最少的一组：root 的 shell 里不该继承我们进程的全部环境
     * （那会带进去一堆 Android 特有的变量）。{@code PATH} 显式给出
     * 系统目录，因为 root 的默认 PATH 里没有 {@code $PREFIX/bin}。
     */
    private static String rootEnvironmentExports(String prefix) {
        return "export HOME=" + quote(TermuxConstants.TERMUX_HOME_DIR_PATH)
            + " PREFIX=" + quote(prefix)
            + " TMPDIR=" + quote(prefix + "/tmp")
            + " PATH=" + quote(prefix + "/bin:/system/bin:/system/xbin")
            + " LANG=en_US.UTF-8 TERM=xterm-256color COLORTERM=truecolor"
            + " SHELL=" + quote(prefix + "/bin/bash")
            + " ZHICODE_APP=1 ZHICODE_TOOL_EXECUTOR=1 ZHICODE_ROOT_EXECUTOR=1; ";
    }

    /**
     * 设置子进程环境。
     *
     * <p>分四组：路径与库定位、终端与语言、Termux 自身的身份变量（保证
     * {@code $PREFIX} 相关的脚本行为正确）、以及只针对包管理命令的两个 debconf 开关。
     *
     * <p>{@code TERMUX_APP__LEGACY_DATA_DIR} 指向当前数据目录而不是真的「旧目录」：
     * 内置运行时的路径已经就是当前目录，某些脚本会读这个变量做兼容判断，
     * 指向一个不存在的路径会让它们走上错误的兼容分支。
     */
    private void applyEnvironment(Map<String, String> env, String prefix, String requestedWorking,
                                  boolean packageCommand) {
        // 路径与库定位。LD_LIBRARY_PATH 的原因见类注释。
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PREFIX", prefix);
        env.put("TMPDIR", prefix + "/tmp");
        env.put("PATH", prefix + "/bin");
        env.put("LD_LIBRARY_PATH", prefix + "/lib");
        env.put("PWD", requestedWorking);
        env.put("SHELL", prefix + "/bin/bash");

        // 终端与语言。
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        // 让构建工具输出确定性的纯日志。命令本身是普通 bash，可以自己覆盖。
        env.put("CI", "true");

        // 沙箱桥接与运行时的自我标识。
        env.put("ZHICODE_APP", "1");
        env.put("ZHICODE_SANDBOX_BRIDGE_DIR", SandboxShell.bridgeDir(context));
        env.put("ZHICODE_APK_PATH", context.getApplicationInfo().sourceDir);
        SandboxShell.ensureCliInstalled(context);
        env.put("ZHICODE_TOOL_EXECUTOR", "1");

        // Termux 自身的身份变量：包管理脚本与 termux-* 工具会读它们。
        env.put("TERMUX_VERSION", "0.118.3");
        env.put("TERMUX_APP__PACKAGE_NAME", TermuxConstants.TERMUX_PACKAGE_NAME);
        env.put("TERMUX_APP__PACKAGE_MANAGER", "apt");
        env.put("TERMUX_APP__PACKAGE_VARIANT", "apt-android-7");
        env.put("TERMUX_APP__FILES_DIR", TermuxConstants.TERMUX_FILES_DIR_PATH);
        env.put("TERMUX_APP__DATA_DIR", TermuxConstants.TERMUX_DATA_DIR_PATH);
        env.put("TERMUX_APP__LEGACY_DATA_DIR", TermuxConstants.TERMUX_DATA_DIR_PATH);
        env.put("TERMUX_APP__PID", Integer.toString(android.os.Process.myPid()));
        env.put("TERMUX_APP__UID", Integer.toString(android.os.Process.myUid()));
        env.put("TERMUX_APP__TARGET_SDK", "28");
        env.put("TERMUX_MAIN_PACKAGE_FORMAT", "debian");
        env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
        env.put("TERMUX_APK_RELEASE", "ZHICODE");
        env.put("TERMUX__HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("TERMUX__PREFIX", prefix);
        env.put("TERMUX__ROOTFS_DIR", TermuxConstants.TERMUX_FILES_DIR_PATH);
        env.put("TERMUX__ROOTFS", TermuxConstants.TERMUX_FILES_DIR_PATH);

        if (packageCommand) {
            // Agent 发起的安装绝不能在不可见的 debconf 提示上停住。
            // 可见的交互式终端**刻意**不设这两个变量，那里的提示是用户要看的。
            env.put("DEBIAN_FRONTEND", "noninteractive");
            env.put("APT_LISTCHANGES_FRONTEND", "none");
        }
    }

    // ================================================================ 准备与校验

    private static void requireRuntimeInstalled() {
        if (!new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash").isFile()) {
            throw new IllegalStateException("Embedded Termux runtime is not installed yet");
        }
    }

    /**
     * 校验并要求工作目录存在（root 模式除外）。
     *
     * <p>root 模式跳过这个检查是必须的：用户可能要在 {@code /data/adb} 这类
     * 只有 root 能进的目录里执行，而本进程连 {@code isDirectory()} 都问不出结果。
     */
    private static String resolveRequestedWorking(String cwd, boolean asRoot) {
        String requested = cwd == null || cwd.isEmpty() ? TermuxConstants.TERMUX_HOME_DIR_PATH : cwd;
        if (!asRoot && !new File(requested).isDirectory()) {
            throw new IllegalArgumentException("Working directory does not exist: " + requested);
        }
        return requested;
    }

    /** 与 {@code BashTool} 的判定同源：这里再判一次是因为终端与子代理也走这条路径。 */
    private static boolean isPackageCommand(String lowerCommand) {
        return lowerCommand.contains("pkg ") || lowerCommand.startsWith("pkg")
            || lowerCommand.contains("apt ") || lowerCommand.startsWith("apt ")
            || lowerCommand.contains("apt-get ") || lowerCommand.startsWith("apt-get")
            || lowerCommand.contains("dpkg ") || lowerCommand.startsWith("dpkg");
    }

    /**
     * {@code $PREFIX/tmp} 下的临时文件。
     *
     * <p><b>这里曾经有个真 bug：</b>原来的代码写的是
     * {@code "/tmp/${TermuxConstants.BRAND_SLUG}-exec-..."} —— 那是 Java 字符串
     * 字面量，{@code ${...}} **不会**被插值，于是文件名里真的带着
     * {@code ${TermuxConstants.BRAND_SLUG}} 这 28 个字符。
     * 「加品牌前缀以免与真正的 Termux 冲突」这个意图从未生效。
     * 现在直接拼 {@link TermuxConstants#BRAND_SLUG} 的值。
     */
    private static File temporaryFile(String prefix, String suffix) {
        return new File(prefix + "/tmp/" + TermuxConstants.BRAND_SLUG + "-" + suffix);
    }

    /**
     * 建一个「只有本应用可读写」的空文件。
     *
     * <p>两步设置权限不是冗余：{@code setReadable(true, true)} 只改 owner 位，
     * 而新文件的默认权限来自 umask，可能对 group/other 也可读。
     * 所以先设 owner 可读写、再显式取消 group/other 的全部权限、最后再确认一次
     * owner 位没被上一步影响。控制文件里带着随机 token，虽然泄露不致命，
     * 但让同 UID 之外的进程能改它就能伪造一个退出码。
     */
    private static void createPrivateControlFile(File file) throws Exception {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Cannot replace control file: " + file);
        }
        try (FileOutputStream ignored = new FileOutputStream(file)) {
            // 只为创建文件。
        }
        if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
            throw new IllegalStateException("Cannot protect control file: " + file);
        }
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        if (!file.setReadable(true, true) || !file.setWritable(true, true)) {
            throw new IllegalStateException("Cannot protect control file: " + file);
        }
    }

    // ================================================================ 等待与清理

    private static Thread start(Runnable body, String name) {
        Thread thread = new Thread(body, name);
        thread.start();
        return thread;
    }

    /**
     * 等进程退出的守护线程。
     *
     * <p>单独的线程是必需的：主线程要能超时放弃，而 {@link Process#waitFor()}
     * 没有超时版本。
     */
    private static Thread startWaiter(Process process, BashCompletionCoordinator completion) {
        return start(() -> {
            try {
                completion.publishProcessExit(process.waitFor());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "zhi-bash-waiter");
    }

    private static void stopCaptures(Capture stdout, Capture stderr, Process process,
                                     ControlWatcher controlWatcher, Thread controlThread,
                                     Thread outThread, Thread errThread, Thread waiter) {
        if (stdout != null) stdout.close();
        if (stderr != null) stderr.close();
        closeProcessStreams(process);
        if (controlWatcher != null) controlWatcher.stop();
        if (controlThread != null) controlThread.interrupt();
        joinThread(outThread, THREAD_JOIN_MS);
        joinThread(errThread, THREAD_JOIN_MS);
        joinThread(controlThread, CONTROL_EXIT_GRACE_MS);
        joinThread(waiter, THREAD_JOIN_MS);
        if (waiter != null && waiter.isAlive()) {
            waiter.interrupt();
            joinThread(waiter, CONTROL_EXIT_GRACE_MS);
        }
    }

    private static String valueOf(Capture capture) {
        return capture == null ? "" : capture.value();
    }

    private static void deleteQuietly(File file) {
        try {
            file.delete();
        } catch (Throwable ignored) {
            // 清理失败不影响结果。
        }
    }

    private static void closeProcessStreams(Process process) {
        if (process == null) return;
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    private static void joinThread(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) return;
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            thread.interrupt();
            Thread.currentThread().interrupt();
        }
    }

    /** 轮询控制文件，把 wrapper 报告的退出码交给协调器。 */
    private static final class ControlWatcher implements Runnable {
        private final File controlFile;
        private final String token;
        private final BashCompletionCoordinator completion;
        private volatile boolean stopped;

        ControlWatcher(File controlFile, String token, BashCompletionCoordinator completion) {
            this.controlFile = controlFile;
            this.token = token;
            this.completion = completion;
        }

        void stop() {
            stopped = true;
        }

        @Override public void run() {
            while (!stopped && !completion.hasExitCode()) {
                if (controlFile.length() > 0L) {
                    Integer code = BashCompletionCoordinator.parseControlLine(readLine(), token);
                    if (code != null) {
                        completion.publishControl(code.intValue());
                        return;
                    }
                }
                try {
                    Thread.sleep(CONTROL_POLL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private String readLine() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(controlFile), StandardCharsets.UTF_8))) {
                return reader.readLine();
            } catch (Throwable unreadable) {
                return null;
            }
        }
    }

    /** 等 pid 文件里出现一个有效的 pid（或到点放弃）。 */
    /**
     * 读 pid 文件，短暂等待它出现。
     *
     * <h3>为什么中断**不能**让它放弃</h3>
     *
     * <p>这个 pid 是"能不能杀掉整棵进程树"的唯一依据：拿到它，{@link #terminateExecution}
     * 就能用负 pid 杀整个进程组（{@link #terminateProcessGroup}）；拿不到，
     * 就只剩 {@code Process.destroy()} —— 而那只会 SIGTERM **直接子进程**。
     * 而直接子进程是 {@code setsid}（见 {@link #buildProcess}）：
     * 它一死，真正在跑的 {@code apt} / {@code gradle} 就在自己的会话里活下来了，
     * 谁也不会再去碰它。
     *
     * <p>所以这里遇到中断**只记下、不返回 -1**：取消路径本身就要靠这次读到的 pid
     * 才能把命令干掉，因为一个"顺手设置的中断位"而降低终止强度，等于让取消失效。
     * 中断位在返回前还回去，调用方看到的仍然是"被中断"。
     */
    private static int readPidWithGrace(File file) {
        long deadline = System.currentTimeMillis() + PID_FILE_GRACE_MS;
        boolean restoreInterrupt = false;
        int pid;
        while (true) {
            pid = readPid(file);
            if (pid > 1) break;
            if (System.currentTimeMillis() >= deadline) break;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                // 不放弃读 pid：清掉标志继续轮询，退出前再还回去。
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
        return pid;
    }

    private static int readPid(File file) {
        if (file == null || !file.isFile()) return -1;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return Integer.parseInt(reader.readLine().trim());
        } catch (Throwable unreadable) {
            return -1;
        }
    }

    // ================================================================ 进程树

    /** {@code /proc/<pid>/status} 里我们关心的三项。 */
    private static final class ProcInfo {
        int pid;
        int ppid;
        int uid;
        String name = "";
    }

    /**
     * 列出同 UID 的全部进程。
     *
     * <p>只列同 UID 的：不同 UID 的进程我们既没权限杀、也不该杀。
     * 这是 {@code /proc} 扫描里唯一的安全边界，所以 uid 的解析失败
     * 会把整条记录丢掉（而不是默认成「属于我们」）。
     */
    private static List<ProcInfo> listOwnProcesses() {
        List<ProcInfo> own = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return own;
        int myUid = android.os.Process.myUid();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            ProcInfo info = readProcInfo(entry);
            if (info != null && info.uid == myUid) own.add(info);
        }
        return own;
    }

    private static ProcInfo readProcInfo(File directory) {
        ProcInfo info = new ProcInfo();
        try {
            info.pid = Integer.parseInt(directory.getName());
        } catch (Throwable invalid) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(directory, "status")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Name:")) info.name = line.substring(5).trim();
                else if (line.startsWith("PPid:")) info.ppid = Integer.parseInt(line.substring(5).trim());
                else if (line.startsWith("Uid:")) {
                    // Uid 行有四个值（real/effective/saved/fs），取第一个。
                    info.uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                }
            }
        } catch (Throwable unreadable) {
            return null;
        }
        return info;
    }

    /**
     * 释放上一次取消后遗留的 dpkg 锁。
     *
     * <p>旧版本取消 Agent 任务时只销毁父 bash，子进程活了下来并占着 dpkg 锁 ——
     * 之后任何安装都会失败，而错误信息是「Could not get lock」，看不出原因。
     *
     * <p>只回收**孤儿**包管理器进程（{@code PPid == 1}）：终端里正在跑的 apt
     * 的父进程是那个终端 shell，不会被这里杀掉。
     */
    public static void cleanupOrphanedPackageManagers() {
        cleanupOrphanedPackageManagers(null);
    }

    private static void cleanupOrphanedPackageManagers(OutputListener listener) {
        for (ProcInfo process : listOwnProcesses()) {
            if (process.ppid != 1 || !isPackageProcessName(process.name)) continue;
            if (process.pid == android.os.Process.myPid()) continue;
            notify(listener, "\n[ZhiCode] 检测到上次取消后遗留的 " + process.name
                + " (PID " + process.pid + ")，正在释放 dpkg 锁…\n");
            signal(process.pid, android.system.OsConstants.SIGTERM);
            sleepQuietly(TERM_GRACE_SHORT_MS);
            if (new File("/proc/" + process.pid).exists()) {
                signal(process.pid, android.system.OsConstants.SIGKILL);
            }
        }
    }

    private static boolean isPackageProcessName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        return lower.equals("apt") || lower.equals("apt-get") || lower.equals("dpkg")
            || lower.equals("dpkg-deb") || lower.equals("pkg");
    }

    /**
     * 终止一棵进程树。
     *
     * <p>顺序是「先 TERM 整棵树、等一等、再 KILL 还活着的」。反过来（直接 KILL）
     * 会让 dpkg 来不及释放锁、bash 来不及写自己的退出状态。全树先 TERM 再全树 KILL，
     * 而不是逐个「TERM 一个等一个」：后者在深树上会累积很多个宽限期。
     */
    private static void terminateProcessTree(int rootPid, Process fallback, OutputListener listener,
                                             String note) {
        notify(listener, note == null ? null : "\n[ZhiCode] " + note + "\n");
        if (rootPid <= 1) {
            destroyQuietly(fallback);
            return;
        }
        Map<Integer, List<Integer>> children = childrenByParent(listOwnProcesses());
        List<Integer> order = new ArrayList<>();
        collectDescendants(rootPid, children, new HashSet<>(), order);
        // 后代在前、根在后：先断开子进程，父进程才不会在退出前又拉起一个。
        order.add(rootPid);

        signalAll(order, android.system.OsConstants.SIGTERM);
        sleepQuietly(TERM_GRACE_MS);
        for (int pid : order) {
            if (pid == android.os.Process.myPid()) continue;
            if (new File("/proc/" + pid).exists()) signal(pid, android.system.OsConstants.SIGKILL);
        }
        destroyQuietly(fallback);
    }

    private static void terminateExecution(boolean asRoot, boolean processGroup, int pid,
                                           Process fallback, OutputListener listener, String note) {
        notify(listener, note == null ? null : "\n[ZhiCode] " + note + "\n");
        if (processGroup && pid > 1) {
            if (asRoot) terminateRootProcessGroup(pid);
            else terminateProcessGroup(pid);
        }
        if (!asRoot) {
            terminateProcessTree(pid, fallback, null, null);
            return;
        }
        // root 模式下那棵树在 root 的命名空间里，只能再用一次 su 去杀。
        if (pid > 1) killRootProcessTree(pid);
        destroyQuietly(fallback);
    }

    /**
     * 终止并**确认**命令真的死了；第一次没杀掉就再强制来一次。
     *
     * <h3>为什么必须「确认」</h3>
     *
     * <p>这是「按下停止，工具却不停，最后只能把应用杀掉」那条反馈的根因。
     * 旧实现的结构是：一次 {@link #terminateExecution} → 直接抛/返回，
     * 而 {@code finally} 里那句清理**看到 {@code terminationRequested} 已经为真就跳过**。
     * 于是只要那唯一一次杀失败，就再也没有第二次机会 ——
     * 命令会一直跑到它自己的超时（默认 120 秒，构建类下限 15 分钟，
     * 包管理类下限 **1 小时**，上限 2 小时）。用户看到的就是"工具不会停止"。
     *
     * <p>杀一次失败的原因都是真实存在的：pid 没读到（只能 SIGTERM 直接子进程，
     * 而它是 {@code setsid}）、信号发给了错误的进程组、或者命令在被杀之前又拉起了子进程。
     * 所以这里在第一次之后**等一小会儿确认**，没死就再杀一次，
     * 并把这件事记进日志（{@code ZhiCodeMem}）与对话流 —— 它下次再发生才查得动。
     */
    private static void terminateAndConfirm(boolean asRoot, boolean processGroup, File pidFile, Process process,
                                            BashCompletionCoordinator completion, OutputListener listener,
                                            String note) {
        int pid = readPidWithGrace(pidFile);
        terminateExecution(asRoot, processGroup, pid, process, listener, note);
        if (awaitExitQuietly(completion, TERMINATION_CONFIRM_MS)) return;
        forceKill(asRoot, processGroup, pidFile, process, completion, listener);
    }

    /**
     * 兜底强制终止：不等待 pid 文件，读到什么就用什么。
     *
     * <p>与 {@link #terminateAndConfirm} 的区别是它**不做第一次尝试**，只负责"再杀一次"，
     * 所以可以用在 {@code finally} 里而不引入额外延迟。
     */
    private static void forceKill(boolean asRoot, boolean processGroup, File pidFile, Process process,
                                  BashCompletionCoordinator completion, OutputListener listener) {
        int pid = readPid(pidFile);
        ZhiMemory.log("bashForceKill pid=" + pid + " processGroup=" + processGroup + " root=" + asRoot);
        notify(listener, "\n[ZhiCode] 命令没有退出，正在强制终止进程树…\n");
        if (pid > 1) {
            terminateExecution(asRoot, processGroup, pid, process, null, null);
        } else {
            // pid 读不到 —— 只能退化成杀直接子进程。这一步在 setsid 模式下**杀不掉**
            // 真正的命令，所以它后面必须留给用户一个明确的痕迹，而不是静默失败。
            destroyQuietly(process);
        }
        awaitExitQuietly(completion, TERMINATION_CONFIRM_MS);
    }

    /** 等进程退出；被中断时只记下并还原标志，不提前返回（见 {@link #terminateAndConfirm}）。 */
    private static boolean awaitExitQuietly(BashCompletionCoordinator completion, long timeoutMs) {
        boolean restoreInterrupt = false;
        try {
            return completion.awaitProcessExit(timeoutMs);
        } catch (InterruptedException interrupted) {
            restoreInterrupt = true;
            return completion.processExited();
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt();
        }
    }

    /** 见 {@link #terminateAndConfirm}：第一次杀完之后等多久才算"没杀掉"。 */
    private static final long TERMINATION_CONFIRM_MS = 600L;

    /** 借助进程组终止：setid 让整棵树在同一个组里，负号表示「发给整组」。 */
    private static void terminateProcessGroup(int pid) {
        signal(-pid, android.system.OsConstants.SIGTERM);
        sleepQuietly(TERM_GRACE_MS);
        signal(-pid, android.system.OsConstants.SIGKILL);
    }

    private static void terminateRootProcessGroup(int pid) {
        runSuScript("kill -TERM -" + pid + " 2>/dev/null || true;"
            + " sleep 0.2;"
            + " kill -KILL -" + pid + " 2>/dev/null || true");
    }

    /**
     * 用 su 递归杀树。
     *
     * <p>{@code /proc/<pid>/task/<pid>/children} 是内核给出的子进程列表，
     * 比我们自己解析 {@code status} 更准（不需要权限、也不会有竞态）。
     */
    private static void killRootProcessTree(int pid) {
        runSuScript("killtree(){ for child in $(cat /proc/$1/task/$1/children 2>/dev/null);"
            + " do killtree $child; done; kill -TERM $1 2>/dev/null; };"
            + " killtree " + pid + ";"
            + " sleep 0.2;"
            + " kill -KILL " + pid + " 2>/dev/null || true");
    }

    /** 跑一段 root 脚本并最多等 {@link #ROOT_KILL_TIMEOUT_MS}。失败一律忽略。 */
    private static void runSuScript(String script) {
        try {
            Process process = new ProcessBuilder(findSuBinary(), "-c", script).start();
            Thread waiter = start(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "zhi-root-kill-waiter");
            waiter.join(ROOT_KILL_TIMEOUT_MS);
            process.destroy();
        } catch (Throwable ignored) {
            // root 已消失、或被拒绝，都无法做得更好。
        }
    }

    private static Map<Integer, List<Integer>> childrenByParent(List<ProcInfo> processes) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        for (ProcInfo process : processes) {
            List<Integer> siblings = children.get(process.ppid);
            if (siblings == null) {
                siblings = new ArrayList<>();
                children.put(process.ppid, siblings);
            }
            siblings.add(process.pid);
        }
        return children;
    }

    /** 深度优先收集后代。{@code seen} 防环（/proc 变动时理论上可能出现）。 */
    private static void collectDescendants(int pid, Map<Integer, List<Integer>> children,
                                           Set<Integer> seen, List<Integer> out) {
        if (!seen.add(pid)) return;
        List<Integer> kids = children.get(pid);
        if (kids == null) return;
        for (int child : kids) {
            collectDescendants(child, children, seen, out);
            out.add(child);
        }
    }

    private static void signalAll(List<Integer> pids, int signal) {
        for (int pid : pids) {
            if (pid == android.os.Process.myPid()) continue;
            signal(pid, signal);
        }
    }

    /**
     * 发信号。
     *
     * <p>负的 pid 表示「整个进程组」。{@link android.system.Os#kill} 会用
     * 系统调用直连内核，不需要 root、也不需要额外权限 —— 但**只能杀同 UID 的进程**，
     * 这正是上面 uid 过滤存在的原因。
     */
    private static void signal(int pid, int signal) {
        try {
            android.system.Os.kill(pid, signal);
        } catch (Throwable ignored) {
            // 进程可能已经消失，或权限不足。
        }
    }

    private static void destroyQuietly(Process process) {
        try {
            process.destroy();
        } catch (Throwable ignored) {
            // 同上。
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void notify(OutputListener listener, String text) {
        if (text == null || listener == null) return;
        try {
            listener.onOutput(text, true, 0);
        } catch (Throwable ignored) {
            // 界面出错不影响终止流程。
        }
    }

    // ================================================================ 小工具

    /**
     * 找一个可用的 su。
     *
     * <p>列了五个常见位置，包括 Termux 自己的 {@code $PREFIX/bin/su}
     * （Termux 的 root 包会把 su 装在那里）。
     */
    private static String findSuBinary() {
        String[] candidates = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/su",
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile() && file.canExecute()) return candidate;
        }
        throw new IllegalStateException(
            "未找到 Magisk/KernelSU 的 su；设备可能没有 Root，或尚未授予 ZhiCode Root 权限");
    }

    /** 单引号包裹 + 内层单引号转义。命令与路径都可能含空格或引号。 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    // ================================================================ 输出捕获

    /**
     * 读一个流，边累积边投递。
     *
     * <p>累积与投递是两件事，且**投递不受上限影响**：界面上应当看到完整的输出，
     * 而上限只限制留在内存里、最终回给模型的那一份。
     *
     * <p>上限的截断必须按「剩余额度」切，不能整块丢：整块丢会让输出在某处
     * 直接断掉，而那看起来像命令被杀了。
     */
    private static final class Capture implements Runnable {
        private final InputStream input;
        private final boolean stderr;
        private final OutputListener listener;
        private final long startedAt;
        private final StringBuilder output = new StringBuilder();
        private volatile boolean closed;
        private boolean truncated;

        Capture(InputStream input, boolean stderr, OutputListener listener, long startedAt) {
            this.input = input;
            this.stderr = stderr;
            this.listener = listener;
            this.startedAt = startedAt;
        }

        @Override public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    String chunk = new String(buffer, 0, read);
                    append(chunk, read);
                    deliver(chunk);
                }
            } catch (Exception failure) {
                if (closed || failure instanceof InterruptedException) return;
                String message = "\n[capture error: " + failure.getMessage() + "]";
                synchronized (output) {
                    output.append(message);
                }
                deliver(message);
            }
        }

        private void append(String chunk, int length) {
            synchronized (output) {
                int remaining = MAX_CAPTURE_CHARS - output.length();
                if (remaining > 0) output.append(chunk, 0, Math.min(length, remaining));
                if (length > remaining) truncated = true;
            }
        }

        private void deliver(String chunk) {
            if (listener == null || chunk.isEmpty()) return;
            try {
                listener.onOutput(chunk, stderr, System.currentTimeMillis() - startedAt);
            } catch (Throwable ignored) {
                // 界面出错不该中断命令的输出读取。
            }
        }

        /** 标记为主动关闭并关掉流 —— 之后读到的异常不再当成错误上报。 */
        void close() {
            if (closed) return;
            closed = true;
            closeQuietly(input);
        }

        String value() {
            synchronized (output) {
                String text = output.toString();
                return truncated ? text + TRUNCATION_MARKER : text;
            }
        }
    }
}
