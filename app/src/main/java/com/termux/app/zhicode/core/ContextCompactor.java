package com.termux.app.zhicode.core;

import com.termux.app.zhicode.json.JsonItems;
import com.termux.app.zhicode.model.AssistantTurn;
import com.termux.app.zhicode.model.SessionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文压缩的算法部分：切在哪里、摘要请求怎么组装、结果怎么还原。
 *
 * <h3>为什么不放在界面里</h3>
 * 这里的每一条都是不变量，而不是显示偏好。放在界面层的话，「自动压缩该留多少」会随着
 * 某个按钮的实现细节漂移，而这类漂移的表现是「偶尔莫名其妙地丢上下文」。
 *
 * <h3>四条不变量</h3>
 * <ol>
 *   <li><b>给摘要留出输出空间</b>：压缩时还要再发一次请求，这次请求同样会占窗口。
 *       不留空间的话，越接近满就越压不动 —— 而那时恰恰最需要压缩。</li>
 *   <li><b>切点在 API 回合边界上，不按消息条数</b>：一条「回合」是一次请求及其全部
 *       工具往返。按条数切会把一次调用和它的结果切开，提供方直接报错。</li>
 *   <li><b>只在摘要请求里缩减大块内容</b>：历史本身不动。缩减是「这一次请求」的
 *       权宜之计，落到历史里就等于真的丢了。</li>
 *   <li><b>摘要由配置的模型生成，且禁用工具</b>：摘要模型去调工具会得到一份
 *       没有正文的「摘要」，那时压缩等于把上下文清空了。</li>
 * </ol>
 *
 * <p>另外有一条「只影响落盘」的规则：摘要里的草稿段（{@code <analysis>}）会被剥掉
 * 再写进压缩后的上下文 —— 那是模型的思考过程，留着只会占地方。
 */
final class ContextCompactor {

    /** 摘要请求最多允许输出多少 token。它是从窗口里先扣掉的固定预留。 */
    static final int SUMMARY_OUTPUT_RESERVE_TOKENS = 20_000;
    /** 自动压缩与窗口上限之间保留的余量：下一轮请求还要用。 */
    static final int AUTO_COMPACT_BUFFER_TOKENS = 13_000;
    /** 「提示词超出窗口」最多重试几次（每次按 API 回合往回缩）。 */
    static final int MAX_PROMPT_TOO_LONG_RETRIES = 2;
    /** 自动压缩连续失败多少次后停止重试。 */
    static final int MAX_CONSECUTIVE_AUTO_FAILURES = 3;

    private static final int MIN_CONTEXT_WINDOW_TOKENS = 16_000;
    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 128_000;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 32_768;
    /** 有效窗口（扣掉预留之后）的下限。再小的话连一次请求都放不下。 */
    private static final int MIN_EFFECTIVE_WINDOW_TOKENS = 8_000;
    private static final int MIN_COMPACT_THRESHOLD_TOKENS = 4_000;
    /** 用户比例的下限：允许把阈值调低，但不允许低到「每轮都压缩」。 */
    private static final double MIN_AUTO_COMPACT_RATIO = 0.50;
    private static final double DEFAULT_AUTO_COMPACT_RATIO = 0.95;
    /** 保留最近多少 token 的原样消息。取窗口的 1/5，并限制在这个区间内。 */
    private static final int MIN_TARGET_RECENT_TOKENS = 8_000;
    private static final int MAX_TARGET_RECENT_TOKENS = 32_000;
    /** 手动压缩的兜底门槛：再短的历史也值得整理一次。 */
    private static final int MIN_MANUAL_COMPACT_TOKENS = 1_500;

    /** 逐次重试时给正文的长度上限。每缩一次，就多省出一点窗口。 */
    private static final int[] SUMMARY_TEXT_LIMITS = {30_000, 14_000, 8_000};
    /** 同上的工具块上限。工具输出通常是最大的那一块。 */
    private static final int[] SUMMARY_TOOL_LIMITS = {8_000, 3_500, 2_000};
    /** 最老几轮被整段丢给摘要时，为它们生成一份确定性摘要的长度上限。 */
    private static final int OVERFLOW_DIGEST_CHARS = 8_000;
    /** 每条消息在摘要请求里最多留多少字符（生成摘要用，不是原文）。 */
    private static final int DIGEST_ENTRY_CHARS = 1_200;
    /** 用户附加的「重点保留什么」的长度上限。 */
    private static final int CUSTOM_INSTRUCTION_CHARS = 6_000;

