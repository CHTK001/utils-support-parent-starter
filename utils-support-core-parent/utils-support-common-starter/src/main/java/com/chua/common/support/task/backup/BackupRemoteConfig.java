package com.chua.common.support.task.backup;

import lombok.Builder;
import lombok.Data;

/**
 * 备份远端上传配置
 *
 * <p>描述将备份文件上传到远端服务器所需的目标信息。
 *
 * <h3>模块依赖说明</h3>
 * <p>本类仅是 common-starter 内的配置载体，不编译期依赖任何网络模块。
 * 当 {@code protocol = sftp} 时，上传能力由 <b>utils-support-ssh-starter</b>
 * 模块提供（SPI 实现：{@code com.chua.ssh.support.backup.SftpBackupUploader}）；
 * 宿主仅在需要 SFTP 远端上传时才需引入该模块，未引入时上传步骤自动跳过。
 *
 * @author CH
 * @since 2026/11/24
 */
@Data
@Builder
public class BackupRemoteConfig {

    /**
     * 上传协议：SFTP（依赖 utils-support-ssh-starter 模块）
     */
    public static final String PROTOCOL_SFTP = "sftp";

    /**
     * 上传协议类型，当前支持 sftp
     */
    @Builder.Default
    private String protocol = PROTOCOL_SFTP;

    /**
     * 远端主机地址
     */
    private String host;

    /**
     * SSH 端口，默认 22
     */
    @Builder.Default
    private int port = 22;

    /**
     * 登录用户名
     */
    private String username;

    /**
     * 登录密码（与 {@link #privateKeyPath} 二选一）
     */
    private String password;

    /**
     * 私钥文件路径（与 {@link #password} 二选一）
     */
    private String privateKeyPath;

    /**
     * 远端目标目录（不存在时自动递归创建；为空时上传到登录默认目录）
     */
    private String remoteDir;
}
