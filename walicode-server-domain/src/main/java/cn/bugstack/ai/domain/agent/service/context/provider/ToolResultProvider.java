package cn.bugstack.ai.domain.agent.service.context.provider;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolResultProvider implements ContextProvider {
    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    // ── 截断策略常量 ──
    /** 当前轮次工具结果保留的最大长度 */
    private static final int CURRENT_RESULT_MAX_LENGTH = 2000;
    /** 历史轮次工具结果保留的头部长度 */
    private static final int HISTORY_HEAD_LENGTH = 500;
    /** 历史轮次工具结果保留的尾部长度 */
    private static final int HISTORY_TAIL_LENGTH = 500;
    /** 结构化摘要中每类工具的最大长度 */
    private static final int CATEGORY_SUMMARY_MAX_LENGTH = 200;
    /** 视为「当前轮次」的最大索引偏移（最近的几条结果） */
    private static final int CURRENT_ROUND_COUNT = 5;

    @Override public String getName() { return "tool-result"; }
    @Override public int getOrder() { return 40; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) return result;

        // 懒摘要：有缓存直接返回，否则重新生成
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }

    public void pushResult(String sessionId, String toolName, String result) {
        // 即时截断：pushResult 时就截断原始结果，减少内存占用
        String truncated = truncateSmart(result, CURRENT_RESULT_MAX_LENGTH);
        results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
               .add(new ToolResultEntry(toolName, truncated));
        summaryCache.remove(sessionId);  // 失效摘要缓存
        log.debug("工具结果已缓存: session={}, tool={}, originalLen={}, truncatedLen={}",
                sessionId, toolName, result.length(), truncated.length());
    }

    /**
     * 结构化摘要生成
     * <p>
     * 改进点（对标 Android contextCompressor P1-4）:
     * 1. 区分当前轮 vs 历史轮，不同截断策略
     * 2. 按工具类型分组，每类独立摘要
     * 3. 结构化输出格式（5段）
     */
    private String generateSummary(List<ToolResultEntry> entries) {
        int total = entries.size();
        int currentStart = Math.max(0, total - CURRENT_ROUND_COUNT);
        List<ToolResultEntry> currentEntries = entries.subList(currentStart, total);
        List<ToolResultEntry> historyEntries = currentStart > 0 ? entries.subList(0, currentStart) : Collections.emptyList();

        StringBuilder sb = new StringBuilder();

        // ── 1. 总览 ──
        sb.append("[工具执行摘要]\n");
        sb.append("共执行 ").append(total).append(" 次工具调用");
        if (!historyEntries.isEmpty()) {
            sb.append("（历史 ").append(historyEntries.size()).append(" 次 + 当前 ").append(currentEntries.size()).append(" 次）");
        }
        sb.append("\n\n");

        // ── 2. 按工具类型分组 ──
        Map<String, List<ToolResultEntry>> categorized = entries.stream()
                .collect(Collectors.groupingBy(ToolResultEntry::getToolName));

        sb.append("[调用分类]\n");
        for (Map.Entry<String, List<ToolResultEntry>> cat : categorized.entrySet()) {
            int catCount = cat.getValue().size();
            // 取该类别最后一条结果的摘要
            String lastResult = truncateSmart(cat.getValue().get(cat.getValue().size() - 1).getResult(), CATEGORY_SUMMARY_MAX_LENGTH);
            sb.append("- ").append(cat.getKey()).append("(").append(catCount).append("次): ").append(lastResult).append("\n");
        }
        sb.append("\n");

        // ── 3. 历史轮次（压缩展示） ──
        if (!historyEntries.isEmpty()) {
            sb.append("[历史轮次]\n");
            for (ToolResultEntry e : historyEntries) {
                sb.append("- ").append(e.getToolName()).append(": ")
                  .append(truncateWithHeadTail(e.getResult(), HISTORY_HEAD_LENGTH, HISTORY_TAIL_LENGTH)).append("\n");
            }
            sb.append("\n");
        }

        // ── 4. 当前轮次（完整展示） ──
        sb.append("[当前轮次]\n");
        for (ToolResultEntry e : currentEntries) {
            sb.append("- ").append(e.getToolName()).append(": ").append(e.getResult()).append("\n");
        }
        sb.append("\n");

        // ── 5. 关键发现 ──
        sb.append("[关键发现]\n");
        // 从当前轮结果中提取错误/关键信息
        for (ToolResultEntry e : currentEntries) {
            String r = e.getResult();
            if (r != null) {
                String lower = r.toLowerCase();
                if (lower.contains("error") || lower.contains("failed") || lower.contains("exception") || lower.contains("permission denied")) {
                    sb.append("- ⚠️ ").append(e.getToolName()).append(" 检测到错误\n");
                }
                if (lower.contains("success") || lower.contains("build success") || lower.contains("passed")) {
                    sb.append("- ✅ ").append(e.getToolName()).append(" 执行成功\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 智能截断：保留头部内容 + 截断标记
     */
    private String truncateSmart(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "... [已截断，原始 " + s.length() + " 字符]";
    }

    /**
     * 头尾截断：保留头部 + 尾部 + 中间省略标记
     */
    private String truncateWithHeadTail(String s, int headLen, int tailLen) {
        if (s == null) return "";
        if (s.length() <= headLen + tailLen + 50) return s;  // 太短无需截断
        String head = s.substring(0, headLen);
        String tail = s.substring(s.length() - tailLen);
        return head + "\n... [中间省略 " + (s.length() - headLen - tailLen) + " 字符]\n" + tail;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }
}
