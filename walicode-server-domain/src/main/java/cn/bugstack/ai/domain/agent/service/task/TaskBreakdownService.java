package cn.bugstack.ai.domain.agent.service.task;

import cn.bugstack.ai.domain.agent.model.valobj.task.TaskBreakdownVO;
import cn.bugstack.ai.domain.agent.service.ITaskBreakdownService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 任务拆解服务实现
 *
 * <p>基于规则 + LLM 两层判断：
 * 1. 规则层：快速检测复杂任务关键词（部署、架构、重构、迁移等），<1ms
 * 2. LLM 层：调用弱模型生成结构化拆解方案，100-500ms
 *
 * <p>拆解结果缓存：每个 sessionId 只拆解一次，后续轮次复用。
 *
 * @author walissh dev
 * 2026/6/19
 */
@Slf4j
@Service
public class TaskBreakdownService implements ITaskBreakdownService {

    @Resource
    private ObjectMapper objectMapper;

    @Value("${intent-ai-api.base-url:https://maas-coding-api.cn-huabei-1.xf-yun.com}")
    private String apiHost;

    @Value("${intent-ai-api.api-key:4015c530cf0d7bdac896f58e4d9fd029:NTYwODNkNzk4ZjdkYzFlYTJlNTQyZDg1}")
    private String apiKey;

    @Value("${intent-ai-api.completions-path:v2/chat/completions}")
    private String completionsPath;

    @Value("${intent-ai-api.chat-model.model:xopglm51}")
    private String model;

    private ChatModel weakChatModel;

    private final Map<String, TaskBreakdownVO> breakdownMap = new ConcurrentHashMap<>();

    private static final Pattern COMPLEX_TASK_PATTERN = Pattern.compile(
            "(?i)(部署|安装并配置|搭建|迁移|重构|架构|CI/CD|持续集成|" +
            "deploy.*and.*config|set.*up|migrate|refactor|build.*pipeline|" +
            "从零|完整|全流程|step.by.step|然后|接着|之后|最后)"
    );

    private static final int MIN_SUB_TASKS = 2;
    private static final int MAX_SUB_TASKS = 10;

    private static final String BREAKDOWN_SYSTEM_PROMPT = """
            你是一个任务分析专家。将用户的复杂运维/开发请求拆解为有序的子任务列表。

            返回纯 JSON 格式（不要 markdown 代码块），结构如下：
            {
              "summary": "一句话描述整体计划",
              "subTasks": [
                {
                  "title": "子任务标题（简洁，不超过20字）",
                  "description": "详细描述要做什么",
                  "expectedTools": "预期使用的工具，如 executeCommand, writeFile"
                }
              ]
            }

            拆解原则：
            1. 每个子任务应该是可独立执行的最小单元
            2. 子任务之间有明确的顺序关系
            3. 子任务数量在 2-8 个之间
            4. 优先按"检查环境 → 执行操作 → 验证结果"的闭环拆分
            5. 如果任务简单（单步可完成），返回空 subTasks 数组

            只返回 JSON，不要任何其他文字。
            """;

