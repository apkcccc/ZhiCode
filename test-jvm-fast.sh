#!/usr/bin/env bash
#
# 纯 JVM 单测的**快路径**。
#
# <h3>为什么不直接用 `./gradlew :app:testDebugUnitTest`</h3>
#
# 那个任务在本机上是**分钟级**的：`--tests` 只过滤「跑哪些测试」，
# 不过滤「编译什么」—— Gradle 仍要走 compileDebugKotlin（带 Compose 编译器插件）、
# compileDebugJavaWithJavac（Termux + zhicode 那棵上千文件的 Java 树）、
# processDebugResources 与打包依赖。改一行代码等几分钟，验证循环就废了。
#
# 而 `app/src/test/` 里有一批测试是**纯 JVM** 的（`zhicode/core/`、`zhicode/api/`、
# `zhicode/tools/` 这些包刻意不 import 任何 `android.*`，见 ApiPureLogicTest 的类注释）。
# 它们只需要：几个源码文件 + 已经编译好的主类目录 + junit + org.json。
# 用 javac 现场编这几个文件，再用 JUnitCore 跑，就是**秒级**。
#
# 覆盖面与 Gradle 那条路完全一致（同一个 JUnit、同一份 org.json 真实现），
# 差别只是不重编整个 app。所以：日常改动的验证走这里，
# 大批次收尾时再跑一次完整的 `./gradlew :app:testDebugUnitTest`。
#
# 用法：
#   bash test-jvm-fast.sh [类名 …]      # 缺省跑下面 NEEDS 里列的全部
#
set -uo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT" || exit 1

# 需要重新编译的**主源码**（只有被测试直接依赖、且我们可能改动的那几个）。
# 其余主类从 Gradle 已编译的目录里取 —— 那些不是本次改动的对象。
MAIN_SOURCES=(
    app/src/main/java/com/termux/app/zhicode/core/PermissionGate.java
    app/src/main/java/com/termux/app/zhicode/core/PermissionModePolicy.java
    app/src/main/java/com/termux/app/zhicode/core/RiskClassifier.java
    app/src/main/java/com/termux/app/zhicode/core/StorageLinks.java
    app/src/main/java/com/termux/app/zhicode/core/FileOps.java
    app/src/main/java/com/termux/app/zhicode/model/SessionConfig.java
    app/src/main/java/com/termux/app/zhicode/core/SystemPromptBuilder.java
    app/src/main/java/com/termux/app/zhicode/tools/WebSearchJson.java
    app/src/main/java/com/termux/app/zhicode/tools/WebSearchTool.java
)

# Kotlin 主源码（同样只列被测试直接依赖的）。
#
# 它们的编译**不带 android.jar** —— 这本身就是一条约束：这些文件必须真的与
# Android 无关，否则快路径会直接编译失败。放在这里的价值是它守得住
# 「纯逻辑不依赖 Android」这个前提，而不只是靠注释提醒。
MAIN_KT_SOURCES=(
    app/src/main/java/com/termux/app/zhicode/core/DocumentTree.kt
    app/src/main/java/com/termux/app/zhicode/core/ProviderLog.kt
    app/src/main/java/com/termux/app/zhicode/api/zcode/ZcodeWire.kt
    app/src/main/java/com/termux/app/zhicode/api/zcode/ZcodeHarness.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ToolKind.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ToolActions.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/LiveOutput.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ErrorSummary.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ChatNavigation.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/FileFormat.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/FileRangeSelection.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ChatKind.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ToolGrouping.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/TurnLayout.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/ActionBusy.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/McpDraft.kt
    app/src/main/java/com/zhizhu/zhicode/compose/model/PromptSlots.kt
)

# 需要编译并运行的测试类（相对 app/src/test/java、点号包名）。
DEFAULT_TESTS=(
    com.termux.app.zhicode.core.PermissionModeMatrixTest
    com.termux.app.zhicode.core.RiskClassifierTest
    com.termux.app.zhicode.core.StorageLinksTest
    com.termux.app.zhicode.core.FileOpsTest
    com.termux.app.zhicode.core.DocumentTreeTest
    com.termux.app.zhicode.core.ProviderLogTest
    com.termux.app.zhicode.api.zcode.ZcodeWireTest
    com.termux.app.zhicode.api.ApiPureLogicTest
    com.termux.app.zhicode.tools.WebSearchJsonTest
    com.zhizhu.zhicode.compose.model.ToolActionsTest
    com.zhizhu.zhicode.compose.model.LiveOutputTest
    com.zhizhu.zhicode.compose.model.ErrorSummaryTest
    com.zhizhu.zhicode.compose.model.ChatNavigationTest
    com.zhizhu.zhicode.compose.model.FileFormatTest
    com.zhizhu.zhicode.compose.model.FileRangeSelectionTest
    com.zhizhu.zhicode.compose.model.ToolGroupingTest
    com.zhizhu.zhicode.compose.model.TurnLayoutTest
    com.zhizhu.zhicode.compose.model.ActionBusyTest
    com.zhizhu.zhicode.compose.model.McpFormLogicTest
    com.zhizhu.zhicode.compose.model.BrandTextTest
    com.termux.app.zhicode.core.PromptStructureTest
    com.zhizhu.zhicode.compose.model.PromptSlotsTest
)

