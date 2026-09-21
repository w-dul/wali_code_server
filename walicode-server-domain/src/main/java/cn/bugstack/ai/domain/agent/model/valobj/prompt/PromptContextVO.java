package cn.bugstack.ai.domain.agent.model.valobj.prompt;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PromptContextVO {

    private String serverInfo;
    private String osInfo;
    private String currentUser;
    private String currentDirectory;

    /** 当前工程名称（如 "ai-mcp-gateway"） */
    private String projectName;

    /** 当前工程根路径（如 "/Users/xxx/coding/ai-mcp-gateway"） */
    private String projectRootPath;

    private List<String> recentCommands;

    private List<MilestoneVO> milestoneVOS;
    
    private String toolResultSummary;
    
    private String taskDescription;

    // 核心记忆（由 CoreMemoryProvider 注入，XML 格式）
    private String coreMemories;

    // SSH 连接信息（由 TerminalStateProvider 注入）
    /** SSH 连接名称 */
    private String sshConnectionName;
    /** SSH 主机地址 */
    private String sshHost;
    /** SSH 端口 */
    private Integer sshPort;
    /** SSH 用户名 */
    private String sshUsername;
    /** SSH 连接 ID */
    private String sshConnectionId;
}
