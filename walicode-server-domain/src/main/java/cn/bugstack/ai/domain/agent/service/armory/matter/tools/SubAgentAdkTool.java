package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import cn.bugstack.ai.domain.agent.service.subagent.SubAgentManager;
import com.google.adk.tools.FunctionTool;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 子代理 ADK 工具
 * <p>
 * 注册为 ADK FunctionTool，让主 Agent 能通过工具调用启动子代理。
 * 使用反射式 API: FunctionTool.create(obj, methodName)
 * <p>
 * 对标 WaLiCode AgentTool：
 * - Explore：只读探索
 * - Verification：验证测试
 * - General：通用任务
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@Component
public class SubAgentAdkTool {

    /**
     * 当前请求上下文（ThreadLocal，在工具调用前设置）
     */
    private static final ThreadLocal<AgentContext> currentContext = new ThreadLocal<>();

    @Resource
    private SubAgentManager subAgentManager;

    /**
     * 设置当前 Agent 上下文（在 AiCallNode 调用 Runner 前设置）
     */
    public static void setCurrentContext(String agentId, String userId, String sessionId) {
        currentContext.set(new AgentContext(agentId, userId, sessionId));
    }

    /**
     * 清除当前 Agent 上下文
     */
    public static void clearCurrentContext() {
        currentContext.remove();
    }

    /**
     * 启动子代理处理复杂任务
     * <p>
     * ADK FunctionTool 通过反射调用此方法。
     *
     * @param subagentType 代理类型: Explore / Verification / General
     * @param prompt       任务描述
     * @param name         简短标识名（可选）
     * @return 执行结果 JSON 字符串
     */
    public String launchSubAgent(String subagentType, String prompt, String name) {
        AgentContext ctx = currentContext.get();
        if (ctx == null) {
            log.warn("子代理工具调用缺少上下文");
            return "错误: 子代理上下文未设置";
        }

        log.info("子代理工具调用: type={}, name={}, promptLen={}, agentId={}",
                subagentType, name, prompt != null ? prompt.length() : 0, ctx.agentId);

        try {
            SubAgentManager.AgentType agentType = SubAgentManager.AgentType.valueOf(subagentType.toUpperCase());
            String agentName = (name != null && !name.isEmpty()) ? name : subagentType.toLowerCase();

            SubAgentManager.AgentResult result = subAgentManager.execute(
                    agentType, prompt, agentName, ctx.agentId, ctx.userId, ctx.sessionId);

            log.info("子代理完成: type={}, success={}, durationMs={}",
                    subagentType, result.isSuccess(), result.getDurationMs());

            return result.toToolResult();

        } catch (IllegalArgumentException e) {
            log.warn("不支持的代理类型: {}", subagentType);
            return "不支持的代理类型: " + subagentType + "，支持: Explore/Verification/General";
        } catch (Exception e) {
            log.error("子代理执行异常", e);
            return "Agent error: " + e.getMessage();
        }
    }

    /**
     * 创建 ADK FunctionTool（反射式）
     */
    public FunctionTool createFunctionTool() {
        try {
            FunctionTool tool = FunctionTool.create(this, "launchSubAgent");
            log.info("子代理 FunctionTool 创建成功: name={}", tool.name());
            return tool;
        } catch (Exception e) {
            log.error("创建子代理 FunctionTool 失败", e);
            throw new RuntimeException(e);
        }
    }

    // ═══════════════════════════════════════════════════════════════

    private record AgentContext(String agentId, String userId, String sessionId) {}
}
