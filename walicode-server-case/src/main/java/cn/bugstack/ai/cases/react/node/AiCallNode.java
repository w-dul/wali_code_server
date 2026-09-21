package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.IPromptService;
import cn.bugstack.ai.domain.agent.service.IChatContextService;
import cn.bugstack.ai.domain.agent.service.IIntentService;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentResultVO;
import cn.bugstack.ai.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import cn.bugstack.ai.domain.agent.service.intent.enhancer.IntentOrchestrator;
import cn.bugstack.ai.domain.agent.adapter.repository.IChatHistoryRepository;
import cn.bugstack.ai.domain.agent.model.entity.ChatMessageEntity;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.SshExecuteMcpService;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SubAgentAdkTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.runner.Runner;
import com.google.genai.types.Content;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI 调用节点（ReAct 循环核心）
 *
 * <p>职责：
 * 1. 调用 ADK runner.runAsync() 获取事件流
 * 2. 处理文本内容，发送 SSE 事件
 * 3. 从 event.actions().stateDelta() 检测工具执行结果
 * 4. 如果有工具调用：存储到上下文，发送 SSE 事件，路由到 ToolCallNode
 * 5. 如果无工具调用：路由到 LoopDecisionNode
 *
 * <p>数据流说明（2026-06-25 修复）：
 * <p>已关闭 Spring AI 自动工具执行（internalToolExecutionEnabled=false），
 * ADK Runner 自动执行工具，FunctionResponse 事件出现在事件流中。
 * AiCallNode 从 FunctionResponse 中提取完整工具结果并通过 SSE 推送到前端。
 * 工具类中的 onToolResult side channel 已禁用，避免重复推送。
 *
 * <p>stateDelta[outputKey] 存的是 LlmAgent 的 AI 文本总结，非工具原始返回值，
 * 仅用于日志和检测 Agent 是否有最终输出。
 *
 * <p>ReAct 循环流程：
 * <pre>
 * RootNode
 *   └→ AiCallNode（调用 ADK runner，解析事件）
 *         ├→ [stateDelta 有结果] ToolCallNode → AiCallNode（循环）
 *         └→ [无工具调用] LoopDecisionNode → UserFeedbackNode
 * </pre>
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private SubAgentAdkTool subAgentAdkTool;

    @Resource
    private IPromptService promptService;
    
    @Resource
    private IChatContextService chatContextService;
    
    @Resource
    private IIntentService intentService;
    
    @Resource
    private IntentOrchestrator intentOrchestrator;
    
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    /** SSE 事件发送间隔（字符数） */
    private static final int SSE_BATCH_SIZE = 20;

    /** tool name 映射：stateDelta key -> 默认 tool name（当无法从结果中推断时使用） */
    private static final Map<String, String> STATE_DELTA_TOOL_MAPPING = Map.ofEntries(
            Map.entry("ssh_result", "executeCommand"),
            Map.entry("code_result", "executeLocalCommand")
    );

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - 开始 AI 调用，第 {} 步", dynamicContext.getStep() + 1);

        // 发送 round_start SSE 事件
        sendRoundStartEvent(dynamicContext.getEmitter(), dynamicContext.getStep(), dynamicContext);

        String agentId = dynamicContext.getAgentId();

        // 1. 获取 Agent 注册信息和 ADK Runner
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (aiAgentRegisterVO == null) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        Runner runner = aiAgentRegisterVO.getRunner();

        // 1.1 确保 session 存在（autoCreateSession=true 让 ADK 自动处理）
        log.info("Runner 使用 autoCreateSession=true，sessionId={}", dynamicContext.getSessionId());

        // 2. 获取最新用户消息
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // [Phase 3] 使用 RootNode 已识别的意图；兜底时再分类一次
        IntentResultVO intentResult = resolveIntentResult(dynamicContext, lastUserMessage);
        log.info("识别到用户意图: {}, 置信度: {}", intentResult.getIntent().getLabel(), intentResult.getConfidence());

        // 3. 重置当前轮次缓冲
        dynamicContext.resetRoundBuffers();

        // [Phase 2] 裁剪消息历史（token 预算从 agent 配置动态读取）
        int tokenBudget = dynamicContext.getContextTokenBudget() > 0 ? dynamicContext.getContextTokenBudget() : 8000;
        List<Map<String, Object>> trimmedHistory = chatContextService.trimHistory(dynamicContext.getMessageHistory(), tokenBudget);
        dynamicContext.setMessageHistory(new ArrayList<>(trimmedHistory));

        // 4. 绑定终端会话 ID（ThreadLocal + ConcurrentHashMap 双通道）
        // ThreadLocal 在 ADK Runner RxJava 异步调度器线程上可能丢失，
        // ConcurrentHashMap sessionMapping 提供跨线程可靠查找
        String terminalSessionId = dynamicContext.getTerminalSessionId();
        String chatSessionId = dynamicContext.getSessionId();
        if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
            SshExecuteAdkTool.setCurrentTerminalSession(terminalSessionId);
            SshExecuteMcpService.setCurrentTerminalSession(terminalSessionId);
            // 注册到 ConcurrentHashMap，供 RxJava 异步线程通过 chatSessionId 精确查找
            SshExecuteAdkTool.setTerminalSession(chatSessionId, terminalSessionId);
        } else {
            // 尝试从 sessionMapping 恢复（前一轮已绑定的场景）
            String boundTerminal = SshExecuteAdkTool.getTerminalSession(chatSessionId);
            if (boundTerminal != null) {
                SshExecuteAdkTool.setCurrentTerminalSession(boundTerminal);
                SshExecuteMcpService.setCurrentTerminalSession(boundTerminal);
            }
        }

        // 设置当前活跃 chatSessionId（供 RxJava 异步线程 fallback 查找 terminalSessionId）
        SshExecuteAdkTool.setActiveChatSessionId(chatSessionId);

        // 5. 构建动态上下文并注入用户消息
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("注入动态上下文后消息长度: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        // [Phase 5] 保存用户消息到数据库（只在首轮保存原始消息）
        if (dynamicContext.getStep() == 0) {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("user")
                    .content(lastUserMessage)
                    .priority("MEDIUM")
                    .tokenCount(lastUserMessage.length() / 2)
                    .build());
        }

        // 6. 构建用户消息（支持多模态：文本 + 图片）
        List<com.google.genai.types.Part> userParts = new java.util.ArrayList<>();
        userParts.add(com.google.genai.types.Part.builder().text(enrichedMessage).build());

        // 6.1 如果请求中包含内联图片数据，追加为多模态 Part
        List<ChatRequestDTO.InlineData> inlineDatas = requestParameter.getInlineDatas();
        if (inlineDatas != null && !inlineDatas.isEmpty()) {
            for (ChatRequestDTO.InlineData inlineData : inlineDatas) {
                try {
                    byte[] imageBytes = java.util.Base64.getDecoder().decode(inlineData.getData());
                    userParts.add(com.google.genai.types.Part.fromBytes(imageBytes, inlineData.getMimeType()));
                    log.info("追加内联图片 Part: mimeType={}, size={} bytes", inlineData.getMimeType(), imageBytes.length);
                } catch (Exception e) {
                    log.warn("解析内联图片数据失败: mimeType={}, error={}", inlineData.getMimeType(), e.getMessage());
                }
            }
        }

        com.google.genai.types.Content userContent = Content.builder()
                .role("user")
                .parts(userParts)
                .build();

        // [DEBUG] 打印 userContent 中每个 Part 的类型和摘要
        log.info("userContent parts 数量: {}", userParts.size());
        for (int i = 0; i < userParts.size(); i++) {
            com.google.genai.types.Part p = userParts.get(i);
            if (p.text().isPresent()) {
                log.info("Part[{}]: type=text, len={}", i, p.text().get().length());
            } else if (p.inlineData().isPresent()) {
                com.google.genai.types.Blob blob = p.inlineData().get();
                log.info("Part[{}]: type=inlineData, mimeType={}, dataPresent={}, dataLen={}",
                        i, blob.mimeType().orElse("?"), blob.data().isPresent(),
                        blob.data().map(d -> d.length).orElse(-1));
            } else {
                log.info("Part[{}]: type=other, textPresent={}, inlineDataPresent={}",
                        i, p.text().isPresent(), p.inlineData().isPresent());
            }
        }

        // 7. 重置 ReAct 循环标志
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 8. 调用 ADK Runner 并处理事件流
        ResponseBodyEmitter emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        int roundToolCalls = 0;
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("调用 ADK Runner，用户消息: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        try {
            // 绑定 SSE 进度通知器（工具执行时推送实时进度）
            cn.bugstack.ai.cases.react.notifier.SseToolProgressNotifier.bind(
                    dynamicContext.getSessionId(), emitter);
            // 绑定指令分发器（本地指令通过 SSE 下发到 Client）
            cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandDispatcher.bindEmitter(
                    dynamicContext.getSessionId(), emitter);

            // [Phase 4] 设置子代理上下文（供 SubAgentAdkTool 使用）
            SubAgentAdkTool.setCurrentContext(agentId, dynamicContext.getUserId(), dynamicContext.getSessionId());

            int maxAttempts = Math.max(1, dynamicContext.getMaxAiRetries() + 1);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean hasObservableOutput = false;
                int eventCount = 0;
                long lastHeartbeatTime = System.currentTimeMillis();
                final long HEARTBEAT_INTERVAL_MS = 30_000L;

                try {
                    Iterator<Event> events = runner.runAsync(
                            dynamicContext.getUserId(),
                            dynamicContext.getSessionId(),
                            userContent,
                            RunConfig.builder()
                                    .streamingMode(RunConfig.StreamingMode.SSE)
                                    .autoCreateSession(true)
                                    .build()
                    ).blockingIterable().iterator();

                    while (events.hasNext()) {
                        if (dynamicContext.isCancelled()) {
                            log.info("检测到任务已取消 (reason={})，退出事件循环", dynamicContext.getCancelReason());
                            dynamicContext.setStopReason("user_stop");
                            break;
                        }

                        Event event = events.next();
                        eventCount++;

                        if (dynamicContext.isCancelled()) {
                            log.info("检测到任务已取消 (reason={})，退出事件循环", dynamicContext.getCancelReason());
                            dynamicContext.setStopReason("user_stop");
                            break;
                        }

                        try {
                            EventActions dbgActions = event.actions();
                            boolean hasStateDelta = dbgActions != null && dbgActions.stateDelta() != null && !dbgActions.stateDelta().isEmpty();
                            var functionCallsList = event.functionCalls();
                            boolean hasFunctionCalls = functionCallsList != null && !functionCallsList.isEmpty();
                            String dbgText = event.stringifyContent();
                            log.info("[AiCallNode] 尝试#{}, 事件#{}: final={}, text_len={}, hasStateDelta={}, hasFunctionCalls={}, functionCalls={}",
                                    attempt,
                                    eventCount,
                                    event.finalResponse(),
                                    dbgText != null ? dbgText.length() : 0,
                                    hasStateDelta,
                                    hasFunctionCalls,
                                    hasFunctionCalls ? functionCallsList.size() : 0);
                        } catch (Exception dbgEx) {
                            log.warn("[AiCallNode] 打印调试日志失败", dbgEx);
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
                            // 通过 CommandDispatcher 统一发送心跳
                            boolean heartbeatOk = cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandDispatcher.sendHeartbeat();
                            if (!heartbeatOk) {
                                log.warn("SSE 心跳发送失败（可能客户端已断开）");
                                dynamicContext.markCancelled("heartbeat_send_failed");
                            }
                            lastHeartbeatTime = now;
                        }

                        String eventText = event.stringifyContent();
                        log.debug("处理第 {} 个事件: final={}, content_len={}",
                                eventCount,
                                event.finalResponse(),
                                eventText.length());

                        if (!eventText.isBlank()) {
                            hasObservableOutput = true;
                            appendChunk(textAccumulator, eventText);
                            dynamicContext.setAssistantContent(textAccumulator);
                            if (!sendTextEvent(emitter, eventText, textAccumulator.toString(), dynamicContext)) {
                                log.info("文本事件发送失败，退出事件循环");
                                dynamicContext.setStopReason("user_stop");
                                break;
                            }
                            lastHeartbeatTime = System.currentTimeMillis();
                        }

                        // [FIX-20260622] 从 FunctionResponse 事件提取工具原始返回值
                        List<com.google.genai.types.FunctionResponse> funcResponses = event.functionResponses();
                        if (!funcResponses.isEmpty()) {
                            hasObservableOutput = true;

                            for (com.google.genai.types.FunctionResponse fr : funcResponses) {
                                String toolName = fr.name().orElse("unknown_tool");
                                java.util.Optional<java.util.Map<String, Object>> responseOpt = fr.response();

                                if (responseOpt.isEmpty()) {
                                    log.debug("FunctionResponse 无 response 数据: tool={}", toolName);
                                    continue;
                                }

                                java.util.Map<String, Object> rawResult = responseOpt.get();
                                String toolCallId = fr.id().orElse("call_" + toolName + "_" + System.currentTimeMillis() + "_" + roundToolCalls);

                                // per-round 截断检查
                                if (roundToolCalls >= dynamicContext.getMaxToolCallsPerRound()) {
                                    dynamicContext.setOverflowToolCount(funcResponses.size() - roundToolCalls);
                                    log.warn("[PER-ROUND-OVERFLOW] 本轮工具调用达到上限: {}/{}, 剩余工具将在下一轮继续",
                                            roundToolCalls, dynamicContext.getMaxToolCallsPerRound());
                                    break;
                                }

                                // 从原始 Map 提取工具名和参数
                                String[] toolInfo = extractToolInfo(rawResult, toolName);
                                String effectiveToolName = toolInfo[0];
                                String toolArgs = toolInfo[1];
                                String resultContent = formatStateValue(rawResult);
                                String toolStatus = inferToolStatus(resultContent);

                                log.info("[FunctionResponse] 工具执行结果: tool={}, effectiveName={}, args_len={}, result_len={}, status={}",
                                        toolName, effectiveToolName, toolArgs != null ? toolArgs.length() : 0, resultContent.length(), toolStatus);

                                Map<String, Object> toolCallInfo = new HashMap<>();
                                toolCallInfo.put("id", toolCallId);
                                toolCallInfo.put("name", effectiveToolName);
                                toolCallInfo.put("args", toolArgs != null ? toolArgs : "");
                                dynamicContext.getCurrentToolCalls().add(toolCallInfo);

                                Map<String, Object> toolResultInfo = new HashMap<>();
                                toolResultInfo.put("id", toolCallId);
                                toolResultInfo.put("name", effectiveToolName);
                                toolResultInfo.put("content", resultContent);
                                toolResultInfo.put("status", toolStatus);
                                dynamicContext.getCurrentToolResults().add(toolResultInfo);

                                if (!sendToolCallEventWithArgs(emitter, toolCallId, effectiveToolName, toolArgs != null ? toolArgs : "", "executing", dynamicContext)) {
                                    log.info("工具调用事件发送失败，退出事件循环");
                                    dynamicContext.setStopReason("user_stop");
                                    break;
                                }

                                if (!sendToolResultEvent(emitter, toolCallId, resultContent, toolStatus, dynamicContext)) {
                                    log.info("工具结果事件发送失败，退出事件循环");
                                    dynamicContext.setStopReason("user_stop");
                                    break;
                                }

                                roundToolCalls++;
                                dynamicContext.incrementTotalToolCalls();

                                if ("executeCommand".equals(effectiveToolName) && !resultContent.isEmpty()) {
                                    recordExecutedCommand(dynamicContext, resultContent);
                                }

                                if ("error".equals(toolStatus)) {
                                    dynamicContext.appendAssistantMessage("工具 " + effectiveToolName + " 执行失败，错误信息如下：\n" + truncate(resultContent, 1200));
                                }

                                promptService.detectAndRecordMilestone(
                                        dynamicContext.getSessionId(), "tool", resultContent);
                                chatContextService.pushToolResult(dynamicContext.getSessionId(), effectiveToolName, resultContent);
                            }
                        }

                        // stateDelta 仅用于日志记录（存的是 AI 文本总结，非工具原始返回值）
                        EventActions actions = event.actions();
                        if (actions != null) {
                            java.util.Map<String, Object> stateDelta = actions.stateDelta();
                            if (stateDelta != null && !stateDelta.isEmpty()) {
                                log.debug("stateDelta 变更（仅日志）: keys={}", stateDelta.keySet());
                            }
                        }

                        if (event.content().isPresent()) {
                            Content content = event.content().get();
                            String role = content.role().orElse("assistant");
                            if ("assistant".equals(role) && !eventText.isBlank()) {
                                dynamicContext.appendAssistantMessage(eventText);
                            }
                        }

                        if (dynamicContext.getStopReason() != null) {
                            break;
                        }
                    }

                    log.info("ADK Runner 事件流处理完成，共 {} 个事件，attempt={}", eventCount, attempt);
                    dynamicContext.resetAiRetryCount();
                    break;
                } catch (Exception e) {
                    if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                        log.info("ADK Runner 事件流已取消，忽略中断异常: reason={}", dynamicContext.getCancelReason());
                        Thread.interrupted();
                        if (dynamicContext.getStopReason() == null) {
                            dynamicContext.setStopReason("user_stop");
                        }
                        break;
                    }

                    boolean canRetry = attempt < maxAttempts
                            && !hasObservableOutput
                            && isRetryableException(e);

                    if (canRetry) {
                        int retryCount = dynamicContext.incrementAiRetryCount();
                        long delayMs = calculateRetryDelayMs(retryCount);
                        log.warn("ADK Runner 调用失败，准备第 {} 次重试，{} ms 后继续: {}",
                                retryCount, delayMs, e.getMessage());
                        safeSleep(delayMs, dynamicContext);
                        continue;
                    }

                    log.error("ADK Runner 调用失败", e);
                    hasError = true;
                    errorBuilder.append("ADK Runner error: ").append(e.getMessage());
                    dynamicContext.setErrorMessage(errorBuilder.toString());
                    dynamicContext.setStopReason("error");
                    break;
                }
            }
        } finally {
            // 解绑 SSE 进度通知器
            cn.bugstack.ai.cases.react.notifier.SseToolProgressNotifier.unbind(dynamicContext.getSessionId());
            // 解绑指令分发器
            cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandDispatcher.unbindEmitter(dynamicContext.getSessionId());

            // [Phase 4] 清除子代理上下文
            SubAgentAdkTool.clearCurrentContext();

            // 清除终端会话绑定
            if (terminalSessionId != null && !terminalSessionId.isEmpty()) {
                SshExecuteAdkTool.clearCurrentTerminalSession();
                SshExecuteMcpService.clearCurrentTerminalSession();
            }
        }

        // 9. 更新步数和工具调用统计
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(
                dynamicContext.getResult().getTotalToolCalls() + roundToolCalls
        );

        log.info("ReAct AiCallNode - 第 {} 步完成，本轮工具调用 {} 次，文本长度 {}{}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length(),
                dynamicContext.getOverflowToolCount() > 0
                    ? "，⚠️ 有 " + dynamicContext.getOverflowToolCount() + " 个工具溢出到下一轮"
                    : "");

        // 10. 连接仍然有效时才发送 round_end，避免断连后继续写 SSE
        if (!dynamicContext.isCancelled()) {
            sendRoundEndEvent(
                    dynamicContext.getEmitter(),
                    dynamicContext.getStep(),
                    dynamicContext.getMaxSteps(),
                    !hasError,
                    dynamicContext.getResult().getTotalToolCalls(),
                    dynamicContext
            );
        }

        // [Phase 5] 保存助手回复到数据库（如果是最终回复，或者包含实质性内容）
        if (textAccumulator.length() > 0) {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("assistant")
                    .content(textAccumulator.toString())
                    .priority("MEDIUM")
                    .tokenCount(textAccumulator.length() / 2)
                    .build());
        }

        // 11. 错误处理
        if (hasError) {
            dynamicContext.setStopReason("error");
        }

        // 12. 路由
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        // 检查是否应该终止
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("检测到终止条件: {}, 路由到 UserFeedbackNode", stopReason);
            return getBean("reactUserFeedbackNode");
        }

        // 检查是否达到最大步数
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("达到最大步数 {}, 路由到 UserFeedbackNode", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            return getBean("reactUserFeedbackNode");
        }

        // 检查本轮是否有工具调用（从 stateDelta 检测到的）
        if (!dynamicContext.getCurrentToolCalls().isEmpty()) {
            log.info("检测到 {} 个工具调用，路由到 ToolCallNode",
                    dynamicContext.getCurrentToolCalls().size());
            return getBean("reactToolCallNode");
        }

        // 无工具调用 → ReAct 循环完成
        log.info("无工具调用，ReAct 循环完成，路由到 LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取最新用户消息
     */
    private String getLastUserMessage(ChatRequestDTO requestParameter,
                                       DefaultReActFactory.DynamicContext dynamicContext) {
        if (requestParameter.getMessage() != null && !requestParameter.getMessage().isEmpty()) {
            return requestParameter.getMessage();
        }

        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return "";
    }

    /**
     * 判断事件文本是否是工具执行结果的重复
     * <p>SpringAI 自动执行工具后，模型会在文本中描述工具结果，
     * 但我们已经在 stateDelta 中获取了原始结果，避免重复展示
     */
    private boolean isToolResultText(String text, Event event) {
        // 检查是否是 FunctionResponse 事件（工具响应）
        if (event.content().isPresent()) {
            Content content = event.content().get();
            // 检查是否包含 FunctionResponse parts
            if (content.parts().isPresent()) {
                for (com.google.genai.types.Part part : content.parts().get()) {
                    if (part.functionResponse().isPresent()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 从 stateDelta key 解析工具名称（默认值，当无法从结果中推断时使用）
     */
    private String resolveToolName(String stateKey) {
        // 已知映射
        String mapped = STATE_DELTA_TOOL_MAPPING.get(stateKey);
        if (mapped != null) {
            return mapped;
        }

        // 从 key 推断：去掉 _result 后缀
        if (stateKey.endsWith("_result")) {
            return stateKey.substring(0, stateKey.length() - 7);
        }

        return stateKey;
    }

    /**
     * 从工具返回值中提取真实工具名和命令参数
     * <p>ADK 自动执行模式下，所有工具的结果都存在同一个 outputKey（如 code_result）下。
     * 但工具返回的 Map 包含特征字段，可以反向推断工具类型：
     * <ul>
     *   <li>executeLocalCommand / compileProject / compileTests / runUnitTests → 含 command/output/exitCode</li>
     *   <li>readFile / writeFile / listFiles → 含 path/content</li>
     *   <li>executeCommand (SSH) → 含 command/output/exitCode + ssh 特征</li>
     * </ul>
     *
     * @return [0] = toolName, [1] = toolArgs (command 或参数摘要)
     */
    private String[] extractToolInfo(Object stateValue, String stateKey) {
        String defaultToolName = resolveToolName(stateKey);
        String toolArgs = "";

        if (stateValue == null) {
            return new String[]{defaultToolName, toolArgs};
        }

        try {
            // 尝试将 stateValue 解析为 JSON 对象
            com.fasterxml.jackson.databind.JsonNode node;
            if (stateValue instanceof String) {
                node = objectMapper.readTree((String) stateValue);
            } else {
                node = objectMapper.valueToTree(stateValue);
            }

            // 从结果字段推断工具类型
            String command = node.has("command") ? node.get("command").asText("") : "";
            String path = node.has("path") ? node.get("path").asText("") : "";
            String filePath = node.has("filePath") ? node.get("filePath").asText("") : "";
            boolean hasExitCode = node.has("exitCode");
            boolean hasOutput = node.has("output");
            boolean hasErrorSummary = node.has("errorSummary");
            boolean hasTestFailures = node.has("testFailures");

            if (hasErrorSummary || hasTestFailures) {
                // BuildValidationAdkTool 系列
                if (command.contains("test") || hasTestFailures) {
                    toolArgs = command;
                    return new String[]{"runUnitTests", toolArgs};
                } else if (command.contains("test-compile") || command.contains("-pl") && command.contains("compile")) {
                    toolArgs = command;
                    return new String[]{"compileTests", toolArgs};
                } else {
                    toolArgs = command;
                    return new String[]{"compileProject", toolArgs};
                }
            }

            if (command != null && !command.isEmpty() && hasExitCode) {
                // 命令执行类工具（executeLocalCommand 或 executeCommand）
                toolArgs = command;
                // SSH 的 outputKey 是 ssh_result，本地的是 code_result
                if ("ssh_result".equals(stateKey)) {
                    return new String[]{"executeSshCommand", toolArgs};
                }
                return new String[]{"executeLocalCommand", toolArgs};
            }

            if (path != null && !path.isEmpty() || filePath != null && !filePath.isEmpty()) {
                // 文件操作类工具
                String resolvedPath = path != null && !path.isEmpty() ? path : filePath;
                toolArgs = resolvedPath;
                // 根据 stateKey 推断是本地还是远程
                if ("code_result".equals(stateKey)) {
                    // 无法确定具体操作类型，用默认名
                    return new String[]{"codeEditTool", toolArgs};
                }
            }

        } catch (Exception e) {
            log.debug("从 stateValue 提取工具信息失败: {}", e.getMessage());
        }

        return new String[]{defaultToolName, toolArgs};
    }

    /**
     * 格式化 stateDelta 值为字符串
     */
    private String formatStateValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: 动态上下文注入
    // ═══════════════════════════════════════════════════════════════

    /**
     * 从工具结果中提取命令并记录到最近命令列表
     */
    private void recordExecutedCommand(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        if (toolResult.length() > 1000) {
            dynamicContext.addRecentCommand(truncate(toolResult, 80) + "...");
        } else {
            dynamicContext.addRecentCommand(toolResult);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * 构建注入了动态上下文的用户消息
     * 委托 IPromptService 完成环境采集、里程碑获取、前缀构建
     * [Phase 3] 集成 IntentOrchestrator 进行指代消解和上下文增强
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        // 记录用户消息的里程碑
        promptService.detectAndRecordMilestone(dynamicContext.getSessionId(), "user", userMessage);

        // [Phase 3] 意图增强：信号提取 + 指代消解 + 上下文增强
        IntentResultVO intentResult = IntentResultVO.builder()
                .intent(IntentTypeEnumVO.valueOf(dynamicContext.getCurrentIntent() != null ? dynamicContext.getCurrentIntent() : "UNKNOWN"))
                .confidence(dynamicContext.getCurrentIntentConfidence())
                .build();
        
        // [P2-5] 传入 projectRootPath 支持意图增强的文件搜索 fallback
        cn.bugstack.ai.api.dto.ProjectContextDTO projectCtxForIntent = dynamicContext.getProjectContext();
        String projectRootPathForIntent = projectCtxForIntent != null ? projectCtxForIntent.getRootPath() : null;
        IntentOrchestrator.EnhancedIntentResult enhancedResult = intentOrchestrator.buildEnhancedResult(
                intentResult, userMessage, dynamicContext.getSessionId(), null, projectRootPathForIntent);
        
        // 使用指代消解后的输入
        String effectiveMessage = enhancedResult.getResolvedInput() != null 
                ? enhancedResult.getResolvedInput() : userMessage;
        
        // 委托领域服务构建富化消息
        cn.bugstack.ai.api.dto.ProjectContextDTO projectCtx = dynamicContext.getProjectContext();
        String projectName = projectCtx != null ? projectCtx.getName() : null;
        String projectRootPath = projectCtx != null ? projectCtx.getRootPath() : null;
        String enrichedMessage = promptService.buildEnrichedMessage(
                effectiveMessage,
                dynamicContext.getSessionId(),
                dynamicContext.getTerminalSessionId(),
                dynamicContext.getRecentCommands(),
                dynamicContext.getMessageHistory(),
                projectName,
                projectRootPath
        );
        
        // [Phase 3] 追加意图增强上下文
        if (enhancedResult.hasEnhancement() && enhancedResult.getEnhancedContext() != null) {
            enrichedMessage = enhancedResult.getEnhancedContext() + "\n" + enrichedMessage;
        }
        
        return enrichedMessage;
    }

    private IntentResultVO resolveIntentResult(DefaultReActFactory.DynamicContext dynamicContext, String lastUserMessage) {
        if (dynamicContext.getCurrentIntent() != null && !dynamicContext.getCurrentIntent().isBlank()) {
            try {
                return IntentResultVO.builder()
                        .intent(IntentTypeEnumVO.valueOf(dynamicContext.getCurrentIntent()))
                        .confidence(dynamicContext.getCurrentIntentConfidence())
                        .rawResponse("from_context")
                        .build();
            } catch (Exception ignore) {
                log.warn("上下文中的意图值无法识别，重新分类: {}", dynamicContext.getCurrentIntent());
            }
        }

        IntentResultVO intentResult = intentService.classify(dynamicContext.getSessionId(), dynamicContext.getUserId(), lastUserMessage);
        dynamicContext.setCurrentIntent(intentResult.getIntent().name());
        dynamicContext.setCurrentIntentConfidence(intentResult.getConfidence());
        return intentResult;
    }

    private String inferToolStatus(String resultContent) {
        if (resultContent == null || resultContent.isBlank()) {
            return "success";
        }

        String lower = resultContent.toLowerCase();
        if (lower.contains("\"success\":false")
                || lower.contains("执行失败")
                || lower.contains("error")
                || lower.contains("failed")
                || lower.contains("exception")
                || lower.contains("timeout")
                || lower.contains("timed out")) {
            return "error";
        }

        return "success";
    }

    private boolean isRetryableException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return true;
        }

        String lower = message.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("temporarily unavailable")
                || lower.contains("connection reset")
                || lower.contains("connection closed")
                || lower.contains("503")
                || lower.contains("504")
                || lower.contains("rate limit")
                || lower.contains("resource exhausted");
    }

    private long calculateRetryDelayMs(int retryCount) {
        return Math.min(8_000L, 1_000L * (1L << Math.max(0, retryCount - 1)));
    }

    private void safeSleep(long delayMs, DefaultReActFactory.DynamicContext dynamicContext) {
        if (delayMs <= 0 || dynamicContext.isCancelled()) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dynamicContext.markCancelled("retry_sleep_interrupted");
        }
    }

    /**
     * 智能拼接 LLM 流式文本块，处理 || 行分隔符和表格行粘连
     * 注意：仅处理流式拼接造成的格式问题，LLM 输出本身的格式错误
     * 由 finalMarkdownCleanup 在 done 前全量处理
     */
    private static void appendChunk(StringBuilder acc, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        // 0. 统一换行符：\r\n → \n，避免 Windows 换行残留
        chunk = chunk.replace("\r\n", "\n").replace("\r", "\n");

        // 1. || → 换行（保留前一行尾部 | 和后续空格）
        // 注意：避免破坏表格格式，只在非表格上下文处理
        String processed = chunk;
        // 处理表格行粘连：|内容|| → |内容|
        // 但保留表格分隔行 |---|---| 的完整性
        if (!chunk.trim().startsWith("|") || !chunk.contains("---")) {
            processed = chunk.replaceAll("(\\|)\\|+(\\s*)", "$1\n$2");
        }

        // 2. 块间换行：累积文本末尾 + 新块开头
        if (acc.length() > 0 && !processed.startsWith("\n")) {
            String accStr = acc.toString();
            String lastLine = accStr.contains("\n")
                    ? accStr.substring(accStr.lastIndexOf('\n') + 1)
                    : accStr;

            // 表格行粘连：| 结尾（支持尾部空格）+ | 开头
            String lastLineTrimmed = lastLine.trim();
            if ((lastLineTrimmed.endsWith("|") || lastLineTrimmed.matches(".*\\|\\s*")) && processed.startsWith("|")) {
                acc.append('\n');
            }
            // 列表项 / 标题 → 表格行
            else if (!lastLine.contains("|") && !lastLine.trim().isEmpty()
                    && !lastLine.trim().startsWith("`") && processed.matches("\\|.*\\|.*\\|.*")) {
                acc.append('\n');
            }
            // 块级元素（--- / ## 标题 / 列表）前需要空行
            else if (!lastLine.trim().isEmpty() && !lastLine.trim().startsWith("`")
                    && (processed.startsWith("---") || processed.startsWith("##")
                        || processed.matches("[-*+]\\s+.*") || processed.matches("\\d+\\.\\s+.*"))) {
                acc.append("\n\n");
            }
        }

        acc.append(processed);
    }

    /**
     * 全量 Markdown 清理（在 done 事件前调用）
     * 处理 LLM 输出本身的格式问题，能看到完整文本
     */
    static String finalMarkdownCleanup(String text) {
        if (text == null || text.isEmpty()) return text;

        // 保护代码块
        java.util.List<String> codeBlocks = new java.util.ArrayList<>();
        java.util.regex.Matcher cbMatcher = java.util.regex.Pattern.compile("```[\\s\\S]*?```").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (cbMatcher.find()) {
            codeBlocks.add(cbMatcher.group());
            cbMatcher.appendReplacement(sb, "\u0001CB" + (codeBlocks.size() - 1) + "\u0001");
        }
        cbMatcher.appendTail(sb);
        String result = sb.toString();

        // 1. 加粗标记空格清理：** text ** → **text**（状态机，成对处理）
        result = cleanBoldSpaces(result);

        // 2. 合并跨行加粗：**Java 17\n3.4.3** → **Java 17 3.4.3**
        result = mergeCrossLineBold(result);

        // 3. 连续 3+ 空行 → 2 空行
        result = result.replaceAll("\n{3,}", "\n\n");

        // 4. 修复表格格式问题
        result = fixTableFormat(result);

        // 还原代码块
        for (int i = 0; i < codeBlocks.size(); i++) {
            result = result.replace("\u0001CB" + i + "\u0001", codeBlocks.get(i));
        }

        return result;
    }

    /**
     * 合并跨行的加粗标记
     */
    private static String mergeCrossLineBold(String text) {
        if (text == null || !text.contains("**")) return text;
        String[] lines = text.split("\n", -1);
        java.util.List<String> merged = new java.util.ArrayList<>();
        for (String line : lines) {
            if (!merged.isEmpty()) {
                String prev = merged.get(merged.size() - 1);
                int count = 0;
                for (int j = 0; j < prev.length() - 1; j++) {
                    if (prev.charAt(j) == '*' && prev.charAt(j + 1) == '*') { count++; j++; }
                }
                if (count % 2 != 0) {
                    merged.set(merged.size() - 1, prev + " " + line);
                    continue;
                }
            }
            merged.add(line);
        }
        return String.join("\n", merged);
    }

    /**
     * 状态机清理加粗标记空格：** text ** → **text**
     * 成对处理 ** 标记，开标签后跳过空格，闭标签前移除空格。
     * 不会跨已闭合的 ** 对误匹配。
     */
    static String cleanBoldSpaces(String text) {
        if (text == null || !text.contains("**")) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        boolean inBold = false;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (!inBold) {
                    // 开标签 **：跳过后续空格
                    sb.append("**");
                    i += 2;
                    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                        i++;
                    }
                    inBold = true;
                } else {
                    // 闭标签 **：移除已累积的尾部空格
                    while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == ' ' || sb.charAt(sb.length() - 1) == '\t')) {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    sb.append("**");
                    i += 2;
                    inBold = false;
                }
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 修复表格格式问题：
     * 1. 表头行末尾多余的 | → 移除
     * 2. 分隔行格式修复：---| → |---|---|
     * 3. 表格行之间确保有换行
     */
    private static String fixTableFormat(String text) {
        if (text == null || !text.contains("|")) return text;

        String[] lines = text.split("\n", -1);
        java.util.List<String> fixed = new java.util.ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // 检测表格行（以 | 开头和结尾）
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // 修复表头行末尾多余的 |：| a | b || → | a | b |
                // 统计 | 的数量，如果奇数个，移除末尾的 |
                int pipeCount = 0;
                for (char c : trimmed.toCharArray()) {
                    if (c == '|') pipeCount++;
                }
                if (pipeCount % 2 == 0) {
                    // 偶数个 |，检查是否是双 || 结尾
                    if (trimmed.endsWith("||")) {
                        line = line.substring(0, line.lastIndexOf("||")) + "|";
                    }
                }
                fixed.add(line);
            }
            // 修复分隔行格式：---| 或 |--- → 补全为 |---|---|
            else if (trimmed.matches("^-+\\|.*") || trimmed.matches(".*\\|[-:]+$")) {
                // 尝试从上一行推断列数
                if (!fixed.isEmpty()) {
                    String prevLine = fixed.get(fixed.size() - 1).trim();
                    if (prevLine.startsWith("|") && prevLine.endsWith("|")) {
                        int colCount = prevLine.split("\\|").length - 1;
                        StringBuilder sepLine = new StringBuilder("|");
                        for (int c = 0; c < colCount; c++) {
                            sepLine.append("---|");
                        }
                        fixed.add(sepLine.toString());
                        continue;
                    }
                }
                fixed.add(line);
            }
            else {
                fixed.add(line);
            }
        }

        return String.join("\n", fixed);
    }

}
