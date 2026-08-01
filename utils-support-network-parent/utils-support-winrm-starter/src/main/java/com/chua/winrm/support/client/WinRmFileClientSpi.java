package com.chua.winrm.support.client;

import com.chua.common.support.network.protocol.client.FileClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * WinRM 文件客户端 SPI 实现。
 * <p>
 * 当前为占位实现，所有 IO 操作返回空 / false。实际 WinRM 文件传输需通过 wsman shell 命令或 SMT/HTTP 通道扩展实现。
 * 连接生命周期由 SPI 框架管理，{@link #connect()} 与 {@link #closeQuietly()} 均无操作。
 * </p>
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class WinRmFileClientSpi implements FileClient {

    /**
     * 建立连接（占位，SPI 框架管理生命周期）。
     */
    @Override
    public void connect() throws IOException {
        // SPI 由框架管理生命周期，此处无需额外处理
    }

    /**
     * 静默关闭连接（占位实现）。
     */
    @Override
    public void closeQuietly() {
        // no-op
    }

    /**
     * 列出指定路径下的文件（占位实现，返回空列表）。
     *
     * @param path 远端路径
     * @return 文件名列表，当前始终为空
     */
    @Override
    public List<String> listFiles(String path) throws IOException {
        return new ArrayList<>();
    }

    /**
     * 上传文件到远端（暂未实现）。
     *
     * @param inputStream 源输入流
     * @param path        远端目标路径
     */
    @Override
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        // WinRM 文件上传暂未实现
    }

    /**
     * 下载远端文件到输出流（暂未实现）。
     *
     * @param path         远端源路径
     * @param outputStream 本地输出流
     */
    @Override
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        // WinRM 文件下载暂未实现
    }

    /**
     * 读取远端文件全部内容（暂未实现）。
     *
     * @param path 远端文件路径
     * @return 当前返回 null
     */
    @Override
    public String readFile(String path) throws IOException {
        return null;
    }

    /**
     * 在远端创建目录（暂未实现）。
     *
     * @param path      远端目录路径
     * @param recursive 是否递归创建
     */
    @Override
    public void createDirectory(String path, boolean recursive) throws IOException {
        // 暂未实现
    }

    /**
     * 删除远端文件或目录（暂未实现）。
     *
     * @param path 远端路径
     */
    @Override
    public void delete(String path) throws IOException {
        // 暂未实现
    }

    /**
     * 重命名远端路径（暂未实现）。
     *
     * @param oldPath 远端原路径
     * @param newPath 远端新路径
     */
    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        // 暂未实现
    }

    /**
     * 判断远端路径是否存在（占位实现，返回 false）。
     *
     * @param path 远端路径
     * @return true 表示存在
     */
    @Override
    public boolean exists(String path) throws IOException {
        return false;
    }

    /**
     * 判断远端路径是否为目录（占位实现，返回 false）。
     *
     * @param path 远端路径
     * @return true 表示是目录
     */
    @Override
    public boolean isDirectory(String path) throws IOException {
        return false;
    }
}
