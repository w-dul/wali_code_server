package cn.bugstack.ai.infrastructure.adapter.port;

import cn.bugstack.ai.domain.ssh.adapter.port.ISshFilePort;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileContentEntity;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileEntryEntity;
import cn.bugstack.ai.domain.ssh.model.entity.SshFileTreeEntity;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SshFilePort implements ISshFilePort {

    private static final int MAX_LIST_COUNT = 500;
    private static final int MAX_READ_BYTES = 512 * 1024;
    private static final int CHUNK_READ_BYTES = 256 * 1024;
    private static final String ROOT = "/";

    @Resource
    private SshSessionPort sshSessionPort;

    private final ConcurrentHashMap<String, ChannelSftp> sftpChannels = new ConcurrentHashMap<>();

    // ==================== 查询 ====================

    @Override
    public SshFileTreeEntity listDirectory(String connectionId, String path) {
        Session session = requireSession(connectionId);
        try {
            ChannelSftp sftp = getOrOpenSftp(connectionId, session);
            String homePath = normalizePath(sftp.pwd());
            String currentPath = normalizePath(path == null || path.isBlank() ? homePath : path);
            String parentPath = parentPathOf(currentPath);

            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> list = sftp.ls(currentPath);
            List<SshFileEntryEntity> items = new ArrayList<>();

            for (ChannelSftp.LsEntry entry : list) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) continue;

                SftpATTRS attrs = entry.getAttrs();
                boolean directory = attrs != null && attrs.isDir();
                String itemPath = ROOT.equals(currentPath) ? ROOT + name : currentPath + "/" + name;
                Long modifiedAt = attrs == null ? null : attrs.getMTime() * 1000L;
                Long size = attrs == null || directory ? null : attrs.getSize();

                items.add(SshFileEntryEntity.builder()
                        .name(name).path(itemPath).directory(directory)
                        .modifiedAt(modifiedAt).size(size).build());
            }

            items.sort(Comparator.comparing(SshFileEntryEntity::isDirectory).reversed()
                    .thenComparing(SshFileEntryEntity::getName, String.CASE_INSENSITIVE_ORDER));
            if (items.size() > MAX_LIST_COUNT) items = items.subList(0, MAX_LIST_COUNT);

            return SshFileTreeEntity.builder()
                    .rootPath(ROOT).homePath(homePath).currentPath(currentPath)
                    .parentPath(parentPath).items(items).build();
        } catch (SftpException e) {
            throw new IllegalArgumentException("目录不存在或无权限访问: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("查询目录失败", e);
        }
    }

    /** 基础读取（前 512KB），兼容旧接口 */
    @Override
    public SshFileContentEntity readFile(String connectionId, String path) {
        return readFile(connectionId, path, null, null);
    }

    /** 支持 offset + limit 的大文件分片读取 */
    @Override
    public SshFileContentEntity readFile(String connectionId, String path, Long offset, Integer limit) {
        Session session = requireSession(connectionId);
        try {
            ChannelSftp sftp = getOrOpenSftp(connectionId, session);
            String normalizedPath = normalizePath(path);
            SftpATTRS attrs = sftp.stat(normalizedPath);
            if (attrs == null || attrs.isDir()) throw new IllegalArgumentException("目标不是文件: " + normalizedPath);

            long fileSize = attrs.getSize();
            long readOffset = (offset != null && offset >= 0) ? offset : 0;
            int readLimit = (limit != null && limit > 0) ? Math.min(limit, CHUNK_READ_BYTES) : CHUNK_READ_BYTES;
            int readBytes = (int) Math.min(readLimit, fileSize - readOffset);
            boolean truncated = readBytes < (fileSize - readOffset);

            byte[] contentBytes = readBytesWithOffset(sftp, normalizedPath, readOffset, readBytes);
            boolean binary = isLikelyBinary(contentBytes);
            String content = binary ? "" : new String(contentBytes, StandardCharsets.UTF_8);


            return SshFileContentEntity.builder()
                    .path(normalizedPath).name(fileNameOf(normalizedPath)).charset("UTF-8")
                    .size(fileSize).binary(binary).truncated(truncated)
                    .content(content).offset(readOffset).build();
        } catch (SftpException e) {
            throw new IllegalArgumentException("文件不存在或无权限访问: " + path, e);
        } catch (Exception e) {
            throw new RuntimeException("读取文件失败", e);
        }
    }

    // ==================== 写操作：优先 SFTP，失败自动 sudo ====================

    @Override
    public void createFile(String connectionId, String path, boolean useSudo) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        if (useSudo) {
            execSudo(session, "touch " + shellEscape(normalizedPath));
        } else {
            try {
                ChannelSftp sftp = getOrOpenSftp(connectionId, session);
                try (InputStream in = new ByteArrayInputStream(new byte[0])) { sftp.put(in, normalizedPath); }
            } catch (Exception e) {
                if (isPermissionDenied(e)) {
                    log.warn("SFTP创建文件权限不足，自动尝试sudo: {}", normalizedPath);
                    execSudo(session, "touch " + shellEscape(normalizedPath));
                } else {
                    throw new IllegalArgumentException("创建文件失败: " + e.getMessage(), e);
                }
            }
        }
        log.info("创建文件: connectionId={}, path={}, sudo={}", connectionId, normalizedPath, useSudo);
    }

    @Override
    public void createDirectory(String connectionId, String path, boolean useSudo) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        if (useSudo) {
            execSudo(session, "mkdir -p " + shellEscape(normalizedPath));
        } else {
            try {
                ChannelSftp sftp = getOrOpenSftp(connectionId, session);
                sftp.mkdir(normalizedPath);
            } catch (Exception e) {
                if (isPermissionDenied(e)) {
                    log.warn("SFTP创建目录权限不足，自动尝试sudo: {}", normalizedPath);
                    execSudo(session, "mkdir -p " + shellEscape(normalizedPath));
                } else {
                    throw new IllegalArgumentException("创建目录失败: " + e.getMessage(), e);
                }
            }
        }
        log.info("创建目录: connectionId={}, path={}, sudo={}", connectionId, normalizedPath, useSudo);
    }

    @Override
    public void rename(String connectionId, String oldPath, String newPath, boolean useSudo) {
        Session session = requireSession(connectionId);
        String nOld = normalizePath(oldPath);
        String nNew = normalizePath(newPath);
        if (useSudo) {
            execSudo(session, "mv " + shellEscape(nOld) + " " + shellEscape(nNew));
        } else {
            try {
                ChannelSftp sftp = getOrOpenSftp(connectionId, session);
                sftp.rename(nOld, nNew);
            } catch (Exception e) {
                if (isPermissionDenied(e)) {
                    log.warn("SFTP重命名权限不足，自动尝试sudo: {} -> {}", nOld, nNew);
                    execSudo(session, "mv " + shellEscape(nOld) + " " + shellEscape(nNew));
                } else {
                    throw new IllegalArgumentException("重命名失败: " + e.getMessage(), e);
                }
            }
        }
        log.info("重命名: connectionId={}, old={}, new={}, sudo={}", connectionId, nOld, nNew, useSudo);
    }

    @Override
    public void delete(String connectionId, String path, boolean useSudo) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        if (useSudo) {
            execSudo(session, "rm -rf " + shellEscape(normalizedPath));
        } else {
            try {
                ChannelSftp sftp = getOrOpenSftp(connectionId, session);
                SftpATTRS attrs = sftp.stat(normalizedPath);
                if (attrs != null && attrs.isDir()) {
                    deleteDirectory(sftp, normalizedPath);
                } else {
                    sftp.rm(normalizedPath);
                }
            } catch (Exception e) {
                if (isPermissionDenied(e)) {
                    log.warn("SFTP删除权限不足，自动尝试sudo: {}", normalizedPath);
                    execSudo(session, "rm -rf " + shellEscape(normalizedPath));
                } else {
                    throw new IllegalArgumentException("删除失败: " + e.getMessage(), e);
                }
            }
        }
        log.info("删除: connectionId={}, path={}, sudo={}", connectionId, normalizedPath, useSudo);
    }

    @Override
    public void saveFile(String connectionId, String path, String content, boolean useSudo) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (useSudo) {
            execSudoWithInput(session, "sudo -S tee " + shellEscape(normalizedPath) + " > /dev/null", contentBytes);
        } else {
            try {
                ChannelSftp sftp = getOrOpenSftp(connectionId, session);
                try (InputStream in = new ByteArrayInputStream(contentBytes)) { sftp.put(in, normalizedPath); }
            } catch (Exception e) {
                if (isPermissionDenied(e)) {
                    log.warn("SFTP保存文件权限不足，自动尝试sudo: {}", normalizedPath);
                    execSudoWithInput(session, "sudo -S tee " + shellEscape(normalizedPath) + " > /dev/null", contentBytes);
                } else {
                    throw new IllegalArgumentException("保存文件失败: " + e.getMessage(), e);
                }
            }
        }
        log.info("保存文件: connectionId={}, path={}, sudo={}", connectionId, normalizedPath, useSudo);
    }

    @Override
    public void uploadFile(String connectionId, String path, InputStream inputStream) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        try {
            ChannelSftp sftp = getOrOpenSftp(connectionId, session);
            sftp.put(inputStream, normalizedPath);
            log.info("上传文件: connectionId={}, path={}", connectionId, normalizedPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("上传文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void downloadFile(String connectionId, String path, OutputStream outputStream) {
        Session session = requireSession(connectionId);
        String normalizedPath = normalizePath(path);
        try {
            ChannelSftp sftp = getOrOpenSftp(connectionId, session);
            sftp.get(normalizedPath, outputStream);
            log.info("下载文件: connectionId={}, path={}", connectionId, normalizedPath);
        } catch (Exception e) {
            throw new IllegalArgumentException("下载文件失败: " + e.getMessage(), e);
        }
    }

    // ==================== 内部工具 ====================

    public void closeSftp(String connectionId) {
        ChannelSftp sftp = sftpChannels.remove(connectionId);
        if (sftp != null) { try { if (sftp.isConnected()) sftp.disconnect(); } catch (Exception ignored) {} }
    }

    private Session requireSession(String connectionId) {
        Session session = sshSessionPort.getSession(connectionId);
        if (session == null || !session.isConnected()) { closeSftp(connectionId); throw new IllegalStateException("SSH连接未建立"); }
        // Session 存活但 SFTP channel 可能已断开，此时清理 channel，下次 getOrOpenSftp 会重建
        ChannelSftp existing = sftpChannels.get(connectionId);
        if (existing != null && (!existing.isConnected() || existing.isClosed())) {
            closeSftp(connectionId);
        }
        return session;
    }

    private synchronized ChannelSftp getOrOpenSftp(String connectionId, Session session) {
        ChannelSftp sftp = sftpChannels.get(connectionId);
        if (sftp != null && sftp.isConnected() && !sftp.isClosed()) return sftp;
        if (sftp != null) { try { sftp.disconnect(); } catch (Exception ignored) {} sftpChannels.remove(connectionId); }
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(8000);
            sftpChannels.put(connectionId, sftp);
            log.info("SFTP channel 已重建 connectionId={}", connectionId);
            return sftp;
        } catch (Exception e) {
            throw new RuntimeException("SFTP channel 打开失败: " + e.getMessage(), e);
        }
    }

    private void execSudo(Session session, String command) {
        execSudoWithInput(session, "sudo -S " + command, null);
    }

    private void execSudoWithInput(Session session, String command, byte[] stdinData) {
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            if (stdinData != null) {
                channel.setInputStream(new ByteArrayInputStream(stdinData), false);
            }
            OutputStream out = channel.getOutputStream();
            channel.connect(8000);
            // sudo -S 从 stdin 读取密码，这里发一个空行（免密 sudo）
            out.write("\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 等待完成
            long deadline = System.currentTimeMillis() + 10000;
            while (!channel.isClosed() && System.currentTimeMillis() < deadline) { try { Thread.sleep(100); } catch (InterruptedException ignored) {} }
            if (!channel.isClosed()) { channel.disconnect(); throw new RuntimeException("sudo 命令超时"); }
            int exitCode = channel.getExitStatus();
            if (exitCode != 0) {
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                byte[] tmp = new byte[1024]; int n;
                while ((n = channel.getErrStream().read(tmp)) != -1) errBuf.write(tmp, 0, n);
                throw new RuntimeException("sudo 命令失败 (exit " + exitCode + "): " + errBuf.toString(StandardCharsets.UTF_8).trim());
            }
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("sudo 执行异常: " + e.getMessage(), e); }
        finally { if (channel != null) channel.disconnect(); }
    }

    private static byte[] readBytes(ChannelSftp sftp, String path, int maxBytes) throws Exception {
        try (InputStream in = sftp.get(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int total = 0, n;
            while ((n = in.read(buf)) != -1 && total < maxBytes) { int w = Math.min(maxBytes - total, n); out.write(buf, 0, w); total += w; }
            return out.toByteArray();
        }
    }


    /** 支持 offset + limit 的大文件分片读取 */
    private static byte[] readBytesWithOffset(ChannelSftp sftp, String path, long offset, int maxBytes) throws Exception {
        try (InputStream in = sftp.get(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (offset > 0) {
                long skipped = in.skip(offset);
                if (skipped < offset) {
                    // skip 可能在某些 SFTP 实现上不完美，保守地读掉多余字节
                    byte[] tmp = new byte[(int) Math.min(8192, offset - skipped)];
                    int r;
                    while (skipped < offset && (r = in.read(tmp)) != -1) {
                        skipped += r;
                        if (skipped >= offset) break;
                    }
                }
            }
            byte[] buf = new byte[8192]; int total = 0, n;
            while ((n = in.read(buf)) != -1 && total < maxBytes) { int w = Math.min(maxBytes - total, n); out.write(buf, 0, w); total += w; }
            return out.toByteArray();
        }
    }

    private static boolean isLikelyBinary(byte[] bytes) {
        if (bytes.length == 0) return false;
        int sample = Math.min(bytes.length, 1024), nonText = 0;
        for (int i = 0; i < sample; i++) { int b = bytes[i] & 0xff; if (b == 0) return true; if (b < 0x09 || (b > 0x0d && b < 0x20)) nonText++; }
        return nonText > sample * 0.1;
    }

    private static boolean isPermissionDenied(Exception e) {
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("permission denied");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return ROOT;
        String n = path.trim().replace("\\", "/");
        if (!n.startsWith(ROOT)) n = ROOT + n;
        while (n.contains("//")) n = n.replace("//", "/");
        if (n.length() > 1 && n.endsWith(ROOT)) n = n.substring(0, n.length() - 1);
        return n;
    }

    private static String parentPathOf(String path) {
        if (path == null || ROOT.equals(path)) return null;
        int idx = path.lastIndexOf('/');
        return idx <= 0 ? ROOT : path.substring(0, idx);
    }

    private static String fileNameOf(String path) {
        int idx = path.lastIndexOf('/');
        return (idx < 0 || idx == path.length() - 1) ? path : path.substring(idx + 1);
    }

    private static String shellEscape(String path) { return "'" + path.replace("'", "'\\''") + "'"; }

    private void deleteDirectory(ChannelSftp sftp, String path) throws SftpException {
        @SuppressWarnings("unchecked") Vector<ChannelSftp.LsEntry> list = sftp.ls(path);
        for (ChannelSftp.LsEntry entry : list) {
            String name = entry.getFilename(); if (".".equals(name) || "..".equals(name)) continue;
            String itemPath = ROOT.equals(path) ? ROOT + name : path + "/" + name;
            if (entry.getAttrs().isDir()) deleteDirectory(sftp, itemPath); else sftp.rm(itemPath);
        }
        sftp.rmdir(path);
    }
}