# 哪些目标是 Kotlin。
#
# 其实是**按文件扩展名自动分辨**的（见下面的 is_kotlin_target），
# 所以这个列表只是给"两个同名文件都存在"这种极端情况留的显式出口；
# 日常不需要往里加东西 —— 加了也不会被用到。
KT_TESTS=()

TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then
    TARGETS=("${DEFAULT_TESTS[@]}")
fi

# ---- 依赖 jar：都从 Gradle 缓存里找，避免再引一份版本 ----
find_jar() {
    find "$HOME/.gradle/caches/modules-2" -name "$1" 2>/dev/null | head -1
}
# 版本号会变的那些用 glob（Kotlin stdlib 的版本跟着 Kotlin 插件走，
# 写死文件名会在升级插件时静默失效）。
find_jar_glob() {
    find "$HOME/.gradle/caches/modules-2" -name "$1" 2>/dev/null | sort -V | tail -1
}
JUNIT="$(find_jar 'junit-4.13.2.jar')"
HAMCREST="$(find_jar 'hamcrest-core-1.3.jar')"
JSON="$(find_jar 'json-20240303.jar')"
# Kotlin 标准库：Kotlin 编出来的类在**运行时**需要它（`kotlin.Unit`、
# `kotlin.jvm.internal.Intrinsics` 等）。少了它的报错是
# `NoClassDefFoundError: kotlin/Unit`，看起来完全不像"缺个 jar"。
KOTLIN_STDLIB="$(find_jar_glob 'kotlin-stdlib-[0-9]*.jar')"

# android.jar 只为**编译**几个 import 了 android.* 的主源码（当前只有
# WebSearchTool 的 android.text.Html）。测试本身不碰 android，运行时不需要它。
ANDROID_JAR=""
SDK_DIR="$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)"
if [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/platforms" ]; then
    # 取版本号最大的那个 platforms/android-*
    ANDROID_JAR="$(ls -d "$SDK_DIR"/platforms/android-* 2>/dev/null | sort -V | tail -1)/android.jar"
    [ -f "$ANDROID_JAR" ] || ANDROID_JAR=""
fi

if [ -z "$JUNIT" ] || [ -z "$JSON" ]; then
    echo "找不到 junit / org.json（Gradle 缓存被清过？）" >&2
    echo "  junit: $JUNIT" >&2
    echo "  json : $JSON" >&2
    exit 1
fi

# ---- 已编译的主类目录：Java（javac）与 Kotlin（kotlin-classes）各一份 ----
MAIN_CLASSES_CP=""
for d in \
    app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes \
    app/build/tmp/kotlin-classes/debug; do
    if [ -d "$d" ]; then
        MAIN_CLASSES_CP="${MAIN_CLASSES_CP:+$MAIN_CLASSES_CP:}$d"
    fi
done
if [ -z "$MAIN_CLASSES_CP" ]; then
    echo "找不到已编译的主类目录：先跑一次 ./gradlew :app:assembleDebug" >&2
    exit 1
fi

CP_BASE="$MAIN_CLASSES_CP:$JUNIT:$HAMCREST:$JSON${KOTLIN_STDLIB:+:$KOTLIN_STDLIB}"
# 编译用 classpath 额外带 android.jar（运行时不带：带着反而会让 android.* 走到桩实现）。
CP_COMPILE="$CP_BASE${ANDROID_JAR:+:$ANDROID_JAR}"
OUT=".test-jvm"

# ---- 编译：主源码 + 测试源码 ----
#
# 顺序是**有依赖关系的**，不能合并不了：
#   1. javac 编 Java 主源码  →  得到 FileOps / TermuxConstants 等
#   2. kotlinc 编 Kotlin 主源码（classpath 指向第 1 步的产物）
#   3. javac 编 Java 测试（classpath 含第 2 步的产物）
#   4. kotlinc 编 Kotlin 测试
# 反过来先编 Kotlin 的话，DocumentTree.kt 引用的 FileOps 还不存在。
#
# ❗ Kotlin 那两步**故意不带 android.jar**：它们编的是"纯逻辑"，
# 必须真的与 Android 无关。带上的话一个 `import android.os.Build`
# 也能编过，然后在快路径运行时才炸成 NoClassDefFoundError —— 那时
# 报错指向的是运行环境而不是那行 import。
NEED_BUILD=0
STAMP="$OUT/.stamp"
# 上一次编了哪些测试类。**必须记**：只按时间戳判断的话，
# `test-jvm-fast.sh A` 之后跑默认三件套会复用只含 A 的 classes 目录，
# 另外两个类变成 ClassNotFoundException（看起来像"环境坏了"）。
TARGETS_FILE="$OUT/.targets"
WANT_TARGETS="$(printf '%s\n' "${TARGETS[@]}")"

# 目标里哪些是 Kotlin、哪些是 Java。缺省按 .java 找，找不到再按 .kt 找。
test_source_of() {
    local cls="$1" base java kt
    base="app/src/test/java/$(echo "$cls" | tr '.' '/')"
    java="$base.java"
    kt="$base.kt"
    if [ -f "$java" ]; then echo "$java"; return 0; fi
    if [ -f "$kt" ]; then echo "$kt"; return 0; fi
    echo ""
}
is_kotlin_target() {
    case " ${KT_TESTS[*]-} " in *" $1 "*) return 0 ;; esac
    local base="app/src/test/java/$(echo "$1" | tr '.' '/')"
    [ ! -f "$base.java" ] && [ -f "$base.kt" ]
}