    /** 一条消息、一个内容块的固定开销（角色、块边界、括号等）。 */
    private static final int TOKENS_PER_MESSAGE = 6;
    private static final int TOKENS_PER_BLOCK = 3;
    /** 图片按固定值估算：字节数与 token 数没有可比性，按大小算会离谱地高。 */
    private static final int IMAGE_TOKENS = 1_200;

    /** 截断提示本身的长度，用于在 headTail 里预留位置。 */
    private static final int TRUNCATION_NOTICE_CHARS = 72;

    /** 这些子串表示「这次请求超出了模型窗口」，来自各家 API 的文案，不可翻译。 */
    private static final String[] PROMPT_TOO_LONG_MARKERS = {
            "prompt_too_long", "context_length_exceeded", "maximum context length", "context window",
            "too many tokens", "request too large", "http 413"};

    private static final Pattern ANALYSIS_BLOCK = Pattern.compile("(?is)<analysis>.*?</analysis>");
    private static final Pattern SUMMARY_BLOCK = Pattern.compile("(?is)<summary>(.*?)</summary>");
    private static final Pattern SUMMARY_TAG = Pattern.compile("(?is)</?summary>");
    private static final Pattern BLANK_LINE_RUNS = Pattern.compile("\\n[ \\t]*\\n(?:[ \\t]*\\n)+");

    /**
     * 一次压缩的切分方案。
     *
     * <p>{@link #prefix} 是要被摘要替换掉的部分，{@link #recent} 是原样保留的部分。
     * {@link #originalJson} 是<b>生成方案时</b>的历史快照：提交之前要拿它比对，
     * 确认这期间没有别的动作（重置、恢复）把会话整个换掉。
     */
    static final class Plan {
        final JSONArray original;
        final JSONArray prefix;
        final JSONArray recent;
        final int removedMessages;
        final int retainedMessages;
        final int roughBeforeTokens;
        final String originalJson;

        Plan(JSONArray original, JSONArray prefix, JSONArray recent, int roughBeforeTokens) {
            this.original = original;
            this.prefix = prefix;
            this.recent = recent;
            this.removedMessages = prefix.length();
            this.retainedMessages = recent.length();
            this.roughBeforeTokens = roughBeforeTokens;
            this.originalJson = original.toString();
        }
    }

    private ContextCompactor() {}

    // ------------------------------------------------------------ 阈值

    /** 扣掉摘要预留之后，真正可用的窗口。 */
    static int effectiveContextWindow(SessionConfig config) {
        int window = config == null
                ? DEFAULT_CONTEXT_WINDOW_TOKENS
                : Math.max(MIN_CONTEXT_WINDOW_TOKENS, config.contextWindowTokens);
        int configuredMaxOutput = config == null
                ? DEFAULT_MAX_OUTPUT_TOKENS
                : Math.max(1, config.maxTokens);
        int reserve = Math.min(configuredMaxOutput, SUMMARY_OUTPUT_RESERVE_TOKENS);
        return Math.max(MIN_EFFECTIVE_WINDOW_TOKENS, window - reserve);
    }

    /**
     * 自动压缩的触发阈值。
     *
     * <p>两个上限取更小的那个：一个是「有效窗口再留一点余量」，另一个是用户设的比例。
     * 后者让用户可以更早触发压缩（例如窗口填得比真实值大时），但不能把它调到比安全余量
     * 还高 —— 那等于让请求先去撞窗口上限。
     */
    static int autoCompactThreshold(SessionConfig config) {
        int window = config == null
                ? DEFAULT_CONTEXT_WINDOW_TOKENS
                : Math.max(MIN_CONTEXT_WINDOW_TOKENS, config.contextWindowTokens);
        int safe = Math.max(MIN_COMPACT_THRESHOLD_TOKENS,
                effectiveContextWindow(config) - AUTO_COMPACT_BUFFER_TOKENS);
        double ratio = config == null
                ? DEFAULT_AUTO_COMPACT_RATIO
                : Math.max(MIN_AUTO_COMPACT_RATIO, Math.min(1.0, config.autoCompactRatio));
        int userCap = (int) Math.floor(window * ratio);
        return Math.max(MIN_COMPACT_THRESHOLD_TOKENS, Math.min(safe, userCap));
    }

