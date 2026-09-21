package cn.bugstack.ai.domain.agent.service.armory.matter.tools.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指令请求（Server → Client，通过 SSE 下发）
 *
 * @author walissh dev
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandRequest {

    /** 指令唯一 ID */
    private String cmdId;

    /** 指令类型 */
    private String type;

    /** 命令内容 */
    private String command;

    /** 工作目录 */
    private String cwd;

    /** 超时时间（毫秒） */
    private long timeoutMs;

    /** 关联的会话 ID */
    private String sessionId;

    /**
     * 生成本地执行指令
     */
    public static CommandRequest executeLocal(String sessionId, String command, String cwd, long timeoutMs) {
        return CommandRequest.builder()
                .cmdId("cmd_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId())
                .type("execute_local_command")
                .command(command)
                .cwd(cwd)
                .timeoutMs(timeoutMs)
                .sessionId(sessionId)
                .build();
    }
}
