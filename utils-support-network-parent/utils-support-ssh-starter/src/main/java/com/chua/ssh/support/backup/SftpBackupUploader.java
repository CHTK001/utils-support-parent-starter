package com.chua.ssh.support.backup;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.task.backup.BackupRemoteConfig;
import com.chua.common.support.task.backup.BackupUploader;
import com.chua.ssh.support.client.SftpClient;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * 基于 SFTP 的备份文件上传器（{@link BackupUploader} SPI 实现，type = "sftp"）
 *
 * <h3>模块依赖说明</h3>
 * <p>本类属于 <b>utils-support-ssh-starter</b> 模块。common-starter 的备份能力
 * 通过 {@code META-INF/extensions} 发现本实现（{@code BackupUploader} SPI），
 * 宿主仅在需要「备份文件 SFTP 远端上传」时才需依赖本模块；
 * 未引入本模块时备份流程跳过远端上传，本地备份不受影响。
 *
 * @author CH
 * @since 2026/11/24
 */
@Slf4j
@Spi(BackupRemoteConfig.PROTOCOL_SFTP)
public class SftpBackupUploader implements BackupUploader {

    /**
     * 类型
     */
    private static final String TYPE = BackupRemoteConfig.PROTOCOL_SFTP;

    /**
     * 协议类型标识：sftp。
     */
    @Override
    public String type() {
        return TYPE;
    }

    /**
     * 通过 SFTP 上传单个本地文件到远端目录。
     */
    @Override
    public void upload(Path localFile, BackupRemoteConfig remoteConfig) {
        String remotePath = buildRemotePath(remoteConfig, localFile);
        log.info("[backup] SFTP 上传开始: {} → {}@{}:{}",
                localFile, remoteConfig.getUsername(), remoteConfig.getHost(), remotePath);
        try (SftpClient client = SftpClient.builder()
                .host(remoteConfig.getHost())
                .port(remoteConfig.getPort())
                .username(remoteConfig.getUsername())
                .password(remoteConfig.getPassword())
                .privateKey(remoteConfig.getPrivateKeyPath())
                .build()
                .connect()) {
            String remoteDir = remoteConfig.getRemoteDir();
            if (remoteDir != null && !remoteDir.isBlank()) {
                client.mkdir().path(remoteDir).recursive(true).exec();
            }
            client.upload().local(localFile.toString()).remote(remotePath).exec();
        } catch (Exception e) {
            throw new IllegalStateException("SFTP 上传失败: " + localFile + " → " + remotePath
                    + " - " + e.getMessage(), e);
        }
        log.info("[backup] SFTP 上传完成: {}", remotePath);
    }

    /**
     * 拼接远端完整路径（目录 + 文件名，统一 / 分隔）。
     */
    private String buildRemotePath(BackupRemoteConfig remoteConfig, Path localFile) {
        String dir = remoteConfig.getRemoteDir();
        String fileName = localFile.getFileName().toString();
        if (dir == null || dir.isBlank()) {
            return fileName;
        }
        return dir.endsWith("/") ? dir + fileName : dir + "/" + fileName;
    }
}
