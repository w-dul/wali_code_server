package cn.bugstack.ai.api.dto;

import lombok.Data;

/**
 * ReAct 对话事件 DTO
 *
 * <p>对应参考项目 WaLiCode 的 streamingAgent.ts 的回调事件结构
 * <p>扩展事件：task_breakdown（任务拆解提案）、task_progress（子任务进度）
 *
 * <p>对应 WaLiCode 的 streamingAgent.ts 的回调事件结构
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/5/4
 */
@Data
public class ReActEventDTO {

    /**
     * 事件类型
     * - text: 文本片断
     * - tool_call: 工具调用开始
     * - tool_result: 工具执行结果
     * - round_end: 一轮结束
     * - done: 全部完成
     * - error: 错误
     * - warning: 警告（非致命）
     * - task_breakdown: 任务拆解提案
     * - task_progress: 子任务状态变更
     * - permission_confirm: 权限确认请求（DENY/CONFIRM 级别工具需用户确认）
     * - tool_output: 工具实时输出片段（长命令执行中的 stdout/stderr 增量）
     * - round_start: 新轮次开始
     * - status: 状态更新（上下文压缩/降级/重连等）
     */
    private String event;

    /**
     * 权限确认信息（event=permission_confirm 时）
     */
    private PermissionInfo permission;

    /**
     * 工具实时输出（event=tool_output 时）
     */
    private String outputChunk;

    /**
     * 状态更新信息（event=status 时）
     */
    private String statusMessage;

    /**
     * 文件变更摘要（event=done 时，从工具调用结果中提取）
     */
    private ChangeSummaryDTO changeSummary;

    /**
     * 任务拆解信息（event=task_breakdown 时）
     */
    private TaskBreakdownDTO taskBreakdown;

    /**
     * 子任务进度信息（event=task_progress 时）
     */
    private TaskProgress taskProgress;

    @Data
    public static class TaskProgress {
        /** 子任务序号 */
        private int subTaskIndex;
        /** 子任务标题 */
        private String subTaskTitle;
        /** 状态：pending / executing / completed / failed / skipped */
        private String status;
        /** 总子任务数 */
        private int totalSubTasks;
        /** 已完成子任务数 */
        private int completedSubTasks;
    }

    /**
     * 事件内容（文本、片断 ID 等）
     */
    private String content;

    /**
     * 工具调用 ID（tool_call / tool_result 时）
     */
    private String toolCallId;

    /**
     * 工具名称（tool_call 时）
     */
    private String toolName;

    /**
     * 工具调用参数（tool_call 时，如命令字符串）
     */
    private String args;

    /**
     * 工具调用状态（tool_call / tool_result 时）
     * - pending: 等待执行
     * - running: 执行中
     * - success: 执行成功
     * - error: 执行失败
     */
    private String status;

    /**
     * 完整文本（累积，event=text 时）
     */
    private String fullText;

    /**
     * 步数信息（round_end 时）
     */
    private StepInfo stepInfo;

    @Data
    public static class StepInfo {
        /** 当前步数 */
        private int currentStep;
        /** 最大步数 */
        private int maxSteps;
        /** 是否继续执行 */
        private boolean shouldContinue;
        /** 工具调用总数 */
        private int totalToolCalls;
    }

    /**
     * 文件变更摘要 DTO
     */
    @Data
    public static class ChangeSummaryDTO {
        /** AI 生成的改动描述 */
        private String description;
        /** 本次对话的核心主题 */
        private String topic;
        /** 新增的文件列表 */
        private java.util.List<ChangeFile> created;
        /** 修改的文件列表 */
        private java.util.List<ChangeFile> modified;
        /** 删除的文件列表 */
        private java.util.List<ChangeFile> deleted;
    }

    /**
     * 单个文件变更
     */
    @Data
    public static class ChangeFile {
        /** 文件路径 */
        private String path;
        /** 变更类型：create / modify / delete */
        private String kind;
        /** 变更行数统计 */
        private int addedLines;
        private int removedLines;
    }

    /**
     * 权限确认信息
     */
    @Data
    public static class PermissionInfo {
        /** 确认请求唯一 ID */
        private String confirmId;
        /** 工具名称 */
        private String toolName;
        /** 工具参数（命令/路径等） */
        private String toolArgs;
        /** 风险等级：DENY / CONFIRM / ALLOW */
        private String riskLevel;
        /** 风险原因（为什么需要确认） */
        private String reason;
        /** 超时时间（毫秒），0=不超时 */
        private long timeoutMs;
    }

}
