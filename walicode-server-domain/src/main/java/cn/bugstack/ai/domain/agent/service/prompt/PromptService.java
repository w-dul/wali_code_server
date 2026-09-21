package cn.bugstack.ai.domain.agent.service.prompt;

import cn.bugstack.ai.domain.agent.model.valobj.prompt.PromptContextVO;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import cn.bugstack.ai.domain.agent.service.prompt.dynamic.MilestoneTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 提示词服务
 * <p>
 * 组合 DynamicPromptBuilder、MilestoneTracker、IChatContextService，
 * 向 case 层提供统一的提示词领域能力。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/5 22:18
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    @Resource
    private MilestoneTracker milestoneTracker;

    @Resource
    private IChatContextService chatContextService;

    @Override
    public void detectAndRecordMilestone(String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String sessionId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String projectName, String projectRootPath) {
        // 1. 通过 ChatContextService 采集上下文
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(sessionId, "userId_placeholder", terminalSessionId, messageHistory);
        
        // 追加来自 Case 层的 recentCommands
        promptContextVO.setRecentCommands(recentCommands);

        // 追加当前工程上下文（由前端注入）
        if (projectName != null && !projectName.isBlank()) {
            promptContextVO.setProjectName(projectName);
        }
        if (projectRootPath != null && !projectRootPath.isBlank()) {
            promptContextVO.setProjectRootPath(projectRootPath);
        }

        // 2. 生成消息前缀
        String prefix = dynamicPromptBuilder.buildMessagePrefix(promptContextVO);

        if (prefix.isEmpty()) {
            return userMessage;
        }

        return prefix + "\n---\n" + userMessage;
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }
}