    // ------------------------------------------------------------ 切点

    /**
     * 建议切在哪里。
     *
     * <p>从最后一轮往前累加，直到保留的部分达到目标长度。这样「最近几轮」永远是完整的
     * 回合，不会出现「一次调用在，它的结果没了」。
     *
     * @return 0 表示不值得压（要么没什么可压，要么压了也不省）
     */
    static int suggestedCut(JSONArray source, SessionConfig config, boolean manual) {
        if (source == null || source.length() == 0) return 0;
        List<Integer> roundStarts = apiRoundStarts(source);
        int messageCount = source.length();
        int[] cumulative = cumulativeRoughTokens(source);
        int targetRecent = clamp(effectiveContextWindow(config) / 5,
                MIN_TARGET_RECENT_TOKENS, MAX_TARGET_RECENT_TOKENS);

        int keepStart = roundStarts.get(roundStarts.size() - 1);
        int keptTokens = cumulative[messageCount] - cumulative[keepStart];
        for (int round = roundStarts.size() - 2; round >= 0; round--) {
            int start = roundStarts.get(round);
            int end = roundStarts.get(round + 1);
            int roundTokens = cumulative[end] - cumulative[start];
            if (keptTokens + roundTokens > targetRecent) break;
            keepStart = start;
            keptTokens += roundTokens;
        }
        if (keepStart > 0) return keepStart;
        return cutWhenFirstRoundHoldsEverything(cumulative[messageCount], messageCount, roundStarts, config, manual);
    }

    /**
     * 全部历史都在一轮里时的兜底。
     *
     * <p>这时按轮次切是没有意义的（只有一轮），但仍然有两种真实场景要照顾：
     * <ul>
     *   <li><b>手动压缩</b>：即使消息很少，用户按了「压缩」也希望有东西发生。
     *       所以保留最后两轮作为可继续工作的上下文，而不是退回「保留最后 12 条」这种
     *       拍脑袋的规则。</li>
     *   <li><b>自动压缩</b>：只可能是「一轮巨大的历史」，此时整体替换是唯一安全的减法。</li>
     * </ul>
     */
    private static int cutWhenFirstRoundHoldsEverything(int totalTokens, int messageCount,
                                                        List<Integer> roundStarts, SessionConfig config,
                                                        boolean manual) {
        if (roundStarts.size() > 1 && (manual || totalTokens >= autoCompactThreshold(config))) {
            int keepRound = manual ? Math.max(1, roundStarts.size() - 2) : roundStarts.size() - 1;
            return roundStarts.get(keepRound);
        }
        int floor = manual ? MIN_MANUAL_COMPACT_TOKENS : autoCompactThreshold(config);
        return totalTokens >= floor ? messageCount : 0;
    }

    static Plan createPlan(JSONArray source, int safeCut) throws Exception {
        JSONArray original = cloneArray(source);
        int cut = Math.max(0, Math.min(safeCut, original.length()));
        return new Plan(original, slice(original, 0, cut), slice(original, cut, original.length()),
                roughTokens(original));
    }

    // ------------------------------------------------------------ 摘要请求

    /**
     * 摘要请求的消息序列：被压缩的那一段（按需缩减）+ 一条提出要求的 user 消息。
     *
     * <p>{@code attempt} 就是「第几次重试」，它决定缩减的力度 —— 重试的唯一原因就是
     * 上一次的请求超过了窗口，所以每次都得更小。
     */
    static JSONArray buildSummaryMessages(Plan plan, String customInstructions, int attempt) throws Exception {
        return buildSummaryMessages(plan, customInstructions, attempt, "");
    }

