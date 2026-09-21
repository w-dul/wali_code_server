package cn.bugstack.ai.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequestDTO {

    private String agentId;
    private String userId;
    private String sessionId;
    private String message;

    /**
     * SSH 终端会话 ID（用于智能体执行命令）
     * 如果未指定，系统将尝试从会话绑定中获取
     */
    private String terminalSessionId;

    /**
     * 当前工程上下文（由前端注入，用于动态 Prompt 构建时识别用户当前打开的工程）
     */
    private ProjectContextDTO projectContext;

    /**
     * 内联图片数据（base64 编码），支持多模态输入
     * 前端上传的图片通过此字段传递给 AI 模型
     */
    private List<InlineData> inlineDatas;

    /**
     * 内联数据（图片等二进制内容）
     */
    @Data
    public static class InlineData {
        /**
         * base64 编码的数据（不含 data:image/xxx;base64, 前缀）
         */
        private String data;
        /**
         * MIME 类型，如 image/png、image/jpeg
         */
        private String mimeType;
    }

}
