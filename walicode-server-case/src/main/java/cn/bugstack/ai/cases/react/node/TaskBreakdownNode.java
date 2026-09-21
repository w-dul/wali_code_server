package cn.bugstack.ai.cases.react.node;

import cn.bugstack.ai.api.dto.ChatRequestDTO;
import cn.bugstack.ai.api.dto.ReActEventDTO;
import cn.bugstack.ai.api.dto.ReActResultDTO;
import cn.bugstack.ai.api.dto.TaskBreakdownDTO;
import cn.bugstack.ai.cases.react.AbstractAIAgentReActSupport;
import cn.bugstack.ai.cases.react.factory.DefaultReActFactory;
import cn.bugstack.ai.domain.agent.model.valobj.task.TaskBreakdownVO;
import cn.bugstack.ai.domain.agent.service.ITaskBreakdownService;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 任务拆解节点
 *
 * <p>插入位置：RootNode → TaskBreakdownNode → AiCallNode
 *
 * <p>职责：
 * 1. 检测用户请求是否需要任务拆解
 * 2. 如需拆解，调用 LLM 生成拆解方案
 * 3. 通过 SSE 发送 task_breakdown 事件给前端
 * 4. 将拆解方案注入上下文，供后续 AiCallNode 使用
 *
 * @author walissh dev
 * 2026/6/19
 */
@Slf4j
@Component("reactTaskBreakdownNode")
public class TaskBreakdownNode extends AbstractAIAgentReActSupport {

    @Resource
    private ITaskBreakdownService taskBreakdownService;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter,
                                      DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        String userMessage = requestParameter.getMessage();
        String sessionId = dynamicContext.getSessionId();

        log.info("ReAct TaskBreakdownNode - 检测任务拆解: sessionId={}", sessionId);

        // 1. 检测是否需要拆解
        boolean needBreakdown = taskBreakdownService.shouldBreakdown(userMessage, sessionId);

        if (!needBreakdown) {
            log.info("任务无需拆解，直接路由到 AiCallNode");
            return router(requestParameter, dynamicContext);
        }

        // 2. 执行拆解
        TaskBreakdownVO breakdownVO = taskBreakdownService.breakdown(
                userMessage,
                sessionId,
                dynamicContext.getAgentId(),
                dynamicContext.getMessageHistory()
        );

        // 3. 如果拆解结果为空（任务实际简单），直接跳过
        if (breakdownVO.getSubTasks() == null || breakdownVO.getSubTasks().isEmpty()) {
            log.info("拆解结果为空，任务简单，直接路由到 AiCallNode");
            return router(requestParameter, dynamicContext);
        }

        // 4. VO → DTO 转换
        TaskBreakdownDTO breakdownDTO = convertToDTO(breakdownVO);

        // 5. 通过 SSE 发送拆解提案
        sendTaskBreakdownEvent(dynamicContext.getEmitter(), breakdownDTO);

        // 6. 将拆解方案注入用户消息（增强 AI 的执行计划性）
        String enrichedMessage = buildBreakdownEnhancedMessage(userMessage, breakdownVO);
        requestParameter.setMessage(enrichedMessage);

        log.info("任务拆解完成，注入 {} 个子任务到消息，路由到 AiCallNode",
                breakdownVO.getSubTasks().size());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * VO → DTO 转换
     */
    private TaskBreakdownDTO convertToDTO(TaskBreakdownVO vo) {
        List<TaskBreakdownDTO.SubTask> subTasks = new ArrayList<>();
        for (TaskBreakdownVO.SubTask st : vo.getSubTasks()) {
            subTasks.add(TaskBreakdownDTO.SubTask.builder()
                    .index(st.getIndex())
                    .title(st.getTitle())
                    .description(st.getDescription())
                    .expectedTools(st.getExpectedTools())
                    .status(st.getStatus())
                    .result(st.getResult())
                    .build());
        }
        return TaskBreakdownDTO.builder()
                .originalRequest(vo.getOriginalRequest())
                .subTasks(subTasks)
                .needConfirmation(vo.isNeedConfirmation())
                .summary(vo.getSummary())
                .build();
    }

    /**
     * 发送任务拆解 SSE 事件
     */
    private void sendTaskBreakdownEvent(ResponseBodyEmitter emitter, TaskBreakdownDTO breakdown) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("task_breakdown");
            event.setTaskBreakdown(breakdown);
            emitter.send(objectMapper.writeValueAsString(event) + "\n",
                    org.springframework.http.MediaType.APPLICATION_JSON);
            log.info("发送 task_breakdown 事件: {} 个子任务", breakdown.getSubTasks().size());
        } catch (Exception e) {
            log.warn("发送 task_breakdown 事件失败: {}", e.getMessage());
        }
    }

    /**
     * 将拆解方案注入用户消息
     */
    private String buildBreakdownEnhancedMessage(String originalMessage, TaskBreakdownVO breakdown) {
        StringBuilder sb = new StringBuilder(originalMessage);
        sb.append("\n\n---\n📋 任务拆解计划：\n");

        for (TaskBreakdownVO.SubTask st : breakdown.getSubTasks()) {
            sb.append(st.getIndex()).append(". **").append(st.getTitle()).append("**");
            if (st.getDescription() != null && !st.getDescription().isEmpty()) {
                sb.append(" - ").append(st.getDescription());
            }
            if (st.getExpectedTools() != null && !st.getExpectedTools().isEmpty()) {
                sb.append(" (工具: ").append(st.getExpectedTools()).append(")");
            }
            sb.append("\n");
        }

        sb.append("\n请按顺序执行以上子任务，每完成一个子任务后简要汇报结果，然后继续下一个。\n---");
        return sb.toString();
    }

}
