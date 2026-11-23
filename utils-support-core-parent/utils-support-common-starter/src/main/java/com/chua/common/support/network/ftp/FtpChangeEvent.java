package com.chua.common.support.network.ftp;

import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * FTP 文件变更事件。
 *
 * <p>服务端在文件/目录发生增删改、移动等操作成功后产生，通过两条通道对外发布：</p>
 * <ul>
 *   <li>进程内监听器：{@link FtpServer#addChangeListener(FtpChangeListener)} 注册，
 *       同步回调 {@link FtpChangeListener#onChange(FtpChangeEvent)}；</li>
 *   <li>控制连接推送：开启 {@code NOTI ON} 的会话会收到
 *       {@code NOTIFY <type> <path> [<from>]} 文本行。</li>
 * </ul>
 *
 * <p>事件中的 {@link #path} 与 {@link #previousPath} 均为相对于 FTP 根目录的路径
 * （以 {@code /} 开头、正斜杠分隔），与 FTP 客户端视角一致。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Getter
@ToString
public class FtpChangeEvent {

    /**
     * 变更类型
     */
    private final EventType type;

    /**
     * 变更后的 FTP 路径（MOVED 事件为目标路径）
     */
    private final String path;

    /**
     * 变更前路径（仅 MOVED 事件非空，其余为 null）
     */
    private final String previousPath;

    /**
     * 事件发生时间
     */
    private final LocalDateTime timestamp;

    /**
     * 触发变更的已认证用户名（匿名时为 anonymous）
     */
    private final String username;

    /**
     * 创建文件变更事件。
     *
     * @param type         变更类型
     * @param path         变更后的 FTP 路径
     * @param previousPath 变更前路径（仅 MOVED 事件）
     * @param username     触发用户名
     */
    public FtpChangeEvent(EventType type, String path, String previousPath, String username) {
        this.type = type;
        this.path = path;
        this.previousPath = previousPath;
        this.timestamp = LocalDateTime.now();
        this.username = username;
    }

    /**
     * 变更类型。
     */
    public enum EventType {

        /**
         * 新建文件（STOR 覆盖不存在的目标）
         */
        FILE_CREATED,

        /**
         * 修改文件（STOR 覆盖已存在的目标）
         */
        FILE_MODIFIED,

        /**
         * 删除文件（DELE）
         */
        FILE_DELETED,

        /**
         * 创建目录（MKD）
         */
        DIRECTORY_CREATED,

        /**
         * 删除目录（RMD）
         */
        DIRECTORY_DELETED,

        /**
         * 移动/重命名（RNFR + RNTO）
         */
        MOVED
    }
}