    /**
     * @param promptHead 用户自定义的开场段（{@code SessionConfig.compactPromptHead}）。
     *                   空串 = 用 {@link #PROMPT_HEAD}。
     */
    static JSONArray buildSummaryMessages(Plan plan, String customInstructions, int attempt,
                                          String promptHead) throws Exception {
        JSONArray out = reduceForSummary(plan.prefix, Math.max(0, attempt));
        out.put(message("user", new JSONArray().put(textBlock(compactPrompt(customInstructions,
                plan.recent.length() > 0, promptHead)))));
        return out;
    }

    /**
     * 压缩后的历史：一条说明 + 摘要 + 原样保留的近期消息。
     *
     * <p>说明里的两句是给模型的指令，缺一句就会出现两种典型故障：
     * 没有「不要复述摘要」它会把摘要当成用户刚说的话回答一遍；
     * 没有「后续消息是原文」它会把保留的近期消息也当成需要总结的材料。
     */
    static JSONArray buildCompactedMessages(Plan plan, String summary) throws Exception {
        String keptVerbatim = plan.recent.length() > 0 ? " Recent messages follow verbatim." : "";
        String context = new StringBuilder()
                .append("<context_summary>\n")
                .append("This session continues from an earlier conversation. The model-generated summary")
                .append(" below covers the compacted prefix.\n\n")
                .append(summary.trim())
                .append("\n</context_summary>\n\n")
                .append("<continuation_instruction>Continue seamlessly from this context.")
                .append(" Do not acknowledge or recap the summary; resume the current task directly.")
                .append(keptVerbatim)
                .append("</continuation_instruction>")
                .toString();

        JSONArray out = new JSONArray();
        out.put(message("user", new JSONArray().put(textBlock(context))));
        for (int i = 0; i < plan.recent.length(); i++) out.put(plan.recent.getJSONObject(i));
        return out;
    }

    /**
     * 从模型的回复里取摘要正文。
     *
     * <p>三种失败都要报出来，而且要说清是哪一种：没回复、被输出上限截断、
     * 模型去调工具了。它们的处置方式完全不同（重试 / 调高输出上限 / 改提示词），
     * 含糊其辞会让人在错误的方向上试。
     */
    static String extractSummary(AssistantTurn turn) {
        if (turn == null) throw new IllegalStateException("压缩未完成：模型没有返回任何内容");
        if ("max_tokens".equals(turn.stopReason)) {
            throw new IllegalStateException("压缩未完成：摘要用满了输出上限，请调高最大输出 token 再试");
        }
        if (!turn.toolCalls.isEmpty()) {
            throw new IllegalStateException("压缩未完成：摘要模型不该调用工具，但它调用了");
        }

        StringBuilder text = new StringBuilder();
        for (JSONObject block : JsonItems.of(turn.content)) {
            if (!"text".equals(block.optString("type", ""))) continue;
            String value = block.optString("text", "");
            if (value.isEmpty()) continue;
            if (text.length() > 0) text.append('\n');
            text.append(value);
        }

        String formatted = formatSummary(text.toString());
        if (formatted.isEmpty()) throw new IllegalStateException("压缩未完成：模型响应里没有可用的摘要文本");
        String lower = formatted.toLowerCase(Locale.US);
        if (lower.startsWith("api error") || lower.startsWith("error:")) {
            // 有些提供方会把错误正文当成正常回复返回。当成摘要存下去，上下文里就留下了一条错误。
            throw new IllegalStateException("压缩未完成：" + formatted);
        }
        return formatted;
    }

    /**
     * 清洗摘要文本。
     *
     * <p>按顺序做四件事：去掉草稿段、如果用了 {@code <summary>} 标签就只取标签里的、
     * 清掉残留的标签、把连续空行压成一行空行。最后一步不只是好看 ——
     * 它直接影响摘要占多少 token。
     */
    static String formatSummary(String raw) {
        String value = raw == null ? "" : raw.trim();
        value = ANALYSIS_BLOCK.matcher(value).replaceAll("").trim();

        Matcher match = SUMMARY_BLOCK.matcher(value);
        if (match.find()) value = match.group(1).trim();
        value = SUMMARY_TAG.matcher(value).replaceAll("").trim();
        return BLANK_LINE_RUNS.matcher(value).replaceAll("\n\n");
    }

