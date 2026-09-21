package cn.bugstack.ai.trigger.http;

import cn.bugstack.ai.api.ISshFileService;
import cn.bugstack.ai.api.dto.SshFileContentResponseDTO;
import cn.bugstack.ai.api.dto.SshFileEntryDTO;
import cn.bugstack.ai.api.dto.SshFileTreeResponseDTO;
import cn.bugstack.ai.api.response.Response;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileContentEntity;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileEntryEntity;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileTreeEntity;
import cn.bugstack.ai.domain.ssh.service.ISshFileDomainService;
import cn.bugstack.ai.types.enums.ResponseCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/ssh/file")
@CrossOrigin(origins = "*")
public class SshFileController implements ISshFileService {

    @Resource
    private ISshFileDomainService sshFileDomainService;

    @RequestMapping(value = "tree", method = RequestMethod.GET)
    @Override
    public Response<SshFileTreeResponseDTO> tree(@RequestParam("connectionId") String connectionId,
                                                 @RequestParam(value = "path", required = false) String path) {
        try {
            SshFileTreeEntity entity = sshFileDomainService.tree(connectionId, path);
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(toTreeDTO(entity)).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("查询目录异常 connectionId={} path={}", connectionId, path, e);
            return Response.<SshFileTreeResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("查询目录失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "content", method = RequestMethod.GET)
    @Override
    public Response<SshFileContentResponseDTO> content(@RequestParam("connectionId") String connectionId,
                                                       @RequestParam("path") String path) {
        try {
            SshFileContentEntity entity = sshFileDomainService.content(connectionId, path);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(toContentDTO(entity)).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("读取文件异常 connectionId={} path={}", connectionId, path, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("读取文件失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "content-chunk", method = RequestMethod.GET)
    public Response<SshFileContentResponseDTO> contentChunk(@RequestParam("connectionId") String connectionId,
                                                            @RequestParam("path") String path,
                                                            @RequestParam(value = "offset", required = false) Long offset,
                                                            @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            SshFileContentEntity entity = sshFileDomainService.content(connectionId, path, offset, limit);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.SUCCESS.getCode()).info(ResponseCode.SUCCESS.getInfo())
                    .data(toContentDTO(entity)).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("分片读取文件异常 connectionId={} path={} offset={}", connectionId, path, offset, e);
            return Response.<SshFileContentResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode()).info("读取文件失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "create-file", method = RequestMethod.POST)
    public Response<Void> createFile(@RequestParam("connectionId") String connectionId,
                                     @RequestParam("path") String path,
                                     @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileDomainService.createFile(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("创建文件成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("创建文件异常 connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("创建文件失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "create-directory", method = RequestMethod.POST)
    public Response<Void> createDirectory(@RequestParam("connectionId") String connectionId,
                                          @RequestParam("path") String path,
                                          @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileDomainService.createDirectory(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("创建目录成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("创建目录异常 connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("创建目录失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "rename", method = RequestMethod.POST)
    public Response<Void> rename(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("oldPath") String oldPath,
                                 @RequestParam("newPath") String newPath,
                                 @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileDomainService.rename(connectionId, oldPath, newPath, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("重命名成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("重命名异常 connectionId={} oldPath={} newPath={}", connectionId, oldPath, newPath, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("重命名失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "delete", method = RequestMethod.POST)
    public Response<Void> delete(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 @RequestParam(value = "sudo", defaultValue = "false") boolean sudo) {
        try {
            sshFileDomainService.delete(connectionId, path, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("删除成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("删除异常 connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("删除失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "save-content", method = RequestMethod.POST)
    public Response<Void> saveContent(@RequestParam("connectionId") String connectionId,
                                      @RequestParam("path") String path,
                                      @RequestParam(value = "sudo", defaultValue = "false") boolean sudo,
                                      @RequestBody Map<String, String> body) {
        try {
            String content = body.getOrDefault("content", "");
            sshFileDomainService.saveFile(connectionId, path, content, sudo);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("保存文件成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("保存文件异常 connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("保存文件失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "upload", method = RequestMethod.POST)
    public Response<Void> upload(@RequestParam("connectionId") String connectionId,
                                 @RequestParam("path") String path,
                                 @RequestParam("file") MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {
            sshFileDomainService.uploadFile(connectionId, path, inputStream);
            return Response.<Void>builder().code(ResponseCode.SUCCESS.getCode()).info("上传文件成功").build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.<Void>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (Exception e) {
            log.error("上传文件异常 connectionId={} path={}", connectionId, path, e);
            return Response.<Void>builder().code(ResponseCode.UN_ERROR.getCode()).info("上传文件失败: " + e.getMessage()).build();
        }
    }

    @RequestMapping(value = "download", method = RequestMethod.GET)
    public void download(@RequestParam("connectionId") String connectionId,
                         @RequestParam("path") String path,
                         HttpServletResponse response) {
        try {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(fileName, "UTF-8"));
            try (OutputStream outputStream = response.getOutputStream()) {
                sshFileDomainService.downloadFile(connectionId, path, outputStream);
            }
        } catch (Exception e) {
            log.error("下载文件异常 connectionId={} path={}", connectionId, path, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    // ===== 转换方法 =====

    private static SshFileTreeResponseDTO toTreeDTO(SshFileTreeEntity entity) {
        List<SshFileEntryDTO> items = entity.getItems() == null
                ? Collections.emptyList()
                : entity.getItems().stream().map(SshFileController::toEntryDTO).collect(Collectors.toList());
        return SshFileTreeResponseDTO.builder()
                .rootPath(entity.getRootPath()).homePath(entity.getHomePath())
                .currentPath(entity.getCurrentPath()).parentPath(entity.getParentPath())
                .items(items).build();
    }

    private static SshFileEntryDTO toEntryDTO(SshFileEntryEntity item) {
        return SshFileEntryDTO.builder()
                .name(item.getName()).path(item.getPath()).directory(item.isDirectory())
                .size(item.getSize()).modifiedAt(item.getModifiedAt()).build();
    }

    private static SshFileContentResponseDTO toContentDTO(SshFileContentEntity entity) {
        return SshFileContentResponseDTO.builder()
                .path(entity.getPath()).name(entity.getName()).charset(entity.getCharset())
                .size(entity.getSize()).binary(entity.isBinary())
                .truncated(entity.isTruncated())
                .offset(entity.getOffset())
                .remaining(entity.getOffset() != null && entity.getSize() != null
                        ? Math.max(0, entity.getSize() - entity.getOffset() - entity.getContent().getBytes(StandardCharsets.UTF_8).length)
                        : null)
                .content(entity.getContent()).build();
    }
}
