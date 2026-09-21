package cn.bugstack.ai.cases.react;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.cases.IAIAgentReActServiceCase;
import cn.bugstack.ai.cases.react.engine.AgentLoopExecutor;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.cases.react.node.RootNode;
import cn.bugstack.ai.domain.agent.service.engine.AgentLoopConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;

/**
 * AI 智能体 ReAct 执行服务实现
 *
 * <p>职责：
 * - 流式对话（SSE）：创建 emitter → 创建动态上下文 → 走节点链路
 * - 普通对话（非流式）：直接调用节点链路
 *
 * <p>节点链路：
 * RootNode → AiCallNode → LoopDecisionNode → UserFeedbackNode
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4 14:45
 */
@Slf4j
@Service
public class AIAgentReActServiceCase implements IAIAgentReActServiceCase {

    @Resource(name = "reactRootNode")
    private RootNode rootNode;

    @Resource
    private AgentLoopExecutor agentLoopExecutor;

    @Override
    public ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO) {
        // 1. 创建 SSE 发射器（30 分钟超时），设置 Content-Type 为 ndjson
        //    原 5 分钟超时在多轮工具调用 + 长报告生成场景时会提前截断
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(30 * 60 * 1000L) {
            @Override
            protected void extendResponse(org.springframework.http.server.ServerHttpResponse outputMessage) {
                outputMessage.getHeaders().set("Content-Type", "application/x-ndjson");
                outputMessage.getHeaders().set("X-Accel-Buffering", "no");
                outputMessage.getHeaders().set("Cache-Control", "no-cache");
            }
        };

        // 2. 注册 emitter 生命周期回调，感知客户端断开
        DefaultReActFactory.DynamicContext[] contextHolder = new DefaultReActFactory.DynamicContext[1];

        emitter.onCompletion(() -> {
            log.info("SSE 连接已完成 - sessionId:{}", requestDTO.getSessionId());
            if (contextHolder[0] != null) {
                contextHolder[0].markCancelled("client_completed");
            }
        });

        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时 - sessionId:{}", requestDTO.getSessionId());
            if (contextHolder[0] != null) {
                contextHolder[0].setStopReason("idle_timeout");
                contextHolder[0].markCancelled("timeout");
            }
        });

        emitter.onError(ex -> {
            log.warn("SSE 连接错误 - sessionId:{} reason:{}", requestDTO.getSessionId(), ex.getMessage());
            if (contextHolder[0] != null) {
                contextHolder[0].markCancelled("client_error");
            }
        });

        try {
            log.info("ReAct 流式对话开始 - agentId:{} userId:{} sessionId:{} terminalSessionId:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(),
                    requestDTO.getSessionId(), requestDTO.getTerminalSessionId());

            // 3. 初始化动态上下文
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(emitter)
                    .build();
            contextHolder[0] = dynamicContext;

            // 4. 构建 AgentLoopConfig
            AgentLoopConfig loopConfig = buildLoopConfig(requestDTO);

            // 5. 异步执行节点链路（AgentLoopExecutor 监督 + 节点链执行）
            Thread streamThread = new Thread(() -> {
                try {
                    ReActResultDTO result = agentLoopExecutor.executeSupervised(
                            loopConfig,
                            dynamicContext,
                            requestDTO,
                            (req, ctx) -> rootNode.apply(req, ctx));

                    log.info("ReAct 流式对话完成 - 步数:{}, 工具调用:{}, stopReason:{}",
                            result.getTotalSteps(), result.getTotalToolCalls(), result.getStopReason());
                    // 正常完成后关闭 SSE 连接，否则前端 reader.read() 不会返回 done
                    emitter.complete();
                } catch (Exception e) {
                    log.error("ReAct 流式对话异常", e);
                    try {
                        if (!dynamicContext.isCancelled()) {
                            try {
                                String errorMsg = e.getMessage() != null ? e.getMessage() : "未知错误";
                                emitter.send("{\"event\":\"error\",\"content\":\"" + errorMsg.replace("\\", "").replace("\"", "'") + "\"}\n",
                                    org.springframework.http.MediaType.APPLICATION_JSON);
                            } catch (Exception ignored) {}
                            emitter.complete();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "react-stream-" + requestDTO.getSessionId());

            // 保存线程引用，便于后续中断
            dynamicContext.setStreamThread(streamThread);

            streamThread.start();

            // 6. 启动 SSE 心跳守护线程（15秒间隔，防止代理/浏览器断开空闲连接）
            //    同时刷新 Agent LoopState 的活跃时间，避免 idle checker 误判超时
            Thread heartbeatThread = new Thread(() -> {
                while (!dynamicContext.isCancelled() && streamThread.isAlive()) {
                    try {
                        Thread.sleep(15_000L);
                        if (!dynamicContext.isCancelled() && streamThread.isAlive()) {
                            emitter.send("{\"event\":\"heartbeat\",\"timestamp\":" + System.currentTimeMillis() + "}\n",
                                org.springframework.http.MediaType.APPLICATION_JSON);
                            // 心跳成功 → 刷新 LoopState 活跃时间，防止 Agent 层 idle checker 误杀
                            cn.bugstack.ai.domain.agent.service.engine.LoopState ls = dynamicContext.getLoopState();
                            if (ls != null) {
                                ls.touch();
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // 心跳发送失败说明客户端已断开
                        log.warn("SSE 全局心跳发送失败 - sessionId:{}", requestDTO.getSessionId());
                        dynamicContext.markCancelled("heartbeat_send_failed");
                        break;
                    }
                }
            }, "react-heartbeat-" + requestDTO.getSessionId());
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();

            // 流结束时中断心跳线程
            emitter.onCompletion(() -> heartbeatThread.interrupt());
            emitter.onTimeout(() -> heartbeatThread.interrupt());
            emitter.onError(ex -> heartbeatThread.interrupt());

        } catch (Exception e) {
            log.error("ReAct 流式对话初始化失败", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    @Override
    public String chat(ChatRequestDTO requestDTO) {
        log.info("ReAct 普通对话开始 - agentId:{} userId:{}",
                requestDTO.getAgentId(), requestDTO.getUserId());

        try {
            // 普通对话使用同步 emitter（内部收集，不走 SSE）
            DefaultReActFactory.DynamicContext dynamicContext = DefaultReActFactory.DynamicContext.builder()
                    .emitter(new ResponseBodyEmitter(60 * 1000L))
                    .build();

            ReActResultDTO result = rootNode.apply(requestDTO, dynamicContext);

            return result.getContent() != null ? result.getContent() : "";

        } catch (Exception e) {
            log.error("ReAct 普通对话异常", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 构建 AgentLoopConfig
     * <p>从请求参数推导循环配置，后续可从 agent YAML 配置覆盖
     */
    private AgentLoopConfig buildLoopConfig(ChatRequestDTO requestDTO) {
        return AgentLoopConfig.builder()
                .mode(AgentLoopConfig.AgentMode.AGENT)
                .maxRounds(50)
                .maxToolCallsPerRound(10)
                .maxTotalToolCalls(200)
                .maxAiRetries(3)
                .idleTimeoutMs(600_000L)
                .maxTokenBudget(200_000)
                .diminishingReturnsThreshold(2)
                .build();
    }

}
