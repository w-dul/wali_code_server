package cn.bugstack.ai.domain.agent.service.context.provider;

import cn.bugstack.ai.domain.agent.service.memory.CoreMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心记忆上下文提供者
 * <p>
 * 对标 Android memoryService.ts 的 CoreMemory 注入：
 * - 从最后一条 user 消息提取文本作为 query
 * - 调用 CoreMemoryService.formatMemoriesForPrompt(query) 做相关性过滤
 * - 注入到 PromptContextVO 中供 DynamicPromptBuilder 使用
 * <p>
 * 与 MilestoneProvider 的关系：
 * - MilestoneProvider 注入事件（TASK_CHANGE/ERROR 等，即时性）
 * - CoreMemoryProvider 注入长期知识（规则/偏好/纠正，跨会话性）
 *
 * @author 教学版-WaLiCode
 * 2026/6/26
 */
@Slf4j
@Component
public class CoreMemoryProvider implements ContextProvider {

    @Resource
    private CoreMemoryService coreMemoryService;

    @Override public String getName() { return "coreMemory"; }
    @Override public int getOrder() { return 25; }  // 在 milestone(30) 之前
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // 从最后一条 user 消息提取 query（对标 Android ai.ts 的逻辑）
        String query = extractLastUserMessage(messageHistory);
        if (query != null && query.length() > 200) {
            query = query.substring(0, 200);  // 限制 query 长度
        }

        String memoriesXml = coreMemoryService.formatMemoriesForPrompt(userId, query);
        result.put("coreMemories", memoriesXml);

        log.debug("核心记忆注入: session={}, queryLen={}, memoriesLen={}",
                sessionId, query != null ? query.length() : 0, memoriesXml.length());

        return result;
    }

    /**
     * 从消息历史中提取最后一条 user 消息的文本内容
     */
    private String extractLastUserMessage(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) return null;

        // 反向查找最后一条 user 消息
        for (int i = messageHistory.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messageHistory.get(i);
            Object role = msg.get("role");
            if ("user".equals(role)) {
                Object content = msg.get("content");
                if (content instanceof String) {
                    return (String) content;
                }
            }
        }
        return null;
    }
}
