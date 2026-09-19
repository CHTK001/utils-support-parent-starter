package com.chua.maven.support;

import java.util.List;

/**
 * Maven 部署目标接口。
 * <p>
 * 定义部署的抽象目标，默认提供本地文件系统实现。
 * 通过 SPI 或直接实例化可扩展为其他部署方式（SSH、最小io、Nexus 等）。
 * </p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>定义统一的部署契约：启动 → 传输文件 → 结束</li>
 *   <li>支持进度回调</li>
 *   <li>支持可断开资源管理</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MavenDeployTarget extends AutoCloseable {

    /**
     * 获取目标名称
     *
     * @return 目标名称
     */
    String name();

    /**
     * 是否已连接（或可操作状态）
     *
     * @return true 目标可用
     */
    boolean isReady();

    /**
     * 初始化部署目标（如建立连接）
     */
    void connect();

    /**
     * 上传单个文件到目标
     *
     * @param localPath  本地文件路径
     * @param targetPath 目标路径
     */
    void upload(String localPath, String targetPath);

    /**
     * 批量上传文件
     *
     * @param files     上传文件列表（本地路径，目标保持原文件名）
     * @param targetDir 目标目录
     * @return 上传成功数
     */
    default int uploadBatch(List<String> files, String targetDir) {
        int count = 0;
        String dir = targetDir;
        if (!dir.endsWith("/") && !dir.endsWith("\\")) {
            dir += "/";
        }
        for (String file : files) {
            java.io.File f = new java.io.File(file);
            String remotePath = dir + f.getName();
            upload(file, remotePath);
            count++;
        }
        return count;
    }

    /**
     * 创建目录
     *
     * @param path 目录路径
     */
    void createDirectory(String path);

    /**
     * 检查目标路径是否存在
     *
     * @param path 文件路径
     * @return true 存在
     */
    boolean exists(String path);

    /**
     * 删除文件
     *
     * @param path 文件路径
     */
    void delete(String path);

    /**
     * 断开连接
     */
    void disconnect();

    @Override
    /** 关闭 */
    default void close() {
        disconnect();
    }

    /**
    * 部署进度回调（可选实现）
    *
    * @param callback 回调
    */
    default void setCallback(MavenDeployCallback callback) {
    }
}