    /** 这次失败是不是「提示词超出模型窗口」。文案来自各家 API，不能翻译。 */
    static boolean isPromptTooLong(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) all.append(' ').append(current.getMessage());
        }
        String text = all.toString().toLowerCase(Locale.US);
        for (String marker : PROMPT_TOO_LONG_MARKERS) {
            if (text.contains(marker)) return true;
        }
        return text.contains("http 400") && text.contains("token");
    }

    // ------------------------------------------------------------ 摘要提示词

    /** 摘要要求的固定开头。三段分开是因为中间要插入「保不保留近期消息」那一句。 */
    private static final String PROMPT_HEAD =
            "CRITICAL: Respond with TEXT ONLY. Do not call any tool. Tool calls will be rejected.\n"
                    + "Your entire response must contain an <analysis> drafting block followed by one"
                    + " <summary> block.\n\n"
                    + "Create a detailed, technically accurate continuity summary of the conversation"
                    + " prefix above. ";
    private static final String PROMPT_KEEP_RECENT =
            "Newer messages are preserved verbatim after this summary, so summarize only the prefix you saw.\n";
    private static final String PROMPT_REPLACE_ALL =
            "The summary will replace the conversation shown above.\n";
    private static final String PROMPT_ANALYSIS =
            "In <analysis>, inspect the messages chronologically and verify requests, decisions, code,"
                    + " files, errors, fixes, user corrections, pending work, and exact current state.\n\n";
    private static final String PROMPT_SECTIONS_HEADER = "In <summary>, use these sections:\n";
    private static final String PROMPT_FOOTER =
            "Preserve concrete commands, file paths, identifiers, constraints, test results, and"
                    + " unresolved blockers. Do not invent completion. Match the conversation's language"
                    + " where practical.";
    private static final String PROMPT_REMINDER =
            "\n\nREMINDER: no tools. Return only <analysis>...</analysis><summary>...</summary>.";

    /**
     * 摘要要覆盖的九个方面。
     *
     * <p>做成数组而不是九行 append：它是一份清单，要能一眼看出「有没有漏掉某一类信息」。
     * 每一节都对应一种只有当事人才知道、但下一轮必须知道的事实。
     */
    private static final String[] SUMMARY_SECTIONS = {
            "1. Primary Request and Intent",
            "2. Key Technical Concepts and Decisions",
            "3. Files and Code Sections (paths, functions, edits, and essential snippets)",
            "4. Errors, Failed Attempts, and Fixes",
            "5. Problem Solving and Verified Results",
            "6. All User Messages (exclude tool results)",
            "7. Pending Tasks",
            "8. Current Work and Exact State",
            "9. Direct Next Step, only if it follows the latest request"};

    private static String compactPrompt(String customInstructions, boolean recentPreserved) {
        return compactPrompt(customInstructions, recentPreserved, "");
    }

    /**
     * @param promptHead 用户自定义的开场段。空串 = 用 {@link #PROMPT_HEAD}。
     *
     * <p>自定义时会补一个分隔符：{@link #PROMPT_HEAD} 原文以空格结尾，直接接后面那句
     * 「Newer messages are preserved…」读起来是一句话；用户自己写的那段多半以句号结尾，
     * 不补分隔就会出现「…above.Newer messages…」这种粘在一起的文本，
     * 而模型会把它当成一个畸形的词而不是两句话。
     */
    private static String compactPrompt(String customInstructions, boolean recentPreserved,
                                        String promptHead) {
        StringBuilder prompt = new StringBuilder(promptHead(promptHead));
        prompt.append(recentPreserved ? PROMPT_KEEP_RECENT : PROMPT_REPLACE_ALL);
        prompt.append(PROMPT_ANALYSIS);
        prompt.append(PROMPT_SECTIONS_HEADER);
        for (String section : SUMMARY_SECTIONS) prompt.append(section).append('\n');
        prompt.append('\n');
        prompt.append(PROMPT_FOOTER);

        String custom = customInstructions == null ? "" : customInstructions.trim();
        if (!custom.isEmpty()) {
            prompt.append("\n\n<additional_compact_instructions>\n")
                    .append(headTail(custom, CUSTOM_INSTRUCTION_CHARS))
                    .append("\n</additional_compact_instructions>");
        }
        prompt.append(PROMPT_REMINDER);
        return prompt.toString();
    }

    /** 见 {@link #compactPrompt(String, boolean, String)} 的参数说明。 */
    private static String promptHead(String customized) {
        String value = customized == null ? "" : customized.trim();
        if (value.isEmpty()) return PROMPT_HEAD;
        return Character.isWhitespace(value.charAt(value.length() - 1)) ? value : value + "\n";
    }

    // ------------------------------------------------------------ 摘要请求的缩减

    /**
     * 让被压缩的那一段小到能塞进摘要请求里。
     *
     * <p>三种缩减力度，按重试次数递增。第三次额外做一件更狠的事：把最老的若干轮
     * <b>整段换成一份确定性摘要</b>（{@code <overflow_digest>}）。用摘要是因为它不依赖模型 ——
     * 这一步的目标是「保证请求能发出去」，不是「摘要得多好」。
     */
    private static JSONArray reduceForSummary(JSONArray prefix, int attempt) throws Exception {
        int limitIndex = Math.min(attempt, SUMMARY_TEXT_LIMITS.length - 1);
        int textLimit = SUMMARY_TEXT_LIMITS[limitIndex];
        int toolLimit = SUMMARY_TOOL_LIMITS[limitIndex];

        JSONArray selected = prefix;
        String overflowDigest = "";
        if (attempt >= 2 && prefix.length() > 1) {
            List<Integer> roundStarts = apiRoundStarts(prefix);
            int roundsToDrop = Math.min(Math.max(1, roundStarts.size() / 5),
                    Math.max(0, roundStarts.size() - 1));
            if (roundsToDrop > 0) {
                int dropEnd = roundStarts.get(roundsToDrop);
                overflowDigest = localDigest(prefix, 0, dropEnd, OVERFLOW_DIGEST_CHARS);
                selected = slice(prefix, dropEnd, prefix.length());
            }
        }

        JSONArray out = new JSONArray();
        if (!overflowDigest.isEmpty()) {
            out.put(message("user", new JSONArray().put(textBlock(
                    "<overflow_digest>Exact oldest rounds exceeded the provider request limit."
                            + " This deterministic digest preserves their recoverable facts:\n"
                            + overflowDigest + "\n</overflow_digest>"))));
        }
        for (JSONObject sourceMessage : JsonItems.of(selected)) {
            JSONArray content = shrinkBlocks(sourceMessage.optJSONArray("content"), textLimit, toolLimit);
            if (content.length() == 0) content.put(textBlock("[non-text assistant state omitted]"));
            out.put(message(sourceMessage.optString("role", "user"), content));
        }
        return out;
    }

    /**
     * 缩减一条消息的内容块。
     *
     * <p>逐类处理，因为每一类的「什么可以丢」不一样：正文可以掐头去尾、工具入参可以换成
     * 占位说明、图片只能写成一行说明、思考过程对摘要没有用（直接丢）。
     */
    private static JSONArray shrinkBlocks(JSONArray sourceContent, int textLimit, int toolLimit) throws Exception {
        JSONArray content = new JSONArray();
        if (sourceContent == null) return content;

        for (JSONObject block : JsonItems.of(sourceContent)) {
            String type = block.optString("type", "");

            if ("thinking".equals(type)) continue;
            if ("image".equals(type)) {
                content.put(textBlock("[attached image omitted from compaction request]"));
                continue;
            }
            if ("text".equals(type)) {
                content.put(textBlock(headTail(block.optString("text", ""), textLimit)));
                continue;
            }
            if ("tool_result".equals(type)) {
                JSONObject copy = new JSONObject(block.toString());
                copy.put("content", headTail(String.valueOf(block.opt("content")), toolLimit));
                content.put(copy);
                continue;
            }
            if ("tool_use".equals(type)) {
                JSONObject copy = new JSONObject(block.toString());
                String input = String.valueOf(block.opt("input"));
                if (input.length() > toolLimit) {
                    // 换成带标记的对象而不是一段截断的字符串：提供方要求 input 是对象，
                    // 而这个标记也让「这里的入参被压缩过」在历史里可查。
                    copy.put("input", new JSONObject()
                            .put("_zhicode_compacted_input", headTail(input, toolLimit)));
                }
                content.put(copy);
                continue;
            }
            content.put(textBlock("[" + type + " block: " + headTail(block.toString(), toolLimit) + "]"));
        }
        return content;
    }

    // ------------------------------------------------------------ 轮次划分

    /**
     * 一次「API 回合」从哪条消息开始。
     *
     * <p>划分依据是「带真实内容的 user 消息」：工具结果也是 user 角色，但它不是新的回合，
     * 而是上一个回合的延续。切点必须落在真正的回合开头，否则会把调用与结果拆散。
     */
    private static List<Integer> apiRoundStarts(JSONArray messages) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 1; i < messages.length(); i++) {
            if (!isUserRequest(messages.optJSONObject(i))) continue;
            if (starts.get(starts.size() - 1) != i) starts.add(i);
        }
        return starts;
    }

    /** 这条消息是不是「人在说话」。只有正文或图片才算，工具结果不算。 */
    private static boolean isUserRequest(JSONObject message) {
        if (message == null || !"user".equals(message.optString("role", ""))) return false;
        JSONArray content = message.optJSONArray("content");
        if (content == null) return false;
        for (JSONObject block : JsonItems.of(content)) {
            String type = block.optString("type", "");
            if ("text".equals(type) || "image".equals(type)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 确定性摘要

    /** 把一段历史压成一份确定性摘要（不用模型，因此一定能生成出来）。 */
    private static String localDigest(JSONArray messages, int from, int to, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, from); i < Math.min(messages.length(), to); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String part = digestContent(message.optJSONArray("content"), DIGEST_ENTRY_CHARS);
            if (part.isEmpty()) continue;
            out.append(message.optString("role", "message")).append(": ").append(part).append('\n');
            if (out.length() >= maxChars) break;
        }
        return headTail(out.toString(), maxChars);
    }

    /** 一条消息的可摘内容：正文照抄，工具调用/结果写成一行说明，图片只有一个标记。 */
    private static String digestContent(JSONArray content, int maxChars) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        for (JSONObject block : JsonItems.of(content)) {
            String type = block.optString("type", "");
            String value = "";
            if ("text".equals(type)) value = block.optString("text", "");
            else if ("tool_use".equals(type)) {
                value = "tool " + block.optString("name", "") + " " + String.valueOf(block.opt("input"));
            } else if ("tool_result".equals(type)) {
                value = "tool result " + String.valueOf(block.opt("content"));
            } else if ("image".equals(type)) {
                value = "[attached image]";
            }
            if (value.trim().isEmpty()) continue;
            if (out.length() > 0) out.append(" | ");
            out.append(value.trim());
            if (out.length() >= maxChars) break;
        }
        return headTail(out.toString().replace('\n', ' ').replaceAll("\\s+", " "), maxChars);
    }

    // ------------------------------------------------------------ 估算

    static int roughTokens(JSONArray messages) {
        return roughTokens(messages, 0, messages == null ? 0 : messages.length());
    }

    /** 估算一段消息占多少 token。用于阈值判断与界面显示，不要求精确。 */
    static int roughTokens(JSONArray messages, int from, int to) {
        if (messages == null) return 0;
        long tokens = 0;
        int start = Math.max(0, from);
        int end = Math.min(messages.length(), Math.max(start, to));
        for (int i = start; i < end; i++) tokens += roughMessageTokens(messages.optJSONObject(i));
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, tokens));
    }

    /** 累加和，供「从尾部往前累加到目标」这类计算使用（避免每轮重算一遍）。 */
    private static int[] cumulativeRoughTokens(JSONArray messages) {
        int length = messages == null ? 0 : messages.length();
        int[] cumulative = new int[length + 1];
        long total = 0;
        for (int i = 0; i < length; i++) {
            total = Math.min(Integer.MAX_VALUE, total + roughMessageTokens(messages.optJSONObject(i)));
            cumulative[i + 1] = (int) total;
        }
        return cumulative;
    }

    private static int roughMessageTokens(JSONObject message) {
        if (message == null) return 0;
        JSONArray content = message.optJSONArray("content");
        if (content == null) {
            // 非标准消息：整个 content 字段按一段文本算。
            return TOKENS_PER_MESSAGE + roughTextTokens(String.valueOf(message.opt("content")));
        }
        long tokens = TOKENS_PER_MESSAGE;
        for (JSONObject block : JsonItems.of(content)) {
            tokens += TOKENS_PER_BLOCK + roughBlockTokens(block);
        }
        return (int) Math.min(Integer.MAX_VALUE, tokens);
    }

    private static long roughBlockTokens(JSONObject block) {
        String type = block.optString("type", "");
        if ("text".equals(type)) return roughTextTokens(block.optString("text", ""));
        if ("thinking".equals(type)) return roughTextTokens(block.optString("thinking", ""));
        if ("tool_use".equals(type)) {
            return roughTextTokens(block.optString("name", ""))
                    + roughJsonTokens(String.valueOf(block.opt("input")));
        }
        if ("tool_result".equals(type)) return roughJsonTokens(String.valueOf(block.opt("content")));
        if ("function_call".equals(type)) {
            return roughTextTokens(block.optString("name", ""))
                    + roughJsonTokens(block.optString("arguments", ""));
        }
        if ("image".equals(type)) return IMAGE_TOKENS;
        return roughTextTokens(block.toString());
    }

    /**
     * 估算一段文本的 token 数。
     *
     * <p>英文约 4 个字符 1 个 token，而中文一个字就接近 1 个 token —— 用 chars/4 去算中文
     * 会严重低估，于是压缩永远不会触发，直到 API 报错。所以分开数：
     * ASCII 按 4 字符一个，非 ASCII 按 1 字符一个（空白不算）。
     */
    static int roughTextTokens(String value) {
        if (value == null || value.isEmpty()) return 0;
        long ascii = 0;
        long nonAscii = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f) ascii++;
            else if (!Character.isWhitespace(codePoint)) nonAscii++;
        }
        return (int) Math.min(Integer.MAX_VALUE, (ascii + 3L) / 4L + nonAscii);
    }

    /**
     * 估算一段 JSON 的体积。
     *
     * <p>取「按文本算」与「按字符数一半算」的较大值：JSON 里有大量单字符的结构符号，
     * 只按文本算会低估。
     */
    private static int roughJsonTokens(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return 0;
        return Math.max(roughTextTokens(value), (value.length() + 1) / 2);
    }

    // ------------------------------------------------------------ 小工具

    /**
     * 掐头去尾保留中间一段省略提示。
     *
     * <p>头尾都留而不是只留头部：工具输出与日志的关键信息常常在结尾（退出码、错误行），
     * 只留开头会正好丢掉最需要的那部分。
     */
    private static String headTail(String value, int maxChars) {
        String text = value == null ? "" : value;
        if (maxChars <= 0 || text.length() <= maxChars) return text;
        int usable = Math.max(16, maxChars - TRUNCATION_NOTICE_CHARS);
        int head = (usable * 2) / 3;
        int tail = usable - head;
        return text.substring(0, head)
                + "\n[… " + (text.length() - head - tail) + " chars omitted for compaction request …]\n"
                + text.substring(text.length() - tail);
    }

    private static JSONObject textBlock(String text) throws Exception {
        return new JSONObject().put("type", "text").put("text", text == null ? "" : text);
    }

    private static JSONObject message(String role, JSONArray content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONArray slice(JSONArray source, int from, int to) throws Exception {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        int start = Math.max(0, from);
        int end = Math.min(source.length(), to);
        for (int i = start; i < end; i++) out.put(source.getJSONObject(i));
        return out;
    }

    private static JSONArray cloneArray(JSONArray source) throws Exception {
        return source == null ? new JSONArray() : new JSONArray(source.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
