package cn.bugstack.ai.cases.react.notifier;

import cn.bugstack.ai.domain.agent.service.armory.matter.tools.ToolProgressNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 工具进度通知器
 *
 * <p>每次 AiCallNode 调用 Runner 前创建一个新的实例绑定到 ThreadLocal，
 * CodeEditAdkTool 通过注入的 ToolProgressNotifier 接口调用，
 * 实际走 ThreadLocal 获取当前请求的 emitter。
 *
 * <p>由于 ADK Runner 用 boundedElastic 线程池执行工具方法，
 * 而 AiCallNode 主线程阻塞在 events 迭代上，
 * 工具方法和主线程不同线程，ThreadLocal 不可靠。
 *
 * <p>改用「快照」方案：AiCallNode bind 时把 emitter 存到 static volatile 字段，
 * 工具方法执行期间始终是同一个 emitter（单用户场景）。
 * 多用户并发场景需要改用 sessionId 路由 + 工具方法传参。
 *
 * @author walissh dev
 */
@Slf4j
@Component("sseToolProgressNotifier")
public class SseToolProgressNotifier implements ToolProgressNotifier {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 当前活跃的 emitter（单用户场景，volatile 保证可见性） */
    private static volatile ResponseBodyEmitter currentEmitter;

    /** 当前活跃的 sessionId */
    private static volatile String currentSessionId;

    /**
     * 绑定 sessionId 与 emitter（AiCallNode 调用 Runner 前调用）
     */
    public static void bind(String sessionId, ResponseBodyEmitter emitter) {
        currentSessionId = sessionId;
        currentEmitter = emitter;
        log.info("[SseToolProgressNotifier] 绑定 sessionId={}", sessionId);
    }

    /**
     * 解绑（Runner 结束后调用）
     */
    public static void unbind(String sessionId) {
        if (sessionId.equals(currentSessionId)) {
            currentEmitter = null;
            currentSessionId = null;
        }
        log.info("[SseToolProgressNotifier] 解绑 sessionId={}", sessionId);
    }

    private ResponseBodyEmitter getEmitter() {
        return currentEmitter;
    }

    @Override
    public void onToolStart(String toolName, String args) {
        ResponseBodyEmitter emitter = getEmitter();
        if (emitter == null) {
            log.debug("[SseToolProgressNotifier] emitter 为空，跳过工具开始通知: {} {}", toolName, args);
            return;
        }

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "tool_progress");
            event.put("toolName", toolName);
            event.put("args", args);
            event.put("status", "executing");
            event.put("timestamp", System.currentTimeMillis());

            emitter.send(objectMapper.writeValueAsString(event) + "\n",
                    org.springframework.http.MediaType.APPLICATION_JSON);
            log.info("[SseToolProgressNotifier] 工具开始: {} {}", toolName, args);
        } catch (Exception e) {
            log.debug("[SseToolProgressNotifier] 推送工具开始事件失败", e);
        }
    }

    @Override
    public void onToolEnd(String toolName, String resultSummary, boolean success) {
        ResponseBodyEmitter emitter = getEmitter();
        if (emitter == null) {
            log.debug("[SseToolProgressNotifier] emitter 为空，跳过工具结束通知: {} {}", toolName, resultSummary);
            return;
        }

        try {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "tool_progress");
            event.put("toolName", toolName);
            event.put("summary", resultSummary);
            event.put("status", success ? "success" : "error");
            event.put("timestamp", System.currentTimeMillis());

            emitter.send(objectMapper.writeValueAsString(event) + "\n",
                    org.springframework.http.MediaType.APPLICATION_JSON);
            log.info("[SseToolProgressNotifier] 工具完成: {} {} success={}", toolName, resultSummary, success);
        } catch (Exception e) {
            log.debug("[SseToolProgressNotifier] 推送工具完成事件失败", e);
        }
    }

    /**
     * 推送工具原始返回值到前端
     *
     * <p>发送 tool_call + tool_result SSE 事件对，前端 onStep 回调会
     * 解析 toolResult 中的 JSON（output/exitCode/success），写入 OutputPanel。
     * 这样 OutputPanel 显示的是原始编译日志/命令输出，而非 AI 总结文本。
     */
    @Override
    public void onToolResult(String toolName, String args, Map<String, Object> rawResult) {
        ResponseBodyEmitter emitter = getEmitter();
        if (emitter == null) {
            log.debug("[SseToolProgressNotifier] emitter 为空，跳过工具结果推送: {}", toolName);
            return;
        }

        try {
            String toolCallId = "call_" + toolName + "_" + System.currentTimeMillis();
            boolean success = Boolean.TRUE.equals(rawResult.get("success"));
            String resultJson = objectMapper.writeValueAsString(rawResult);

            // 1. 发送 tool_call 事件（in_progress）
            Map<String, Object> callEvent = new HashMap<>();
            callEvent.put("event", "tool_call");
            callEvent.put("toolCallId", toolCallId);
            callEvent.put("toolName", toolName);
            callEvent.put("args", args != null ? args : "");

            emitter.send(objectMapper.writeValueAsString(callEvent) + "\n",
                    org.springframework.http.MediaType.APPLICATION_JSON);

            // 2. 发送 tool_result 事件（包含原始返回值）
            Map<String, Object> resultEvent = new HashMap<>();
            resultEvent.put("event", "tool_result");
            resultEvent.put("toolCallId", toolCallId);
            resultEvent.put("content", resultJson);
            resultEvent.put("status", success ? "success" : "error");

            emitter.send(objectMapper.writeValueAsString(resultEvent) + "\n",
                    org.springframework.http.MediaType.APPLICATION_JSON);

            log.info("[SseToolProgressNotifier] 工具原始结果已推送: tool={}, args={}, resultLen={}, success={}",
                    toolName, args != null ? args.length() : 0, resultJson.length(), success);
        } catch (Exception e) {
            log.debug("[SseToolProgressNotifier] 推送工具原始结果失败", e);
        }
    }
}
