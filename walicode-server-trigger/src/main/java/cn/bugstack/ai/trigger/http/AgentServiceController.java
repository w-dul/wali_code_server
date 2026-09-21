package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.IAgentService;
import cn.bugstack.ai.api.dto.*;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.cases.IAIAgentReActServiceCase;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.service.IChatService;
import cn.bugstack.ai.domain.agent.service.armory.matter.mcp.server.SshExecuteMcpService;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandDispatcher;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.command.CommandResult;
import cn.bugstack.ai.types.enums.ResponseCode;
import cn.bugstack.ai.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/1/20 08:23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/")
@CrossOrigin(origins = "*")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    @Resource
    private IAIAgentReActServiceCase reactServiceCase;

    @Resource
    private CommandDispatcher commandDispatcher;

    // 会话绑定映射：chatSessionId → terminalSessionId（原 SshAgentController 迁入）
    private final Map<String, String> sessionBindings = new ConcurrentHashMap<>();

    @RequestMapping(value = "query_ai_agent_config_list", method = RequestMethod.GET)
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        try {
            log.info("查询智能体配置列表");

            List<AiAgentConfigTableVO.Agent> agentConfigs = chatService.queryAiAgentConfigList();

            List<AiAgentConfigResponseDTO> responseDTOS = agentConfigs.stream().map(agentConfig -> {
                AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
                responseDTO.setAgentId(agentConfig.getAgentId());
                responseDTO.setAgentName(agentConfig.getAgentName());
                responseDTO.setAgentDesc(agentConfig.getAgentDesc());
                return responseDTO;
            }).collect(Collectors.toList());

            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTOS)
                    .build();

        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("查询智能体配置列表失败", e);
            return Response.<List<AiAgentConfigResponseDTO>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.POST)
    @Override
    public Response<CreateSessionResponseDTO> createSession(@RequestBody CreateSessionRequestDTO requestDTO) {
        try {
            log.info("创建会话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

            CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
            responseDTO.setSessionId(sessionId);

            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("查询智能体配置列表异常", e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("创建会话失败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<CreateSessionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @RequestMapping(value = "create_session", method = RequestMethod.GET)
    public Response<CreateSessionResponseDTO> createSession(@RequestParam("agentId") String agentId, @RequestParam("userId") String userId) {
        CreateSessionRequestDTO requestDTO = new CreateSessionRequestDTO();
        requestDTO.setAgentId(agentId);
        requestDTO.setUserId(userId);
        return createSession(requestDTO);
    }

    @RequestMapping(value = "chat", method = RequestMethod.POST)
    @Override
    public Response<ChatResponseDTO> chat(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
            }

            List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

            ChatResponseDTO responseDTO = new ChatResponseDTO();
            responseDTO.setContent(String.join("\n", messages));

            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(responseDTO)
                    .build();
        } catch (AppException e) {
            log.error("智能体对话异常", e);
            return Response.<ChatResponseDTO>builder()
                    .code(e.getCode())
                    .info(e.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("智能体对话败 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), e);
            return Response.<ChatResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSH 终端绑定 API（原 SshAgentController 合入）
    // ═══════════════════════════════════════════════════════════════

    @RequestMapping(value = "bind_terminal", method = RequestMethod.POST)
    public Response<BindTerminalResponseDTO> bindTerminal(@RequestBody BindTerminalRequestDTO requestDTO) {
        try {
            String chatSessionId = requestDTO.getChatSessionId();
            String terminalSessionId = requestDTO.getTerminalSessionId();

            log.info("绑定终端会话: chatSessionId={}, terminalSessionId={}", chatSessionId, terminalSessionId);

            if (chatSessionId == null || chatSessionId.isEmpty()) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("chatSessionId 不能为空")
                        .build();
            }

            if (terminalSessionId == null || terminalSessionId.isEmpty()) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                        .info("terminalSessionId 不能为空")
                        .build();
            }

            sessionBindings.put(chatSessionId, terminalSessionId);

            BindTerminalResponseDTO response = BindTerminalResponseDTO.builder()
                    .chatSessionId(chatSessionId)
                    .terminalSessionId(terminalSessionId)
                    .bound(true)
                    .build();

            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(response)
                    .build();

        } catch (Exception e) {
            log.error("绑定终端会话失败", e);
            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("绑定失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "unbind_terminal", method = RequestMethod.POST)
    public Response<Void> unbindTerminal(@RequestParam("chatSessionId") String chatSessionId) {
        try {
            log.info("解绑终端会话: chatSessionId={}", chatSessionId);
            sessionBindings.remove(chatSessionId);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();

        } catch (Exception e) {
            log.error("解绑终端会话失败", e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("解绑失败: " + e.getMessage())
                    .build();
        }
    }

    @RequestMapping(value = "query_binding", method = RequestMethod.GET)
    public Response<BindTerminalResponseDTO> queryBinding(@RequestParam("chatSessionId") String chatSessionId) {
        try {
            String terminalSessionId = sessionBindings.get(chatSessionId);

            if (terminalSessionId == null) {
                return Response.<BindTerminalResponseDTO>builder()
                        .code(ResponseCode.SUCCESS.getCode())
                        .info("未绑定终端")
                        .data(BindTerminalResponseDTO.builder()
                                .chatSessionId(chatSessionId)
                                .bound(false)
                                .build())
                        .build();
            }

            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(BindTerminalResponseDTO.builder()
                            .chatSessionId(chatSessionId)
                            .terminalSessionId(terminalSessionId)
                            .bound(true)
                            .build())
                    .build();

        } catch (Exception e) {
            log.error("查询绑定失败", e);
            return Response.<BindTerminalResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("查询失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 设置当前线程的终端会话（供工具调用前设置）
     */
    public void setCurrentTerminalSession(String chatSessionId) {
        String terminalSessionId = sessionBindings.get(chatSessionId);
        if (terminalSessionId != null) {
            SshExecuteMcpService.setCurrentTerminalSession(terminalSessionId);
        }
    }

    /**
     * 获取绑定的终端会话 ID
     */
    public String getTerminalSessionId(String chatSessionId) {
        return sessionBindings.get(chatSessionId);
    }

    // ═══════════════════════════════════════════════════════════════
    //  本地指令结果回传 API（Client → Server）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Client 回传本地指令执行结果
     *
     * <p>当 Client 收到 execute_local_command SSE 事件后，执行本地命令，
     * 然后通过此端点将结果回传给 Server。
     *
     * @param result 指令执行结果
     * @return 处理状态
     */
    @RequestMapping(value = "tool_result", method = RequestMethod.POST)
    public Response<Void> receiveToolResult(@RequestBody CommandResult result) {
        try {
            log.info("收到本地指令结果: cmdId={}, sessionId={}, status={}, exitCode={}",
                    result.getCmdId(), result.getSessionId(), result.getStatus(), result.getExitCode());

            commandDispatcher.completeCommand(result.getCmdId(), result);

            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("结果已接收")
                    .build();

        } catch (Exception e) {
            log.error("处理本地指令结果失败: cmdId={}", result.getCmdId(), e);
            return Response.<Void>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("处理失败: " + e.getMessage())
                    .build();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 2: GET 轮询端点（Client 主动拉取结果）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Client 轮询指定 cmdId 的执行结果
     *
     * <p>GET Cache API: Client 定期调用此端点检查结果是否就绪。
     * 结果被消费后即从缓存中移除（一次性读取）。
     *
     * @param cmdId 指令 ID
     * @return 缓存的执行结果，未就绪返回 null
     */
    @RequestMapping(value = "tool_result/pending", method = RequestMethod.GET)
    public Response<CommandResult> pollToolResult(@RequestParam("cmdId") String cmdId) {
        try {
            CommandResult result = commandDispatcher.pollResult(cmdId);
            if (result != null) {
                log.info("[GET Cache] 返回缓存结果: cmdId={}, status={}", cmdId, result.getStatus());
            }
            return Response.<CommandResult>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(result)
                    .build();
        } catch (Exception e) {
            log.error("[GET Cache] 轮询结果失败: cmdId={}", cmdId, e);
            return Response.<CommandResult>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("轮询失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Client 重连后批量拉取所有缓存结果
     *
     * <p>当 Client 检测到 SSE 断线重连后，调用此端点获取断线期间错过的所有结果。
     * 结果被消费后即从缓存中移除。
     *
     * @return 所有缓存的结果（cmdId → CommandResult）
     */
    @RequestMapping(value = "tool_result/pending_all", method = RequestMethod.GET)
    public Response<Map<String, CommandResult>> pollAllToolResults() {
        try {
            Map<String, CommandResult> results = commandDispatcher.pollAllResults();
            if (!results.isEmpty()) {
                log.info("[GET Cache] 批量返回缓存结果: {} 个", results.size());
            }
            return Response.<Map<String, CommandResult>>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(results)
                    .build();
        } catch (Exception e) {
            log.error("[GET Cache] 批量轮询结果失败", e);
            return Response.<Map<String, CommandResult>>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info("批量轮询失败: " + e.getMessage())
                    .build();
        }
    }

    /**
     * 获取本地指令分发状态
     *
     * @return 分发器状态信息
     */
    @RequestMapping(value = "command_status", method = RequestMethod.GET)
    public Response<Map<String, Object>> getCommandStatus() {
        Map<String, Object> status = Map.of(
                "pendingCommands", commandDispatcher.getPendingCount(),
                "clientConnected", CommandDispatcher.currentEmitter() != null
        );
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(status)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  原有 API
    // ═══════════════════════════════════════════════════════════════

    @RequestMapping(value = "chat_stream", method = RequestMethod.POST)
    @Override
    public ResponseBodyEmitter chatStream(@RequestBody ChatRequestDTO requestDTO) {
        try {
            log.info("ReAct 流式对话 agentId:{} userId:{} sessionId:{} terminalSessionId:{} message:{}",
                    requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId(),
                    requestDTO.getTerminalSessionId(), requestDTO.getMessage());

            // 如果未指定 sessionId，先创建
            String sessionId = requestDTO.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
                requestDTO.setSessionId(sessionId);
            }

            // 路由到 ReAct 服务（Case 层）
            return reactServiceCase.chatStream(requestDTO);
        } catch (Exception e) {
            log.error("ReAct 流式对话失败", e);
            ResponseBodyEmitter emitter = new ResponseBodyEmitter();
            emitter.completeWithError(e);
            return emitter;
        }
    }

}
