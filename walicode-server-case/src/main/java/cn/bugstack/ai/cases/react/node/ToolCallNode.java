package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.agent.service.security.PermissionGuard;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.PermissionConfirmManager;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 工具执行节点
 *
 * <p>职责：
 * 1. 从上下文中获取 AI 返回的工具调用列表（由 AiCallNode 设置）
 * 2. 检查工具是否已被 ADK 自动执行（FunctionResponse 已存在）
 * 3. 如果未执行，则手动执行工具
 * 4. 将工具结果追加到消息历史
 * 5. 发送 tool_call / tool_result SSE 事件
 * 6. 路由：回到 AiCallNode 继续对话 或 到 LoopDecisionNode 完成
 *
 * <p>工具执行模式：
 * <ul>
 *   <li>ADK 自动执行模式：ADK runner.runAsync() 内部自动执行工具，
 *       ToolCallNode 仅处理已有结果并路由</li>
 *   <li>手动执行模式（未来扩展）：ToolCallNode 直接调用
 *       SshExecuteAdkTool.executeCommand() 等工具方法执行</li>
 * </ul>
 *
 * <p>ReAct 循环链路：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用模型，解析 FunctionCalls）
 *         └→ ToolCallNode（处理工具结果）
 *               └→ [有工具结果] 回到 AiCallNode 继续对话
 *               └→ [无工具调用] LoopDecisionNode
 *                     └→ UserFeedbackNode
 * </pre>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;
    
    @Resource
    private IPromptService promptService;
    
    @Resource
    private IChatContextService chatContextService;
    
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private PermissionGuard permissionGuard;

    @Resource
    private PermissionConfirmManager permissionConfirmManager;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = dynamicContext.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - 无工具调用，跳过");
            return router(requestParameter, dynamicContext);
        }

        log.info("ReAct ToolCallNode - 处理 {} 个工具调用，已有 {} 个结果",
                toolCalls.size(), toolResults != null ? toolResults.size() : 0);

        ResponseBodyEmitter emitter = dynamicContext.getEmitter();

        // 检查是否已有 ADK 自动执行的结果（FunctionResponse 已返回）
        boolean adkAutoExecuted = toolResults != null && !toolResults.isEmpty();

        if (adkAutoExecuted) {
            // ─── ADK 自动执行模式 ───
            // 工具已被 ADK runner.runAsync() 内部执行，结果已在 currentToolResults 中
            log.info("工具已被 ADK 自动执行，处理已有结果");
            handleAdkToolResults(dynamicContext, toolCalls, toolResults);
        } else {
            // ─── 手动执行模式 ───
            // 工具未被自动执行，需要手动执行（未来扩展场景）
            log.info("工具未执行，手动执行");
            handleManualToolExecution(dynamicContext, toolCalls, emitter);
        }

        // 路由
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();

        // 有工具调用（已执行完成）→ 回到 AiCallNode 继续对话
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // 检查是否达到最大步数
            if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
                log.info("达到最大步数 {}，路由到 LoopDecisionNode", dynamicContext.getMaxSteps());
                dynamicContext.setStopReason("max_steps");
                return getBean("reactLoopDecisionNode");
            }

            log.info("工具调用处理完成，回到 AiCallNode 继续对话");
            return getBean("reactAiCallNode");
        }

        // 无工具调用 → 路由到 LoopDecisionNode
        log.info("无工具调用，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADK 自动执行模式（当前主力）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 处理 ADK 自动执行的工具结果
     * <p>ADK runner.runAsync() 内部已执行工具，FunctionResponse 已在事件流中返回。
     * 此处增加事后安全审计：检测已执行的危险命令并记录警告。
     */
    private void handleAdkToolResults(DefaultReActFactory.DynamicContext dynamicContext,
                                       List<Map<String, Object>> toolCalls,
                                       List<Map<String, Object>> toolResults) {

        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> result : toolResults) {
            String id = (String) result.get("id");
            if (id != null) {
                resultMap.put(id, result);
            }
        }

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            Map<String, Object> matchedResult = resultMap.get(toolCallId);
            if (matchedResult != null) {
                String content = (String) matchedResult.get("content");
                log.info("ADK 工具结果: id={}, name={}, result_length={}",
                        toolCallId, toolName, content != null ? content.length() : 0);

                // ── 事后安全审计 ──
                // ADK 已自动执行工具，无法阻止，但可以检测并记录危险操作
                PermissionGuard.PermissionDecision postAudit = permissionGuard.check(
                        dynamicContext.getSessionId(), toolName, argsStr);
                if (postAudit.isDenied()) {
                    log.error("[安全审计] ADK 自动执行了被拒绝的工具! tool={}, rule={}, reason={}, args={}",
                            toolName, postAudit.getRuleName(), postAudit.getReason(),
                            argsStr != null && argsStr.length() > 200 ? argsStr.substring(0, 200) + "..." : argsStr);
                    // 向动态上下文注入安全警告，让下一轮 AI 看到并停止
                    dynamicContext.appendToolMessage(toolCallId,
                            "⚠️ [安全审计警告] 工具 " + toolName + " 执行了被安全策略拒绝的操作: "
                                    + postAudit.getReason() + "。请立即停止后续危险操作。");
                }
            } else {
                log.warn("未找到工具结果: id={}, name={}", toolCallId, toolName);
            }
        }

        // 清除本轮工具调用标记，避免重复路由
        dynamicContext.getCurrentToolCalls().clear();
    }

    // ═══════════════════════════════════════════════════════════════
    //  手动执行模式（未来扩展）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 手动执行工具调用
     * <p>当 ADK 未自动执行工具时，由 ToolCallNode 直接执行
     * <p>适用于：自定义工具、MCP 工具、需要预处理/后处理的场景
     */
    private void handleManualToolExecution(DefaultReActFactory.DynamicContext dynamicContext,
                                            List<Map<String, Object>> toolCalls,
                                            ResponseBodyEmitter emitter) throws Exception {

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            if (toolCallId == null || toolName == null) {
                log.warn("工具调用信息不完整: {}", toolCall);
                continue;
            }

            // [Phase 2] 权限检查
            PermissionGuard.PermissionDecision decision = permissionGuard.check(
                    dynamicContext.getSessionId(), toolName, argsStr);

            if (decision.isDenied()) {
                // 拒绝执行
                log.warn("工具被权限拦截: tool={}, rule={}, reason={}",
                        toolName, decision.getRuleName(), decision.getReason());
                String denyMsg = "⛔ " + decision.getReason();

                Map<String, Object> toolResult = new HashMap<>();
                toolResult.put("id", toolCallId);
                toolResult.put("name", toolName);
                toolResult.put("content", denyMsg);
                toolResult.put("status", "denied");
                dynamicContext.getCurrentToolResults().add(toolResult);

                dynamicContext.appendToolMessage(toolCallId, denyMsg);
                sendToolResultEvent(emitter, toolCallId, denyMsg, "denied", dynamicContext);
                continue;
            }

            if (decision.needsConfirmation()) {
                // 需要用户确认 — 阻塞式等待
                log.info("工具需要确认: tool={}, rule={}, command={}",
                        toolName, decision.getRuleName(), decision.getCommand());

                // 发送 permission_confirm SSE 事件
                String confirmId = toolCallId + "_confirm_" + System.currentTimeMillis();
                sendPermissionConfirmEvent(
                        emitter,
                        confirmId,
                        toolName,
                        decision.getCommand() != null ? decision.getCommand() : argsStr,
                        decision.getAction().name(),
                        decision.getReason(),
                        30_000L, // 30 秒超时
                        dynamicContext
                );

                // 阻塞等待用户确认结果
                PermissionConfirmManager.PermissionResolveResult resolveResult =
                        permissionConfirmManager.awaitConfirmation(confirmId, 30_000L);

                if (resolveResult == null || !resolveResult.isApproved()) {
                    // 用户拒绝或超时
                    String denyMsg = resolveResult == null
                            ? "⛔ 确认超时，操作已取消"
                            : "⛔ 用户拒绝了操作: " + decision.getReason();
                    log.info("权限确认未通过: tool={}, timeout={}", toolName, resolveResult == null);

                    Map<String, Object> toolResult = new HashMap<>();
                    toolResult.put("id", toolCallId);
                    toolResult.put("name", toolName);
                    toolResult.put("content", denyMsg);
                    toolResult.put("status", "denied");
                    dynamicContext.getCurrentToolResults().add(toolResult);

                    dynamicContext.appendToolMessage(toolCallId, denyMsg);
                    sendToolResultEvent(emitter, toolCallId, denyMsg, "denied", dynamicContext);
                    continue;
                }

                // 用户确认 — 如果有修改的参数则使用修改后的
                if (resolveResult.getModifiedArgs() != null && !resolveResult.getModifiedArgs().isEmpty()) {
                    argsStr = resolveResult.getModifiedArgs();
                    log.info("用户修改了参数: tool={}, newArgs={}", toolName, argsStr);
                }
                permissionGuard.recordConfirmation(dynamicContext.getSessionId(), true);
            }

            // 发送 tool_call executing 事件
            sendToolCallEvent(emitter, toolCallId, toolName, "executing", dynamicContext);

            // 执行工具
            String resultContent;
            String status = "success";
            try {
                resultContent = executeTool(toolName, argsStr, toolCallId, emitter, dynamicContext);
                log.info("工具执行成功: name={}, result_length={}", toolName, resultContent.length());
            } catch (Exception e) {
                log.error("工具执行失败: name={}", toolName, e);
                resultContent = "Error executing tool '" + toolName + "': " + e.getMessage();
                status = "error";
            }

            // 截断过长结果
            resultContent = truncateToolResponse(resultContent, 4000);

            // 存储工具结果到上下文
            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("id", toolCallId);
            toolResult.put("name", toolName);
            toolResult.put("content", resultContent);
            toolResult.put("status", status);
            dynamicContext.getCurrentToolResults().add(toolResult);

            // 追加 tool 消息到消息历史（供下一轮 AI 调用使用）
            dynamicContext.appendToolMessage(toolCallId, resultContent);

            // 记录里程碑和工具执行摘要
            promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "tool", resultContent);
            chatContextService.pushToolResult(dynamicContext.getSessionId(), toolName, resultContent);

            // [Phase 5] 保存工具结果消息到数据库
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .priority("MEDIUM")
                    .tokenCount(resultContent.length() / 2)
                    .build());

            // 发送 tool_result SSE 事件
            sendToolResultEvent(emitter, toolCallId, resultContent, status, dynamicContext);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  工具执行
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据工具名称和参数执行对应的工具
     */
    private String executeTool(String toolName, String argsStr,
                                String toolCallId,
                                ResponseBodyEmitter emitter,
                                DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("手动执行工具: name={}, args={}", toolName, argsStr);

        switch (toolName) {
            case "executeCommand":
            case "execute_command":
            case "run_command":
                return executeSshTool(argsStr, toolCallId, emitter, dynamicContext);
            default:
                log.warn("未知工具: {}", toolName);
                return "Unknown tool: " + toolName + ". Available tools: executeCommand";
        }
    }

    /**
     * 执行 SSH 工具（流式输出版本）
     * <p>调用 SshExecuteAdkTool.executeCommandStreaming() 执行 SSH 命令，
     * 每个输出片段通过 sendToolOutputEvent 实时推送到前端。
     */
    private String executeSshTool(String argsStr,
                                   String toolCallId,
                                   ResponseBodyEmitter emitter,
                                   DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 1. 解析参数
        String command = parseToolArg(argsStr, "command");
        if (command == null || command.isBlank()) {
            return "Error: missing 'command' argument";
        }

        // 2. 解析超时参数（可选）
        Long timeoutMs = null;
        String timeoutStr = parseToolArg(argsStr, "timeoutMs");
        if (timeoutStr != null && !timeoutStr.isBlank()) {
            try {
                timeoutMs = Long.parseLong(timeoutStr);
            } catch (NumberFormatException ignored) {}
        }

        // 3. 执行 SSH 命令（流式），每个 chunk 实时推送 tool_output 事件
        Map<String, Object> result = sshExecuteAdkTool.executeCommandStreaming(
                command,
                timeoutMs,
                chunk -> sendToolOutputEvent(emitter, toolCallId, chunk, dynamicContext)
        );

        // 4. 格式化结果
        return formatSshResult(result);
    }

    /**
     * 格式化 SSH 执行结果
     */
    private String formatSshResult(Map<String, Object> result) {
        if (result == null) {
            return "No result";
        }

        StringBuilder sb = new StringBuilder();

        Object output = result.get("output");
        if (output != null && !output.toString().isEmpty()) {
            sb.append(output);
        }

        Object error = result.get("error");
        if (error != null && !error.toString().isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[ERROR] ").append(error);
        }

        Object exitCode = result.get("exitCode");
        if (exitCode != null) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[Exit code: ").append(exitCode).append("]");
        }

        return !sb.isEmpty() ? sb.toString() : "Command executed with no output";
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从 JSON 参数字符串中解析指定 key 的值
     */
    private String parseToolArg(String argsStr, String key) {
        if (argsStr == null || argsStr.isBlank()) return null;

        try {
            Map<String, Object> args = objectMapper.readValue(argsStr,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Object value = args.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("解析工具参数失败: {}", e.getMessage());
            // 兜底：简单字符串匹配
            String pattern = "\"" + key + "\"";
            int idx = argsStr.indexOf(pattern);
            if (idx >= 0) {
                int colonIdx = argsStr.indexOf(":", idx + pattern.length());
                if (colonIdx >= 0) {
                    String remaining = argsStr.substring(colonIdx + 1).trim();
                    if (remaining.startsWith("\"")) {
                        int endQuote = remaining.indexOf("\"", 1);
                        if (endQuote > 0) {
                            return remaining.substring(1, endQuote);
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * 截断过长的工具响应
     */
    private String truncateToolResponse(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "\n... (truncated, total " + content.length() + " chars)";
    }

    /**
     * 发送权限确认请求事件（使用统一的 ReActEventDTO）
     */
    private void sendConfirmationEvent(ResponseBodyEmitter emitter,
                                        String toolCallId,
                                        String toolName,
                                        PermissionGuard.PermissionDecision decision,
                                        DefaultReActFactory.DynamicContext dynamicContext) {
        String confirmId = toolCallId + "_confirm_" + System.currentTimeMillis();
        sendPermissionConfirmEvent(
                emitter,
                confirmId,
                toolName,
                decision.getCommand() != null ? decision.getCommand() : "",
                decision.getAction().name(),
                decision.getReason(),
                30_000L, // 30 秒超时
                dynamicContext
        );
    }

}
