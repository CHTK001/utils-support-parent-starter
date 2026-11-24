package com.chua.common.support.task.backup;

import java.nio.file.Path;

/**
 * 备份文件远端上传 SPI
 *
 * <p>备份完成后，将本地备份文件上传到远端服务器。通过 SPI 机制自动发现实现类，
 * 每个实现类通过 {@link #type()} 标识支持的上传协议。
 *
 * <h3>内置与扩展实现</h3>
 * <ul>
 *   <li>"sftp" — 由 <b>utils-support-ssh-starter</b> 模块提供
 *       （{@code com.chua.ssh.support.backup.SftpBackupUploader}）</li>
 * </ul>
 *
 * <h3>模块依赖说明</h3>
 * <p>本 SPI 定义在 common-starter，不编译期依赖任何 SSH 实现；
 * 宿主仅在实际配置远端上传时才需引入对应协议模块（SFTP → utils-support-ssh-starter）。
 * 未引入时 {@code ServiceProvider.of(BackupUploader.class).getExtension("sftp")}
 * 返回空，备份流程跳过远端上传，本地备份不受影响。
 *
 * @author CH
 * @since 2026/11/24
 */
public interface BackupUploader {

    /**
     * 获取上传协议类型标识
     *
     * @return 协议类型（如 sftp）
     */
    String type();

    /**
     * 上传单个本地文件到远端
     *
     * @param localFile    本地待上传文件
     * @param remoteConfig 远端目标配置
     */
    void upload(Path localFile, BackupRemoteConfig remoteConfig);
}
