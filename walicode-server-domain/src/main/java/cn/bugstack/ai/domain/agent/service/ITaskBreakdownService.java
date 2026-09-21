package cn.bugstack.ai.domain.agent.service;

import cn.bugstack.ai.domain.agent.model.valobj.task.TaskBreakdownVO;

import java.util.List;
import java.util.Map;

/**
 * 任务拆解领域服务接口
 *
 * <p>当用户提出复杂任务时，将任务拆解为多个有序子任务。
 * 对应参考项目 WaLiCode 的 Task Breakdown 模式。
 *
 * <p>流程：
 * 1. 检测用户请求是否需要拆解（基于复杂度评估）
 * 2. 调用 AI 生成拆解方案
 * 3. 返回拆解结果供前端展示和用户确认
 *
 * @author walissh dev
 * 2026/6/19
 */
public interface ITaskBreakdownService {

    /**
     * 检测用户请求是否需要任务拆解
     */
    boolean shouldBreakdown(String userMessage, String sessionId);

    /**
     * 执行任务拆解
     */
    TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                               List<Map<String, Object>> messageHistory);

    /**
     * 更新子任务状态
     */
    void updateSubTaskStatus(String sessionId, int subTaskIndex, String status, String result);

    /**
     * 获取会话的任务拆解结果
     */
    TaskBreakdownVO getBreakdown(String sessionId);

    /**
     * 清除会话的拆解记录
     */
    void clearBreakdown(String sessionId);

}
