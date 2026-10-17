package com.zhizhu.zhicode.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/**
 * Shizuku（https://github.com/RikkaApps/Shizuku）的授权申请与状态查询。
 *
 * <h3>它在整条链路里的位置</h3>
 * Shizuku 由用户经 adb 或 Root 启动一个 shell 身份的进程，再把 Binder 交给本应用。
 * 本类只回答两个问题：「现在能不能用 shell 身份」以及「怎么才能用」；
 * 真正跑命令的是 {@link ShizukuShell}，把它包成工具的是
 * {@code tools/ShizukuBashTool}。
 *
 * <h3>为什么每个方法都吞异常</h3>
 * 这里触碰的是**别的进程**：管理端没装、服务没起来、Binder 刚死、用户拒绝 ——
 * 全是常态而不是异常路径。让它们抛出去会把设置页整页带崩，
 * 而用户此刻只是想看一眼「到底能不能用」。
 */
public final class ShizukuBridge {

    /** Shizuku 管理端包名（Sui 也由它承载）。 */
    public static final String MANAGER_PACKAGE = "moe.shizuku.privileged.api";
    /** 项目地址：没装管理端时给用户的安装入口。 */
    public static final String PROJECT_URL = "https://github.com/RikkaApps/Shizuku";

    /** 没有安装 Shizuku / Sui。 */
    public static final int UNAVAILABLE = 0;
    /** 装了管理端，但 shell 服务没在运行。 */
    public static final int NOT_RUNNING = 1;
    /** 服务在运行，本应用还没被授权。 */
    public static final int DENIED = 2;
    /** 已授权，可以用 shell 身份。 */
    public static final int GRANTED = 3;

    private ShizukuBridge() { }

    /** 当前状态，取值是上面四个常量。 */
    public static int state(Context context) {
        try {
            if (Shizuku.pingBinder()) {
                return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? GRANTED : DENIED;
            }
        } catch (Throwable ignored) {
            // Binder 刚死、或 provider 没声明 —— 都按「服务不在」处理。
        }
        return managerInstalled(context) ? NOT_RUNNING : UNAVAILABLE;
    }

    public static boolean managerInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MANAGER_PACKAGE, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 已授权时的服务详情（服务版本 / 实际 uid / SELinux 上下文），否则 {@code null}。
     *
     * <p>这一行是**唯一的可信证据**：模型与用户都容易把「开了开关」当成「能跑命令」，
     * 而 uid 才是真正决定这条命令落在谁身上的东西（2000 = shell，0 = root）。
     */
    public static String detail() {
        try {
            if (!Shizuku.pingBinder()) return null;
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) return null;
            String seLinux = Shizuku.getSELinuxContext();
            return "服务 v" + Shizuku.getVersion() + " · uid " + Shizuku.getUid()
                + (seLinux == null || seLinux.isEmpty() ? "" : " · " + seLinux);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 给界面直接显示的中文状态。 */
    public static String stateLabel(Context context) {
        switch (state(context)) {
            case GRANTED: return "已授权（shell 身份）";
            case DENIED: return "未授权，点此申请";
            case NOT_RUNNING: return "服务未运行，先在 Shizuku 里启动";
            default: return "未安装 Shizuku，点此了解";
        }
    }

    /**
     * 弹出 Shizuku 的授权请求。
     *
     * @return {@code null} 表示请求已交给 Shizuku，结果由
     *         {@link Shizuku.OnRequestPermissionResultListener} 回调；
     *         否则返回**不能申请的原因**（直接显示给用户，不要吞掉）。
     */
    public static String requestPermission(int requestCode) {
        try {
            if (Shizuku.isPreV11()) return "当前 Shizuku 版本过低，请升级到 11 以上或改用 Sui";
            if (!Shizuku.pingBinder()) return "Shizuku 服务未运行：请先打开 Shizuku 并启动服务";
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return null;
            // 「拒绝且不再询问」之后再调 requestPermission 是**没有弹窗**的，
            // 用户会以为点了没反应。这种情况只能让他去管理端里手动打开。
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                return "上次选了「拒绝且不再询问」，请在 Shizuku 的授权应用列表里手动允许 ZhiCode";
            }
            Shizuku.requestPermission(requestCode);
            return null;
        } catch (Throwable e) {
            String message = e.getMessage();
            return "授权请求失败：" + (message == null ? e.toString() : message);
        }
    }

    /** 打开 Shizuku 管理端；没装返回 false（调用方据此改开项目主页）。 */
    public static boolean openManager(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
