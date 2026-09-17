package com.chua.common.support.network.protocol.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
* 文件客户端接口，统一处理不同协议下的文件操作。
*
* @author CH
* @since 2026/07/27
 */
public interface FileClient {

    /**
    * 连接文件服务。
    *
    * @throws IOException 连接失败时抛出
    */
    void connect() throws IOException;

    /**
    * 快速关闭资源（不抛异常）。
    */
    void closeQuietly();

    /**
    * 列出目录中的文件列表。
    *
    * @param path 目录路径
    * @return 文件名列表
    * @throws IOException 列出失败时抛出
    */
    List<String> listFiles(String path) throws IOException;

    /**
    * 上传文件到远程位置。
    *
    * @param inputStream 文件输入流
    * @param path      远程目标路径
    * @throws IOException 上传失败时抛出
    */
    void uploadFile(InputStream inputStream, String path) throws IOException;

    /**
    * 从远程位置下载文件。
    *
    * @param path 远程源路径
    * @param outputStream 文件输出流
    * @throws IOException 下载失败时抛出
    */
    void downloadFile(String path, OutputStream outputStream) throws IOException;

    /**
    * 读取远程文件内容。
    *
    * @param path 文件路径
    * @return 文件内容字符串
    * @throws IOException 读取失败时抛出
    */
    String readFile(String path) throws IOException;

    /**
    * 创建远程目录。
    *
    * @param path     目录路径
    * @param recursive 是否递归创建父目录
    * @throws IOException 创建失败时抛出
    */
    void createDirectory(String path, boolean recursive) throws IOException;

    /**
    * 删除远程文件或目录。
    *
    * @param path 路径
    * @throws IOException 删除失败时抛出
    */
    void delete(String path) throws IOException;

    /**
    * 重命名或移动远程文件/目录。
    *
    * @param oldPath 原路径
    * @param newPath 新路径
    * @throws IOException 重命名失败时抛出
    */
    void rename(String oldPath, String newPath) throws IOException;

    /**
    * 检查路径是否存在。
    *
    * @param path 路径
    * @return 如果存在返回 true
    * @throws IOException 检查失败时抛出
    */
    boolean exists(String path) throws IOException;

    /**
    * 检查路径是否为目录。
    *
    * @param path 路径
    * @return 如果是目录返回 true
    * @throws IOException 检查失败时抛出
    */
    boolean isDirectory(String path) throws IOException;
}
