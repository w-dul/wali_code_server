package cn.bugstack.ai.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.springai.MessageConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * Spring AI 补丁
 *
 * <p>关键修改：关闭 Spring AI 自动工具执行（internalToolExecutionEnabled=false），
 * 让 ADK ReAct 链路（AiCallNode → ToolCallNode）管理工具调用生命周期。
 *
 * <p>原因：Spring AI 的 DefaultToolCallingManager 在流式模式下自动执行工具时，
 * 如果 LLM 返回的工具参数 JSON 被截断（网络超时、token 预算耗尽等），
 * FunctionToolCallback.call() 中的 JsonParser.fromJson() 会抛出 JsonEOFException，
 * 导致 MessageAggregator 连续报错并终止 ReAct 循环。
 *
 * <p>ADK Runner 有自己的工具执行机制（runAgentWithUpdatedSession → FunctionTool.call()），
 * 不受 Spring AI 的 internalToolExecutionEnabled 影响。
 * 关闭后：ADK 事件流中的 FunctionResponse 正常返回，AiCallNode 正常处理。
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/9 08:17
 */
@Slf4j
public class MyMessageConverter extends MessageConverter {

    public MyMessageConverter(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public Prompt toLlmPrompt(LlmRequest llmRequest) {
        // super.toLlmPrompt() 内部的 handleUserContent() 已经正确处理了
        // Part.inlineData() → Media 和 Part.fileData() → Media 的转换，
        // 无需手动重复提取和追加，否则会导致图片数据被发送两次给模型。
        Prompt llmPrompt = super.toLlmPrompt(llmRequest);

        // 关闭 Spring AI 自动工具执行
        // 原因：DefaultToolCallingManager 在流式模式下无法处理截断的工具参数 JSON
        // 让 ADK ReAct 链路（AiCallNode → ToolCallNode）管理工具调用
        org.springframework.ai.chat.prompt.ChatOptions options = llmPrompt.getOptions();
        if (options instanceof ToolCallingChatOptions) {
            ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
            toolOptions.setInternalToolExecutionEnabled(false);
            log.info("[MyMessageConverter] 已关闭 Spring AI 自动工具执行 (internalToolExecutionEnabled=false)，工具调用由 ADK ReAct 链路管理");
        } else {
            log.warn("[MyMessageConverter] Prompt options 不是 ToolCallingChatOptions，无法关闭自动工具执行: {}",
                    options != null ? options.getClass().getSimpleName() : "null");
        }

        return llmPrompt;
    }

}
