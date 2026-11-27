package com.chua.ssh.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.FileClient;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SFTP 文件客户端 SPI 适配器。
 *
 * <p>{@link SftpClient} 保留链式 API；本适配器负责把通用 {@link FileClient}
 * 契约转换为该链式客户端，并消费通用客户端配置中的超时。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public class SftpFileClientSpi implements FileClient {

    private final SftpClient delegate;

    /**
     * 使用通用客户端配置创建 SFTP 文件客户端。
     *
     * @param setting 客户端配置
     */
    public SftpFileClientSpi(ClientSetting setting) {
        if (setting == null) {
            throw new IllegalArgumentException("SFTP ClientSetting 不能为空");
        }
        this.delegate = SftpClient.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .username(setting.getUsername())
                .password(setting.getPassword())
                .connectTimeout(toSeconds(setting.getConnectTimeout()))
                .authTimeout(toSeconds(setting.getReadTimeout()))
                .build();
    }

    @Override
    public void connect() throws IOException {
        try {
            delegate.connect();
        } catch (Exception e) {
            throw ioFailure("SFTP 连接失败", e);
        }
    }

    @Override
    public void closeQuietly() {
        try {
            delegate.close();
        } catch (Exception ignored) {
            // 文件客户端契约要求关闭操作不抛异常。
        }
    }

    @Override
    public List<String> listFiles(String path) throws IOException {
        try {
            List<String> result = new ArrayList<>();
            for (java.util.Map<String, Object> entry : delegate.ls().path(path).exec()) {
                result.add(String.valueOf(entry.get("name")));
            }
            return result;
        } catch (Exception e) {
            throw ioFailure("SFTP 列出目录失败", e);
        }
    }

    @Override
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        if (inputStream == null) {
            throw new IOException("上传输入流不能为空");
        }
        try (var handle = delegate.getSftp().open(path,
                org.apache.sshd.sftp.client.SftpClient.OpenMode.Create,
                org.apache.sshd.sftp.client.SftpClient.OpenMode.Write,
                org.apache.sshd.sftp.client.SftpClient.OpenMode.Truncate)) {
            byte[] buffer = new byte[8192];
            long offset = 0;
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                if (length == 0) {
                    continue;
                }
                delegate.getSftp().write(handle, offset, buffer, 0, length);
                offset += length;
            }
        } catch (Exception e) {
            throw ioFailure("SFTP 上传失败", e);
        }
    }

    @Override
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        if (outputStream == null) {
            throw new IOException("下载输出流不能为空");
        }
        try (var handle = delegate.getSftp().open(path,
                org.apache.sshd.sftp.client.SftpClient.OpenMode.Read)) {
            byte[] buffer = new byte[8192];
            long offset = 0;
            int length;
            while ((length = delegate.getSftp().read(handle, offset, buffer)) > 0) {
                outputStream.write(buffer, 0, length);
                offset += length;
            }
        } catch (Exception e) {
            throw ioFailure("SFTP 下载失败", e);
        }
    }

    @Override
    public String readFile(String path) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        downloadFile(path, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void createDirectory(String path, boolean recursive) throws IOException {
        try {
            delegate.mkdir().path(path).recursive(recursive).exec();
        } catch (Exception e) {
            throw ioFailure("SFTP 创建目录失败", e);
        }
    }

    @Override
    public void delete(String path) throws IOException {
        try {
            deletePath(path);
        } catch (Exception e) {
            throw ioFailure("SFTP 删除失败", e);
        }
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        try {
            delegate.rename().from(oldPath).to(newPath).exec();
        } catch (Exception e) {
            throw ioFailure("SFTP 重命名失败", e);
        }
    }

    @Override
    public boolean exists(String path) throws IOException {
        try {
            delegate.getSftp().stat(path);
            return true;
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE
                    || e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_PATH) {
                return false;
            }
            throw e;
        } catch (Exception e) {
            throw ioFailure("SFTP 检查路径失败", e);
        }
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        try {
            return delegate.getSftp().stat(path).isDirectory();
        } catch (Exception e) {
            throw ioFailure("SFTP 检查目录失败", e);
        }
    }

    private static int toSeconds(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return 1;
        }
        long seconds = ((timeoutMillis - 1L) / 1000L) + 1L;
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    private void deletePath(String path) throws IOException {
        var attributes = delegate.getSftp().lstat(path);
        if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
            delegate.rm().path(path).exec();
            return;
        }
        for (var entry : delegate.getSftp().readDir(path)) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            deletePath(childPath);
        }
        delegate.rm().path(path).recursive(true).exec();
    }

    private static IOException ioFailure(String message, Exception cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message + ": " + cause.getMessage(), cause);
    }
}
