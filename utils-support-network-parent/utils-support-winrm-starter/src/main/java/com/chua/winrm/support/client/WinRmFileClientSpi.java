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
 */
@Slf4j
public class WinRmFileClientSpi implements FileClient {

    @Override
    public void connect() throws IOException {
        // SPI 由框架管理生命周期，此处无需额外处理
    }

    @Override
    public void closeQuietly() {
        // no-op
    }

    @Override
    public List<String> listFiles(String path) throws IOException {
        return new ArrayList<>();
    }

    @Override
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        // WinRM 文件上传暂未实现
    }

    @Override
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        // WinRM 文件下载暂未实现
    }

    @Override
    public String readFile(String path) throws IOException {
        return null;
    }

    @Override
    public void createDirectory(String path, boolean recursive) throws IOException {
        // 暂未实现
    }

    @Override
    public void delete(String path) throws IOException {
        // 暂未实现
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        // 暂未实现
    }

    @Override
    public boolean exists(String path) throws IOException {
        return false;
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        return false;
    }
}