if [ ! -f "$STAMP" ]; then
    NEED_BUILD=1
elif [ "$(cat "$TARGETS_FILE" 2>/dev/null)" != "$WANT_TARGETS" ]; then
    rm -rf "$OUT/classes"
    NEED_BUILD=1
else
    for f in "${MAIN_SOURCES[@]}" "${MAIN_KT_SOURCES[@]}"; do
        [ "$f" -nt "$STAMP" ] && NEED_BUILD=1
    done
    for t in "${TARGETS[@]}"; do
        tf="$(test_source_of "$t")"
        [ -n "$tf" ] && [ "$tf" -nt "$STAMP" ] && NEED_BUILD=1
    done
fi

if [ "$NEED_BUILD" = "1" ]; then
    mkdir -p "$OUT/classes"

    # ---- 1. Java 主源码 ----
    if ! javac -nowarn -implicit:none -d "$OUT/classes" -cp "$CP_COMPILE" \
            "${MAIN_SOURCES[@]}" 2>"$OUT/javac.log"; then
        echo "编译失败（Java 主源码）：" >&2
        sed 's/^/  /' "$OUT/javac.log" | head -40 >&2
        exit 1
    fi

    # ---- 2. Kotlin 主源码 ----
    # 有 Kotlin 源才调 kotlinc：它的启动就要几秒，别拖慢"只改了 Java"的那些轮次。
    if [ ${#MAIN_KT_SOURCES[@]} -gt 0 ]; then
        if ! kotlinc -nowarn -d "$OUT/classes" -cp "$OUT/classes:$CP_BASE" \
                "${MAIN_KT_SOURCES[@]}" >"$OUT/kotlinc-main.log" 2>&1; then
            echo "编译失败（Kotlin 主源码）：" >&2
            grep -v "^warning:" "$OUT/kotlinc-main.log" | sed 's/^/  /' | head -40 >&2
            exit 1
        fi
    fi

    # ---- 3. Java 测试 ----
    JAVA_TESTS=()
    KT_TEST_SOURCES=()
    for t in "${TARGETS[@]}"; do
        tf="$(test_source_of "$t")"
        [ -z "$tf" ] && continue
        case "$tf" in
            *.java) JAVA_TESTS+=("$tf") ;;
            *.kt) KT_TEST_SOURCES+=("$tf") ;;
        esac
    done
    if [ ${#JAVA_TESTS[@]} -gt 0 ]; then
        # -implicit:none：只编点名的这几个，依赖的类走 classpath。
        if ! javac -nowarn -implicit:none -d "$OUT/classes" -cp "$OUT/classes:$CP_COMPILE" \
                "${JAVA_TESTS[@]}" 2>"$OUT/javac.log"; then
            echo "编译失败（Java 测试）：" >&2
            sed 's/^/  /' "$OUT/javac.log" | head -40 >&2
            exit 1
        fi
    fi

    # ---- 4. Kotlin 测试 ----
    if [ ${#KT_TEST_SOURCES[@]} -gt 0 ]; then
        if ! kotlinc -nowarn -d "$OUT/classes" -cp "$OUT/classes:$CP_BASE" \
                "${KT_TEST_SOURCES[@]}" >"$OUT/kotlinc-test.log" 2>&1; then
            echo "编译失败（Kotlin 测试）：" >&2
            grep -v "^warning:" "$OUT/kotlinc-test.log" | sed 's/^/  /' | head -40 >&2
            exit 1
        fi
    fi

    touch "$STAMP"
    printf '%s\n' "${TARGETS[@]}" > "$TARGETS_FILE"
fi

# ---- 运行：JUnitCore ----
FAILED=0
for t in "${TARGETS[@]}"; do
    tf="$(test_source_of "$t")"
    if [ -z "$tf" ]; then
        echo "PASS  $t （源码不存在，跳过）"
        continue
    fi
    if out=$(java -cp "$OUT/classes:$CP_BASE" org.junit.runner.JUnitCore "$t" 2>&1); then
        n=$(echo "$out" | grep -o "OK ([0-9]* test" | grep -o "[0-9]*")
        echo "PASS  ${t##*.}  （${n:-?} 条）"
    else
        echo "FAIL  ${t##*.}"
        echo "$out" | sed 's/^/      /' | head -20
        FAILED=1
    fi
done

echo "-----"
if [ "$FAILED" = "0" ]; then
    echo "全部通过"
else
    echo "有失败项"
fi
exit "$FAILED"
