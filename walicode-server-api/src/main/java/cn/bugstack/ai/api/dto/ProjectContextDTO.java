package cn.bugstack.ai.api.dto;

import lombok.Data;

/**
 * 当前工程上下文
 * <p>
 * 由前端注入，描述用户当前打开的本地工程信息，
 * 用于动态 Prompt 构建时让 AI 识别当前工程而非按 SOUL.md 默认设定回答。
 */
@Data
public class ProjectContextDTO {

    /** 工程名称（文件夹名），如 "ai-mcp-gateway" */
    private String name;

    /** 工程根路径（绝对路径），如 "/Users/xxx/coding/ai-mcp-gateway" */
    private String rootPath;

}
