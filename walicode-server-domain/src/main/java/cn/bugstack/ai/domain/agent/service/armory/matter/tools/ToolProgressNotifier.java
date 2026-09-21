package cn.bugstack.ai.domain.agent.service.armory.matter.tools;

import java.util.Map;

/**
 * 工具执行进度通知器
 *
 * <p>工具执行过程中，通过此接口向前端推送实时进度事件。
 * <p>由 case 层注入具体实现（SSE 推送），domain 层不依赖 web 技术。
 *
 * <p>核心作用：onToolStart/onToolEnd 推送轻量级进度事件（工具名称、参数摘要、成功/失败），
 * 用于前端实时显示工具执行状态。
 *
 * <p>注意：onToolResult（完整工具原始返回值推送）已禁用。
 * 关闭 internalToolExecutionEnabled=false 后，工具结果通过 ADK 事件流的
 * FunctionResponse 传递给 AiCallNode，通过 SSE 推送到前端，
 * 不再需要 side channel 推送，避免重复。
 *
 * @author walissh dev
 */
public interface ToolProgressNotifier {

    /**
     * 通知前端工具开始执行
     *
     * @param toolName 工具名称（如 readLocalFile、writeLocalFile）
     * @param args     工具参数摘要（如文件路径）
     */
    void onToolStart(String toolName, String args);

    /**
     * 通知前端工具执行完成
     *
     * @param toolName    工具名称
     * @param resultSummary 结果摘要（如 "读取成功，547 字节"）
     * @param success     是否成功
     */
    void onToolEnd(String toolName, String resultSummary, boolean success);

    /**
     * 通知前端工具执行的原始返回值（包含 command/output/exitCode 等）
     *
     * <p>此方法发送完整的工具返回 Map，前端 OutputPanel 可从中提取
     * 编译日志、命令输出等原始内容，而非 AI 总结文本。
     *
     * @param toolName 工具名称
     * @param args     工具参数（如执行的命令、文件路径）
     * @param rawResult 工具原始返回值 Map（包含 output/exitCode/success 等）
     */
    void onToolResult(String toolName, String args, Map<String, Object> rawResult);
}
