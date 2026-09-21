package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.cases.react.PermissionConfirmManager;
import cn.bugstack.ai.domain.agent.service.security.PermissionGuard;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 权限确认回写 Controller
 *
 * <p>前端 PermissionConfirmModal 用户确认/拒绝后，POST /api/v1/permission/resolve
 * <p>后端接收结果，通过 PermissionConfirmManager 唤醒阻塞等待的 ToolCallNode 线程
 *
 * @author 教学版-WaLiCode
 * 2026/6/22
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/permission")
public class PermissionResolveController {

    @Resource
    private PermissionGuard permissionGuard;

    @Resource
    private PermissionConfirmManager permissionConfirmManager;

    /**
     * 接收用户确认结果
     */
    @PostMapping("/resolve")
    public Response<String> resolvePermission(@RequestBody PermissionResolveRequest request) {
        log.info("收到权限确认回写: confirmId={}, approved={}", request.getConfirmId(), request.isApproved());

        // 记录断路器状态
        permissionGuard.recordConfirmation("default", request.isApproved());

        // 唤醒等待线程
        permissionConfirmManager.resolve(
                request.getConfirmId(),
                request.isApproved(),
                request.getModifiedArgs()
        );

        return Response.<String>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data("ok")
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  DTO
    // ═══════════════════════════════════════════════════════════════

    @lombok.Data
    public static class PermissionResolveRequest {
        private String confirmId;
        private boolean approved;
        private String modifiedArgs;
    }
}
