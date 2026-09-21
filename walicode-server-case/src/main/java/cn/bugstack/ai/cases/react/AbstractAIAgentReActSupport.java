package cn.bugstack.ai.cases.react;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActEventDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * ReAct 支撑类（抽象基类）
 *
 * <p>参考 mobile-claw-case 的 AbstractAutoAgentSupport 设计，
 * 封装 ReAct 循环的通用能力：
 * - 上下文管理（DynamicContext）
 * - SSE 事件发射
 * - 工具调用结果解析
 * - 响应格式化
 *
 * <p>节点路由链：
 * RootNode → AiCallNode → ToolCallNode → (ToolResultNode) → [循环或完成]
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4 13:58
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 会话 → 终端会话 ID 映射
     */
    protected static final Map<String, String> sessionTerminalMapping = new ConcurrentHashMap<>();

    /**
     * 当前线程绑定的终端会话 ID（普通 ThreadLocal，避免线程池子线程泄露）
     */
    protected static final ThreadLocal<String> currentTerminalSession = new ThreadLocal<>();

    @Override
    protected void multiThread(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // 暂无异步预加载需求
    }

    /**
     * 通用的 Bean 获取
     */
    protected <T> T getBean(String beanName) {
        return applicationContext.getBean(beanName, (Class<T>) Object.class);
    }

    // ═══════════════════════════════════════════════════════════════
    //  上下文绑定（ThreadLocal + sessionMapping 双通道）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 设置当前线程的终端会话 ID
     */
    protected static void setCurrentTerminalSession(String terminalSessionId) {
        currentTerminalSession.set(terminalSessionId);
    }

    /**
     * 获取当前线程的终端会话 ID
     */
    protected static String getCurrentTerminalSession() {
        return currentTerminalSession.get();
    }

    /**
     * 清除当前线程的终端会话 ID
     */
    protected static void clearCurrentTerminalSession() {
        currentTerminalSession.remove();
    }

    /**
     * 绑定会话与终端会话
     */
    protected static void bindTerminalSession(String sessionId, String terminalSessionId) {
        if (sessionId != null && terminalSessionId != null) {
            sessionTerminalMapping.put(sessionId, terminalSessionId);
        }
    }

    /**
     * 获取会话绑定的终端会话 ID
     */
    protected static String getTerminalSession(String sessionId) {
        return sessionId != null ? sessionTerminalMapping.get(sessionId) : null;
    }

    /**
     * 解绑会话与终端会话
     */
    protected static void unbindTerminalSession(String sessionId) {
        if (sessionId != null) {
            sessionTerminalMapping.remove(sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE 事件发射辅助
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送文本事件
     * @return true=发送成功, false=发送失败（客户端已断开）
     */
    protected boolean sendTextEvent(ResponseBodyEmitter emitter, String content, String fullText, DefaultReActFactory.DynamicContext ctx) {
        try {
            // 流式过程中不做 normalize——ReactMarkdown 增量渲染足以处理中间态
            // normalize 只在 done 时做一次（UserFeedbackNode.buildFinalResult），确保幂等安全
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("text");
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            // 文本事件发送成功 → 刷新 LoopState 活跃时间，避免长文本生成期间 idle checker 误杀
            cn.bugstack.ai.domain.agent.service.engine.LoopState ls = ctx.getLoopState();
            if (ls != null) {
                ls.touch();
            }
            log.info("发送文本事件 {}", event);
            return true;
        } catch (Exception e) {
            log.warn("发送文本事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_text_failed");
            }
            return false;
        }
    }

    /**
     * 发送工具调用事件
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendToolCallEvent(ResponseBodyEmitter emitter, String toolCallId, String toolName, String status, DefaultReActFactory.DynamicContext ctx) {
        return sendToolCallEventWithArgs(emitter, toolCallId, toolName, "", status, ctx);
    }

    /**
     * 发送工具调用事件（含参数）
     * <p>ADK 自动执行模式下，工具名和参数需要从 stateDelta value 中提取，
     * 因为 ADK 用 agent 的 outputKey 作为 stateDelta key，而非工具名。
     *
     * @param toolArgs 工具调用参数（如命令字符串、文件路径等）
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendToolCallEventWithArgs(ResponseBodyEmitter emitter, String toolCallId, String toolName, String toolArgs, String status, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_call");
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setArgs(toolArgs);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送工具调用事件: toolName={}, args={}, status={}", toolName, toolArgs != null && toolArgs.length() > 100 ? toolArgs.substring(0, 100) + "..." : toolArgs, status);
            return true;
        } catch (Exception e) {
            log.warn("发送工具调用事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_tool_call_failed");
            }
            return false;
        }
    }

    /**
     * 发送工具结果事件
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendToolResultEvent(ResponseBodyEmitter emitter, String toolCallId, String content, String status, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_result");
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送工具结果事件 {}", event);
            return true;
        } catch (Exception e) {
            log.warn("发送工具结果事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_tool_result_failed");
            }
            return false;
        }
    }

    /**
     * 发送步数结束事件
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendRoundEndEvent(ResponseBodyEmitter emitter, int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setShouldContinue(shouldContinue);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_end");
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 round_end 事件 {}", event);
            return true;
        } catch (Exception e) {
            log.warn("发送 round_end 事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_round_end_failed");
            }
            return false;
        }
    }

    /**
     * 发送完成事件（含文件变更摘要）
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendDoneEvent(ResponseBodyEmitter emitter, ReActResultDTO result, DefaultReActFactory.DynamicContext ctx) {
        try {
            // done 事件的 content 也需规范化（ReActResultDTO.content 是最终回复文本）
            // done 事件 content 直接透传，不做额外处理
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("done");
            event.setContent(objectMapper.writeValueAsString(result));
            // 从工具调用结果中提取文件变更摘要
            ReActEventDTO.ChangeSummaryDTO changeSummary = extractChangeSummary(result);
            if (changeSummary != null) {
                event.setChangeSummary(changeSummary);
            }
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 done 事件 {}", event);
            return true;
        } catch (Exception e) {
            log.warn("发送 done 事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_done_failed");
            }
            return false;
        }
    }

    /**
     * 从 ReAct 结果中提取文件变更摘要
     * <p>扫描 toolResults 中的 path 字段，按操作类型分类
     */
    private ReActEventDTO.ChangeSummaryDTO extractChangeSummary(ReActResultDTO result) {
        if (result == null || result.getToolResults() == null || result.getToolResults().isEmpty()) {
            return null;
        }

        java.util.List<ReActEventDTO.ChangeFile> created = new java.util.ArrayList<>();
        java.util.List<ReActEventDTO.ChangeFile> modified = new java.util.ArrayList<>();
        java.util.List<ReActEventDTO.ChangeFile> deleted = new java.util.ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        for (java.util.Map<String, Object> toolResult : result.getToolResults()) {
            String toolName = String.valueOf(toolResult.getOrDefault("name", toolResult.getOrDefault("toolName", "")));
            String content = String.valueOf(toolResult.getOrDefault("content", ""));

            // 尝试从 content 中解析 path
            String path = extractPathFromContent(content);
            if (path == null || !seenPaths.add(path)) continue;

            String kind = classifyChange(toolName, path, content);
            ReActEventDTO.ChangeFile changeFile = new ReActEventDTO.ChangeFile();
            changeFile.setPath(path);
            changeFile.setKind(kind);

            // 简单行数统计
            int[] lineStats = estimateLineStats(content);
            changeFile.setAddedLines(lineStats[0]);
            changeFile.setRemovedLines(lineStats[1]);

            switch (kind) {
                case "create": created.add(changeFile); break;
                case "delete": deleted.add(changeFile); break;
                default: modified.add(changeFile); break;
            }
        }

        if (created.isEmpty() && modified.isEmpty() && deleted.isEmpty()) {
            return null;
        }

        ReActEventDTO.ChangeSummaryDTO summary = new ReActEventDTO.ChangeSummaryDTO();
        summary.setCreated(created);
        summary.setModified(modified);
        summary.setDeleted(deleted);
        summary.setDescription("共变更 " + (created.size() + modified.size() + deleted.size()) + " 个文件");
        return summary;
    }

    /**
     * 从工具结果内容中提取文件路径
     */
    private String extractPathFromContent(String content) {
        if (content == null || content.isEmpty()) return null;
        // 尝试 JSON 解析
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);
            if (node.has("path")) return node.get("path").asText();
            if (node.has("filePath")) return node.get("filePath").asText();
            if (node.has("file")) return node.get("file").asText();
        } catch (Exception ignored) {
        }
        // 正则匹配 "path": "..."
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"path\"\s*:\s*\"([^\"]+)\"").matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * 根据工具名和路径分类变更类型
     */
    private String classifyChange(String toolName, String path, String content) {
        String lowerTool = toolName.toLowerCase();
        String lowerContent = content.toLowerCase();
        if (lowerTool.contains("create") || lowerTool.contains("write") || lowerContent.contains("created") || lowerContent.contains("新建")) {
            return "create";
        }
        if (lowerTool.contains("delete") || lowerTool.contains("remove") || lowerContent.contains("deleted") || lowerContent.contains("删除")) {
            return "delete";
        }
        return "modify";
    }

    /**
     * 简单估计行数统计
     */
    private int[] estimateLineStats(String content) {
        if (content == null || content.isEmpty()) return new int[]{0, 0};
        int added = 0, removed = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        return new int[]{added, removed};
    }

    /**
     * 发送警告事件（非致命错误）
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendWarningEvent(ResponseBodyEmitter emitter, String message, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("warning");
            event.setContent(message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.warn("发送警告事件: {}", message);
            return true;
        } catch (Exception e) {
            log.warn("发送警告事件失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送子任务进度事件
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendTaskProgressEvent(ResponseBodyEmitter emitter, int subTaskIndex, String subTaskTitle,
                                          String status, int totalSubTasks, int completedSubTasks,
                                          DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.TaskProgress progress = new ReActEventDTO.TaskProgress();
            progress.setSubTaskIndex(subTaskIndex);
            progress.setSubTaskTitle(subTaskTitle);
            progress.setStatus(status);
            progress.setTotalSubTasks(totalSubTasks);
            progress.setCompletedSubTasks(completedSubTasks);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("task_progress");
            event.setTaskProgress(progress);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 task_progress 事件: index={}, status={}/{}, completed={}",
                    subTaskIndex, status, totalSubTasks, completedSubTasks);
            return true;
        } catch (Exception e) {
            log.warn("发送 task_progress 事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_task_progress_failed");
            }
            return false;
        }
    }

    /**
     * 发送权限确认事件（CONFIRM 级别工具需用户确认）
     * <p>前端收到后弹出 PermissionConfirmModal，用户确认/拒绝后回写结果
     *
     * @param confirmId  确认请求唯一 ID
     * @param toolName   工具名称
     * @param toolArgs   工具参数（命令/路径等）
     * @param riskLevel  风险等级：DENY / CONFIRM / ALLOW
     * @param reason     风险原因
     * @param timeoutMs  超时时间（毫秒），0=不超时
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendPermissionConfirmEvent(ResponseBodyEmitter emitter, String confirmId,
                                                 String toolName, String toolArgs, String riskLevel,
                                                 String reason, long timeoutMs,
                                                 DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.PermissionInfo permission = new ReActEventDTO.PermissionInfo();
            permission.setConfirmId(confirmId);
            permission.setToolName(toolName);
            permission.setToolArgs(toolArgs);
            permission.setRiskLevel(riskLevel);
            permission.setReason(reason);
            permission.setTimeoutMs(timeoutMs);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("permission_confirm");
            event.setPermission(permission);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 permission_confirm 事件: tool={}, risk={}, confirmId={}", toolName, riskLevel, confirmId);
            return true;
        } catch (Exception e) {
            log.warn("发送 permission_confirm 事件失败: {}, 标记任务取消", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_permission_confirm_failed");
            }
            return false;
        }
    }

    /**
     * 发送工具实时输出事件（长命令执行中的 stdout/stderr 增量）
     *
     * @param toolCallId  工具调用 ID（关联 tool_call 事件）
     * @param outputChunk 输出片段
     * @param ctx         动态上下文
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendToolOutputEvent(ResponseBodyEmitter emitter, String toolCallId,
                                          String outputChunk, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_output");
            event.setToolCallId(toolCallId);
            event.setOutputChunk(outputChunk);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            return true;
        } catch (Exception e) {
            log.warn("发送 tool_output 事件失败: {}", e.getMessage());
            // tool_output 失败不取消任务，仅丢弃该片段
            return false;
        }
    }

    /**
     * 发送状态更新事件（上下文压缩/降级/重连等）
     *
     * @param message 状态描述
     * @param ctx     动态上下文
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendStatusEvent(ResponseBodyEmitter emitter, String message,
                                      DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("status");
            event.setStatusMessage(message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 status 事件: {}", message);
            return true;
        } catch (Exception e) {
            log.warn("发送 status 事件失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 发送轮次开始事件
     *
     * @param roundIndex 当前轮次（从 1 开始）
     * @param ctx        动态上下文
     * @return true=发送成功, false=发送失败
     */
    protected boolean sendRoundStartEvent(ResponseBodyEmitter emitter, int roundIndex,
                                          DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_start");
            event.setContent(String.valueOf(roundIndex));
            emitter.send(objectMapper.writeValueAsString(event) + "\n", MediaType.APPLICATION_JSON);
            log.info("发送 round_start 事件: round={}", roundIndex);
            return true;
        } catch (Exception e) {
            log.warn("发送 round_start 事件失败: {}", e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具调用结果解析（参考 mobile-claw-case）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 解析 AI 响应中的 action 字符串
     * 兼容格式：
     * 1. JSON: ```json { "action": "..." } ```
     * 2. <answer>...</answer> 标签包裹的内容
     * 3. DSL: do(action=...) / finish(message=...)
     */
    protected String parseActionString(String response) {
        if (response == null || response.isBlank()) return null;

        String contentToParse = response;

        // 1. 提取 <answer>...</answer> 标签
        java.util.regex.Pattern answerPattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher answerMatcher = answerPattern.matcher(response);
        if (answerMatcher.find()) {
            contentToParse = answerMatcher.group(1).trim();
        }

        // 2. 尝试 JSON
        try {
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(contentToParse);
            if (jsonNode.has("action")) {
                return jsonNode.get("action").asText();
            }
        } catch (Exception ignored) {
        }

        // 3. 尝试 markdown JSON 代码块
        java.util.regex.Pattern jsonPattern = java.util.regex.Pattern.compile("```json(.*?)```", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher jsonMatcher = jsonPattern.matcher(contentToParse);
        if (jsonMatcher.find()) {
            try {
                com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(jsonMatcher.group(1).trim());
                if (jsonNode.has("action")) {
                    return jsonNode.get("action").asText();
                }
            } catch (Exception ignored) {
            }
        }

        // 4. 尝试 DSL
        java.util.regex.Pattern dslPattern = java.util.regex.Pattern.compile("(do|finish)\\s*\\(\\s*(action|message)\\s*=", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher dslMatcher = dslPattern.matcher(contentToParse);
        int lastStart = -1;
        while (dslMatcher.find()) {
            lastStart = dslMatcher.start();
        }
        if (lastStart != -1) {
            String action = contentToParse.substring(lastStart).trim();
            if (action.endsWith("```")) {
                action = action.substring(0, action.length() - 3).trim();
            }
            return action;
        }

        return null;
    }

    /**
     * 判断响应是否包含工具调用（用于决定是否继续循环）
     * 兼容两种格式：
     * - WaLiCode 风格：直接解析 tool_calls JSON
     * - mobile-claw-case 风格：解析 action 字符串
     */
    protected boolean hasToolCalls(String response) {
        if (response == null || response.isBlank()) return false;

        // 1. 检查 <answer> 标签内容
        java.util.regex.Pattern answerPattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher answerMatcher = answerPattern.matcher(response);
        if (answerMatcher.find()) {
            String content = answerMatcher.group(1).trim();
            return containsToolCallSign(content);
        }

        return containsToolCallSign(response);
    }

    private boolean containsToolCallSign(String content) {
        // 检查 do(...) / finish(...) DSL 模式
        if (java.util.regex.Pattern.compile("(do|finish)\\s*\\(").matcher(content).find()) {
            return true;
        }
        // 检查 tool_calls JSON 结构
        if (content.contains("tool_calls") || content.contains("\"action\"")) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否应该终止循环
     * 终止条件：检测到 finish / max_steps / user_stop / error
     */
    protected boolean shouldTerminate(String response, int currentStep, int maxSteps) {
        if (response == null || response.isBlank()) return false;

        String actionStr = parseActionString(response);

        // finish → 终止
        if (actionStr != null && actionStr.startsWith("finish")) {
            return true;
        }

        // 达到最大步数 → 终止
        if (currentStep >= maxSteps) {
            return true;
        }

        // 检测错误关键词
        String lower = response.toLowerCase();
        if (lower.contains("error:") || lower.contains("failed:")) {
            return true;
        }

        return false;
    }

}
