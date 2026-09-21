package cn.bugstack.ai.cases.react.engine;

import cn.bugstack.ai.api.dto.ReActResultDTO;

/**
 * Agent 循环 SSE 回调接口
 * <p>所有事件通过此接口推送到前端
 * <p>对标 WaLiCode streamingAgent.ts 的 StreamingCallbacks
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
public interface AgentLoopCallbacks {

    /**
     * AI 文本流（增量）
     *
     * @param chunk    本次增量文本
     * @param fullText 累积全文
     */
    void onText(String chunk, String fullText);

    /**
     * 工具调用开始
     */
    void onToolCall(String toolCallId, String toolName, String args);

    /**
     * 工具执行进度
     */
    void onToolProgress(String toolCallId, String progress);

    /**
     * 工具执行结果
     *
     * @param status "success" / "error" / "denied"
     */
    void onToolResult(String toolCallId, String content, String status);

    /**
     * 一轮结束
     */
    void onRoundEnd(int currentRound, int maxRounds, int totalToolCalls);

    /**
     * 警告信息（非致命错误，循环可继续）
     */
    void onWarning(String message);

    /**
     * 错误信息（致命错误，循环将终止）
     */
    void onError(String message);

    /**
     * 完成
     */
    void onDone(ReActResultDTO result);
}
