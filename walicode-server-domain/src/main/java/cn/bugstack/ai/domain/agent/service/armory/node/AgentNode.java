package cn.bugstack.ai.domain.agent.service.armory.node;

import cn.bugstack.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.bugstack.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.bugstack.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.bugstack.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.BuildValidationAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.CodeEditAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.LocalExecuteAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SshExecuteAdkTool;
import cn.bugstack.ai.domain.agent.service.armory.matter.tools.SubAgentAdkTool;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import com.google.adk.agents.LlmAgent;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.tools.FunctionTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;
    
    @Resource
    private SshExecuteAdkTool sshExecuteAdkTool;

    @Resource
    private LocalExecuteAdkTool localExecuteAdkTool;

    @Resource
    private CodeEditAdkTool codeEditAdkTool;

    @Resource
    private BuildValidationAdkTool buildValidationAdkTool;

    @Resource
    private SubAgentAdkTool subAgentAdkTool;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new SpringAI(chatModel, (StreamingChatModel) chatModel, chatModelConfig.getModel()))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // 构建 ADK 工具列表
            List<Object> adkTools = new ArrayList<>();

            // 添加 SSH 执行工具（ADK 原生 FunctionTool）
            try {
                log.info("开始创建 SSH 执行工具, sshExecuteAdkTool={}", sshExecuteAdkTool);
                Method sshMethod = SshExecuteAdkTool.class.getMethod("executeCommand", String.class);
                FunctionTool sshTool = FunctionTool.create(sshExecuteAdkTool, sshMethod);
                log.info("FunctionTool 创建成功: name={}, declaration={}",
                        sshTool.name(),
                        sshTool.declaration().isPresent() ? sshTool.declaration().get() : "null");
                adkTools.add(sshTool);
                log.info("为 Agent [{}] 注册 SSH 执行工具成功", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建 SSH ADK 工具失败", e);
            }

            // 添加本地命令执行工具（通过 Tauri HTTP Server 调用本地 Shell）
            try {
                Method localMethod = LocalExecuteAdkTool.class.getMethod("executeLocalCommand", String.class);
                FunctionTool localExecTool = FunctionTool.create(localExecuteAdkTool, localMethod);
                adkTools.add(localExecTool);
                log.info("为 Agent [{}] 注册本地命令执行工具成功", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建 LocalExecute ADK 工具失败", e);
            }

            // 添加 AI Coding 工具集（远程 SSH 文件读写、搜索等）
            try {
                FunctionTool readFileTool = FunctionTool.create(codeEditAdkTool, "readFile");
                FunctionTool writeFileTool = FunctionTool.create(codeEditAdkTool, "writeFile");
                FunctionTool listFilesTool = FunctionTool.create(codeEditAdkTool, "listFiles");
                FunctionTool searchTool = FunctionTool.create(codeEditAdkTool, "searchInFiles");
                FunctionTool createFileTool = FunctionTool.create(codeEditAdkTool, "createFile");
                FunctionTool deleteFileTool = FunctionTool.create(codeEditAdkTool, "deleteFile");

                adkTools.add(readFileTool);
                adkTools.add(writeFileTool);
                adkTools.add(listFilesTool);
                adkTools.add(searchTool);
                adkTools.add(createFileTool);
                adkTools.add(deleteFileTool);

                log.info("为 Agent [{}] 注册远程 CodeEdit 工具集成功（readFile/writeFile/listFiles/searchInFiles/createFile/deleteFile）", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建远程 CodeEdit ADK 工具失败", e);
            }

            // 添加本地文件操作工具集（不依赖 SSH，直接操作本地文件系统）
            try {
                FunctionTool readLocalFileTool = FunctionTool.create(codeEditAdkTool, "readLocalFile");
                FunctionTool writeLocalFileTool = FunctionTool.create(codeEditAdkTool, "writeLocalFile");
                FunctionTool listLocalFilesTool = FunctionTool.create(codeEditAdkTool, "listLocalFiles");
                FunctionTool searchLocalTool = FunctionTool.create(codeEditAdkTool, "searchInLocalFiles");
                FunctionTool createLocalFileTool = FunctionTool.create(codeEditAdkTool, "createLocalFile");
                FunctionTool deleteLocalFileTool = FunctionTool.create(codeEditAdkTool, "deleteLocalFile");

                adkTools.add(readLocalFileTool);
                adkTools.add(writeLocalFileTool);
                adkTools.add(listLocalFilesTool);
                adkTools.add(searchLocalTool);
                adkTools.add(createLocalFileTool);
                adkTools.add(deleteLocalFileTool);

                log.info("为 Agent [{}] 注册本地 CodeEdit 工具集成功（readLocalFile/writeLocalFile/listLocalFiles/searchInLocalFiles/createLocalFile/deleteLocalFile）", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建本地 CodeEdit ADK 工具失败", e);
            }

            // 添加编译 / 测试 / Lint 校验工具
            try {
                FunctionTool compileProjectTool = FunctionTool.create(buildValidationAdkTool, "compileProject");
                FunctionTool compileTestsTool = FunctionTool.create(buildValidationAdkTool, "compileTests");
                FunctionTool runUnitTestsTool = FunctionTool.create(buildValidationAdkTool, "runUnitTests");
                FunctionTool runLintTool = FunctionTool.create(buildValidationAdkTool, "runLint");

                adkTools.add(compileProjectTool);
                adkTools.add(compileTestsTool);
                adkTools.add(runUnitTestsTool);
                adkTools.add(runLintTool);

                log.info("为 Agent [{}] 注册 BuildValidation 工具成功（compileProject/compileTests/runUnitTests/runLint）", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建 BuildValidation ADK 工具失败", e);
            }

            // 添加子代理工具（AgentTool — Explore/Verification/General）
            try {
                FunctionTool subAgentTool = subAgentAdkTool.createFunctionTool();
                adkTools.add(subAgentTool);
                log.info("为 Agent [{}] 注册子代理工具成功（launchSubAgent: Explore/Verification/General）", agentConfig.getName());
            } catch (Exception e) {
                log.error("创建 SubAgent ADK 工具失败", e);
            }

            // 注册工具到 Agent
            if (!adkTools.isEmpty()) {
                log.info("为 Agent [{}] 注册 {} 个工具", agentConfig.getName(), adkTools.size());
                builder.tools(adkTools);
            } else {
                log.warn("Agent [{}] 没有注册任何工具！", agentConfig.getName());
            }

            LlmAgent llmAgent = builder.build();
            
            // 打印 Agent 的工具信息
            log.info("Agent [{}] 构建完成, tools={}", agentConfig.getName(), llmAgent.tools());
            
            dynamicContext.getAgentGroup().put(agentConfig.getName(), llmAgent);
        }

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
