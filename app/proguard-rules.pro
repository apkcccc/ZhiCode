# 蜘蛛（ZhiCode）的 R8 规则。
#
# 原则：这里**只写开 R8 之后真的会坏的东西**。
#
# Compose / AndroidX / MiuiX 不需要在这里重复声明，它们要么自带 consumer 规则，
# 要么本来就不靠反射。往这里堆"以防万一"的 -keep 只会让 R8 白干。
#
# 判断方法：构建后翻 app/build/outputs/mapping/release/mapping.txt，看某个类
# 是不是真的被改了名；先有证据，再加规则。


# ---------------------------------------------------------------------------
# 1) 原生库按名字反查 Java 成员 —— 漏了必崩，且不是编译错误
# ---------------------------------------------------------------------------
#
# app/src/main/jniLibs/arm64-v8a/libtermux.so 是**预编译**产物，走的是 JNI 名字
# 绑定，导出符号已经写死在 .so 里：
#
#     Java_com_termux_terminal_JNI_createSubprocess
#     Java_com_termux_terminal_JNI_setPtyWindowSize
#     Java_com_termux_terminal_JNI_setPtyUTF8Mode
#     Java_com_termux_terminal_JNI_waitFor
#     Java_com_termux_terminal_JNI_close
#
# ART 是按「Java_ + 包名/类名/方法名（点换下划线）」去 .so 里找符号的。R8 一旦把
# com.termux.terminal.JNI 或其方法改名，终端就永远起不来，报的是运行时
# UnsatisfiedLinkError 而不是编译失败 —— 这类问题最难查，所以显式钉住。
#
# 两条都需要：
#   -keepclasseswithmembernames 保住**名字**；
#   -keep class … {*;} 保住**类本身**不被裁掉 —— JNI.java 里那几个方法只被原生
#   侧的符号引用，Java 代码里看不到调用者，R8 会当成死代码删掉。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.termux.terminal.JNI { *; }


# ---------------------------------------------------------------------------
# 2) 注解要能在运行期读到
# ---------------------------------------------------------------------------
#
# Bcore 的 black-reflection 是**注解驱动**的反射：靠 @BClass / @BMethod(name = "…")
# 这些运行期注解决定去反射哪个成员。注解属性若在压缩阶段被当成"没人读"丢掉，
# 反射会静默失效。
#
# 注意 Bcore 的 consumer 规则里已有一份类似的 keep —— 但那是针对 **Bcore 的类**
# 写规则名；注解属性是被使用方（就是本模块）保留的，所以这里必须再声明一次。
-keepattributes *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault, Signature, InnerClasses, EnclosingMethod


# ---------------------------------------------------------------------------
# 3) 让崩溃栈还能看
# ---------------------------------------------------------------------------
#
# 不保留行号的话，拿到的栈全是 "SourceFile:0"，定位不了任何东西。代价是几 KB，
# 对一个自己长期用、还要开源给人读的工具完全值得。
#
# renamesourcefileattribute 把源文件名统一成 SourceFile，免得把本机路径
# （/data/user/0/com.iqge/...）泄进发布产物。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile


# ---------------------------------------------------------------------------
# 4) 隐藏 API 的引用
# ---------------------------------------------------------------------------
#
# Bcore 要 hook 的本来就是 android.jar 里**不存在**的东西（ActivityThread、
# ServiceManager、dalvik.system.* 等）。R8 拿编译期 android.jar 当参照，找不到
# 这些类就会报 "Missing class"，而按 AGP 的默认严格度会直接让构建失败。
#
# 这些类在**运行时一定存在**（就是设备上的 framework），所以警告本身是假警报，
# 用 -dontwarn 关掉是正确做法，而不是用 -keep 去"保住"不存在的东西。
-dontwarn android.**
-dontwarn com.android.**
-dontwarn dalvik.**
-dontwarn libcore.**
-dontwarn mirror.**
-dontwarn sun.**
-dontwarn org.apache.**
-dontwarn org.slf4j.**
-dontwarn top.niunaijun.blackbox.**
-dontwarn top.niunaijun.blackreflection.**
-dontwarn top.niunaijun.jnihook.**


# ---------------------------------------------------------------------------
# 5) Shizuku（无 Root 的 shell 身份）
# ---------------------------------------------------------------------------
#
# 这一段是**必须**的，而且理由不是"以防万一"：
#
# 1) `moe.shizuku.server.*` 是 AIDL 生成的 Stub/Proxy。我们调用的是
#    `IShizukuService.Stub.asInterface(binder)`，而它在**对端**（Shizuku 服务进程）
#    才展开成 onTransact 分派。Binder 的分派靠 `Parcel` 里的接口描述符字符串，
#    那个字符串是编译期常量、不会被改名 —— 但只要 R8 把 `Stub` 本身裁掉或改名，
#    我们这侧就没有可用的代理类，`getVersion()`/`newProcess()` 会直接
#    NoSuchMethodError。这两个类是本工具唯一的执行通路，体积只有几 KB。
#
# 2) `rikka.shizuku.ShizukuProvider` 由**系统**按名字实例化（清单里那条 provider），
#    R8 看不到任何 Java 调用点。AGP 通常会替清单组件自动加 keep，但那条规则
#    覆盖的是"清单里出现的组件类"，而 provider 内部还会反射回调
#    `Shizuku.onBinderReceived(...)`，这一层没有任何静态引用。
#
# 3) `Shizuku` 自己用反射读隐藏的 `android.os.SystemProperties`（见
#    `ShizukuSystemProperties`），并在 API 11 之前的版本上走另一条
#    Parcel 手写路径；这些都只按名字发生，静态分析看不到。
#
# 结论：把这两个包整体钉住。它们是第三方库的外壳（不是我们的业务代码），
# 总量在 30KB 量级，换的是"打开 R8 之后工具仍然可用"。
-keep class moe.shizuku.** { *; }
-keep class rikka.shizuku.** { *; }
