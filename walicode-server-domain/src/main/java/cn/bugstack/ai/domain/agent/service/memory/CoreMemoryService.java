package cn.bugstack.ai.domain.agent.service.memory;

import cn.bugstack.ai.domain.agent.adapter.repository.ICoreMemoryRepository;
import cn.bugstack.ai.domain.agent.model.valobj.memory.CoreMemoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 核心记忆服务
 * <p>
 * 对标 Android memoryService.ts 的 CoreMemory 能力：
 * 1. 添加记忆（对话内学习）
 * 2. 相关性过滤（按关键词匹配查询）
 * 3. 格式化为提示词注入格式
 * 4. 冷记忆淘汰
 * <p>
 * 与 MilestoneTracker 的关系：
 * - MilestoneTracker 记录事件（TASK_CHANGE/ERROR 等）
 * - CoreMemoryService 存储提炼后的长期知识（规则/偏好/纠正）
 * - MilestoneTracker 的 USER_CORRECTION 事件可触发 CoreMemoryService 写入
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
@Slf4j
@Service
public class CoreMemoryService {

    @Resource
    private ICoreMemoryRepository coreMemoryRepository;

    /** 最大记忆条数 */
    private static final int MAX_MEMORIES = 50;

    /** 相关性匹配最小命中关键词数 */
    private static final int MIN_KEYWORD_MATCH = 1;

    /** 默认优先级 */
    private static final int DEFAULT_PRIORITY = 3;

    /** 纠正类记忆优先级（更高） */
    private static final int CORRECTION_PRIORITY = 5;

    /**
     * 添加核心记忆
     *
     * @param scope      作用域（user / session）
     * @param category   分类（Rule / Preference / Decision / Correction / Fact）
     * @param title      简短标题
     * @param keywords   关键词（逗号分隔）
     * @param content    正文内容
     * @param priority   优先级（1-5）
     * @param sourceSid  来源会话 ID
     */
    public void addMemory(String scope, String category, String title,
                          String keywords, String content, int priority, String sourceSid) {
        CoreMemoryVO memory = CoreMemoryVO.builder()
                .scope(scope)
                .category(category)
                .title(title)
                .keywords(keywords)
                .content(content)
                .priority(priority)
                .createdAt(System.currentTimeMillis())
                .lastUsedAt(System.currentTimeMillis())
                .useCount(1)
                .sourceSessionId(sourceSid)
                .build();

        coreMemoryRepository.addMemory(memory);
        log.info("核心记忆已添加: scope={}, category={}, title={}, priority={}",
                scope, category, title, priority);

        // 淘汰冷记忆，保持总量在 MAX_MEMORIES 以内
        coreMemoryRepository.evictColdMemories("default", MAX_MEMORIES);
    }

    /**
     * 添加纠正类记忆（对话内学习专用）
     * <p>
     * 从 MilestoneTracker 的 USER_CORRECTION 事件触发
     *
     * @param originalText 用户纠正原文
     * @param sourceSid    来源会话 ID
     */
    public void addCorrectionMemory(String originalText, String sourceSid) {
        // 提取关键词（简单分词：按空格和常见分隔符拆分）
        String keywords = extractKeywords(originalText);

        // 生成标题和内容
        String title = "用户纠正: " + truncate(originalText, 50);
        String content = buildCorrectionContent(originalText);

        addMemory("user", "Correction", title, keywords, content, CORRECTION_PRIORITY, sourceSid);
    }

    /**
     * 获取与当前查询相关的核心记忆，并格式化为提示词注入格式
     * <p>
     * 对标 Android memoryService.ts formatMemoriesForPrompt(query)
     *
     * @param userId 用户 ID
     * @param query  当前用户消息（用于相关性过滤）
     * @return 格式化后的记忆文本（XML 格式，便于 LLM 解析）
     */
    public String formatMemoriesForPrompt(String userId, String query) {
        List<CoreMemoryVO> memories;

        if (query != null && !query.isBlank()) {
            // 有 query 时做相关性过滤
            memories = coreMemoryRepository.getRelevantMemories(userId, query, 10);
        } else {
            // 无 query 时取最近的高优先级记忆
            memories = coreMemoryRepository.getAllMemories(userId, 5);
        }

        if (memories.isEmpty()) {
            return "<core_memories>\n<no_relevant_memories>\n当前上下文无相关核心记忆。请忽略记忆板块，基于当前需求独立分析。\n</no_relevant_memories>\n</core_memories>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<core_memories>\n");
        for (CoreMemoryVO m : memories) {
            sb.append("<memory category=\"").append(m.getCategory())
              .append("\" priority=\"").append(m.getPriority())
              .append("\" keywords=\"").append(m.getKeywords())
              .append("\">\n");
            sb.append("  <title>").append(m.getTitle()).append("</title>\n");
            sb.append("  <content>").append(m.getContent()).append("</content>\n");
            sb.append("</memory>\n");
        }
        sb.append("</core_memories>");

        // 更新使用状态
        for (CoreMemoryVO m : memories) {
            coreMemoryRepository.touchMemory(m.getId());
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    private String extractKeywords(String text) {
        if (text == null || text.isBlank()) return "";

        // 简单分词：移除常见否定词，提取实质内容关键词
        Set<String> negationWords = Set.of("不要", "别", "不对", "不是", "错了", "不应该", "别再", "换", "改成");

        // 按空格、逗号、中文标点分词
        String[] tokens = text.split("[\\s,，。.!！?？;；:：/\\\\|]+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (negationWords.contains(token)) continue;
            keywords.add(token.toLowerCase());
        }

        // 如果过滤后无关键词，保留原文前20字符
        if (keywords.isEmpty()) {
            return truncate(text, 20).toLowerCase();
        }

        return keywords.stream().collect(Collectors.joining(","));
    }

    private String buildCorrectionContent(String originalText) {
        return "用户明确纠正了之前的行为。原文: \"" + truncate(originalText, 200)
                + "\"。后续执行时应遵守此纠正规则。";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