    @PostConstruct
    public void init() {
        try {
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(apiHost)
                    .apiKey(apiKey)
                    .completionsPath(completionsPath)
                    .build();

            this.weakChatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.2)
                            .build())
                    .build();
            log.info("TaskBreakdownService 弱模型初始化完成");
        } catch (Exception e) {
            log.warn("TaskBreakdownService 弱模型初始化失败，将仅使用规则层: {}", e.getMessage());
        }
    }

    @Override
    public boolean shouldBreakdown(String userMessage, String sessionId) {
        if (userMessage == null || userMessage.isBlank()) return false;
        if (breakdownMap.containsKey(sessionId)) return false;

        Matcher matcher = COMPLEX_TASK_PATTERN.matcher(userMessage);
        boolean ruleMatch = matcher.find();

        long stepSignals = Arrays.stream(new String[]{"然后", "接着", "之后", "最后", "and then", "after that", "finally"})
                .filter(userMessage::contains)
                .count();

        boolean need = ruleMatch || stepSignals >= 1;
        log.info("任务拆解检测: sessionId={}, ruleMatch={}, stepSignals={}, result={}",
                sessionId, ruleMatch, stepSignals, need);
        return need;
    }

    @Override
    public TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                                      List<Map<String, Object>> messageHistory) {
        log.info("执行任务拆解: sessionId={}, agentId={}", sessionId, agentId);

        TaskBreakdownVO result = new TaskBreakdownVO();
        result.setOriginalRequest(userMessage);
        result.setNeedConfirmation(true);

        if (weakChatModel == null) {
            log.warn("弱模型未初始化，跳过 LLM 拆解");
            result.setSummary("弱模型不可用，直接执行");
            result.setSubTasks(Collections.emptyList());
            return result;
        }

        try {
            String prompt = BREAKDOWN_SYSTEM_PROMPT + "\n\n用户请求: " + userMessage;

            org.springframework.ai.chat.prompt.Prompt chatPrompt =
                    new org.springframework.ai.chat.prompt.Prompt(prompt);
            org.springframework.ai.chat.model.ChatResponse response = weakChatModel.call(chatPrompt);
            String content = response.getResult().getOutput().getText();

            log.info("任务拆解 LLM 响应: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

            String json = extractJson(content);
            if (json == null) {
                log.warn("LLM 响应中未找到有效 JSON，跳过拆解");
                result.setSummary("无法解析拆解结果，直接执行");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String summary = (String) parsed.get("summary");
            List<Map<String, Object>> subTaskList = (List<Map<String, Object>>) parsed.get("subTasks");

            if (subTaskList == null || subTaskList.isEmpty()) {
                log.info("LLM 判定任务简单，无需拆解");
                result.setSummary(summary != null ? summary : "任务简单，直接执行");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            List<TaskBreakdownVO.SubTask> subTasks = new ArrayList<>();
            for (int i = 0; i < subTaskList.size() && i < MAX_SUB_TASKS; i++) {
                Map<String, Object> st = subTaskList.get(i);
                subTasks.add(TaskBreakdownVO.SubTask.builder()
                        .index(i + 1)
                        .title((String) st.getOrDefault("title", "子任务" + (i + 1)))
                        .description((String) st.getOrDefault("description", ""))
                        .expectedTools((String) st.getOrDefault("expectedTools", ""))
                        .status("pending")
                        .build());
            }

            if (subTasks.size() < MIN_SUB_TASKS) {
                log.info("拆解子任务数 {} < {}，跳过拆解", subTasks.size(), MIN_SUB_TASKS);
                result.setSummary("任务简单，直接执行");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            result.setSummary(summary);
            result.setSubTasks(subTasks);
            breakdownMap.put(sessionId, result);

            log.info("任务拆解完成: sessionId={}, subTasks={}", sessionId, subTasks.size());
            return result;

        } catch (Exception e) {
            log.error("任务拆解失败", e);
            result.setSummary("拆解失败: " + e.getMessage());
            result.setSubTasks(Collections.emptyList());
            return result;
        }
    }

    @Override
    public void updateSubTaskStatus(String sessionId, int subTaskIndex, String status, String result) {
        TaskBreakdownVO breakdown = breakdownMap.get(sessionId);
        if (breakdown == null || breakdown.getSubTasks() == null) return;

        for (TaskBreakdownVO.SubTask st : breakdown.getSubTasks()) {
            if (st.getIndex() == subTaskIndex) {
                st.setStatus(status);
                if (result != null) {
                    st.setResult(result);
                }
                log.info("子任务状态更新: sessionId={}, index={}, status={}", sessionId, subTaskIndex, status);
                break;
            }
        }
    }

    @Override
    public TaskBreakdownVO getBreakdown(String sessionId) {
        return breakdownMap.get(sessionId);
    }

    @Override
    public void clearBreakdown(String sessionId) {
        breakdownMap.remove(sessionId);
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) return null;

        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception ignored) {
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String extracted = trimmed.substring(start, end + 1);
            try {
                objectMapper.readTree(extracted);
                return extracted;
            } catch (Exception ignored) {
            }
        }

        return null;
    }

}
