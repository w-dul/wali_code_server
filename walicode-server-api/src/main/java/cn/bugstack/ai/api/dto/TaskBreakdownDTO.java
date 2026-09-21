package cn.bugstack.ai.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务拆解 DTO
 *
 * <p>当用户提出复杂任务时，AI 将任务拆解为多个有序子任务，
 * 每个子任务可独立执行并跟踪状态。
 *
 * <p>对应参考项目 WaLiCode 的 Task Breakdown 模式：
 * 检测复杂任务 → 提案拆解 → 用户确认 → 顺序执行
 *
 * @author walissh dev
 * 2026/6/19
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskBreakdownDTO {

    /**
     * 原始用户请求
     */
    private String originalRequest;

    /**
     * 拆解的子任务列表
     */
    private List<SubTask> subTasks;

    /**
     * 是否需要用户确认
     */
    @Builder.Default
    private boolean needConfirmation = true;

    /**
     * 拆解摘要（一句话描述整体计划）
     */
    private String summary;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SubTask {

        /**
         * 子任务序号（从 1 开始）
         */
        private int index;

        /**
         * 子任务标题
         */
        private String title;

        /**
         * 子任务描述
         */
        private String description;

        /**
         * 预期工具调用
         */
        private String expectedTools;

        /**
         * 子任务状态：pending / executing / completed / failed / skipped
         */
        @Builder.Default
        private String status = "pending";

        /**
         * 执行结果（完成后填充）
         */
        private String result;
    }

}
