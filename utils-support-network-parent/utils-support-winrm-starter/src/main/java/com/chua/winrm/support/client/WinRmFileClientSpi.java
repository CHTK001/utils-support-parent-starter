package com.chua.winrm.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.FileClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * winrm 文件客户端 SPI 实现。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WinRmFileClientSpi implements FileClient {

    private final WinRmFileClient delegate;

    public WinRmFileClientSpi(ClientSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("WinRM ClientSetting 不能为空");
        }
        this.delegate = new WinRmFileClient(setting);
    }

    @Override
    public void connect() throws IOException {
        delegate.connect();
    }

    @Override
    public void closeQuietly() {
        delegate.closeQuietly();
    }

    @Override
    public List<String> listFiles(String path) throws IOException {
        return delegate.listFiles(path);
    }

    @Override
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        delegate.uploadFile(inputStream, path);
    }

    @Override
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        delegate.downloadFile(path, outputStream);
    }

    @Override
    public String readFile(String path) throws IOException {
        return delegate.readFile(path);
    }

    @Override
    public void createDirectory(String path, boolean recursive) throws IOException {
        delegate.createDirectory(path, recursive);
    }

    @Override
    public void delete(String path) throws IOException {
        delegate.delete(path);
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        delegate.rename(oldPath, newPath);
    }

    @Override
    public boolean exists(String path) throws IOException {
        return delegate.exists(path);
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        return delegate.isDirectory(path);
    }
}
