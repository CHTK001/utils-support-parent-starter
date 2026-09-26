package com.chua.winrm.support.client;

import com.chua.common.support.network.protocol.ClientSetting;
import com.chua.common.support.network.protocol.client.FileClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * winrm 文件客户端，实现 winrm 协议下的文件操作。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WinRmFileClient implements FileClient {

    private final WinRmExecClient winrmClient;

    public WinRmFileClient(ClientSetting setting) {
        this(WinRmExecClient.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .username(setting.getUsername())
                .password(setting.getPassword())
                .connectTimeout((int) Math.max(1, setting.getConnectTimeout() / 1000))
                .sessionTimeout((int) Math.max(1, setting.getReadTimeout() / 1000))
                .build());
    }

    public WinRmFileClient(WinRmExecClient execClient) {
        this.winrmClient = execClient;
    }

    @Override
    public void connect() throws IOException {
        try {
            winrmClient.connect();
        } catch (Exception e) {
            throw ioFailure("连接 WinRM 失败", e);
        }
    }

    @Override
    public void closeQuietly() {
        winrmClient.disconnect();
    }

    @Override
    public List<String> listFiles(String path) throws IOException {
        String output = executePowerShell("Get-ChildItem -LiteralPath " + quote(path)
                + " -ErrorAction Stop | Select-Object -ExpandProperty Name");
        List<String> result = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                result.add(line.trim());
            }
        }
        return result;
    }

    @Override
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        if (inputStream == null) {
            throw new IOException("上传输入流不能为空");
        }
        String encoded = Base64.getEncoder().encodeToString(inputStream.readAllBytes());
        executePowerShell("[System.IO.File]::WriteAllBytes(" + quote(path)
                + ", [System.Convert]::FromBase64String(" + quote(encoded) + "))");
        log.info("文件上传成功: {}", path);
    }

    @Override
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        if (outputStream == null) {
            throw new IOException("下载输出流不能为空");
        }
        String output = executePowerShell("[System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes("
                + quote(path) + "))");
        try {
            outputStream.write(Base64.getDecoder().decode(output.trim()));
        } catch (IllegalArgumentException e) {
            throw new IOException("远端文件不是有效的 Base64 响应: " + path, e);
        }
        log.info("文件下载成功: {}", path);
    }

    @Override
    public String readFile(String path) throws IOException {
        return executePowerShell("Get-Content -LiteralPath " + quote(path)
                + " -Raw -ErrorAction Stop");
    }

    @Override
    public void createDirectory(String path, boolean recursive) throws IOException {
        String force = recursive ? " -Force" : "";
        executePowerShell("New-Item -ItemType Directory -Path " + quote(path) + force
                + " -ErrorAction Stop | Out-Null");
        log.info("目录创建成功: {}", path);
    }

    @Override
    public void delete(String path) throws IOException {
        executePowerShell("Remove-Item -LiteralPath " + quote(path)
                + " -Force -Recurse -ErrorAction Stop");
        log.info("删除成功: {}", path);
    }

    @Override
    public void rename(String oldPath, String newPath) throws IOException {
        executePowerShell("Move-Item -LiteralPath " + quote(oldPath)
                + " -Destination " + quote(newPath) + " -ErrorAction Stop");
        log.info("重命名成功: {} -> {}", oldPath, newPath);
    }

    @Override
    public boolean exists(String path) throws IOException {
        return Boolean.parseBoolean(executePowerShell("Test-Path -LiteralPath " + quote(path)
                + " -ErrorAction Stop").trim());
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        return Boolean.parseBoolean(executePowerShell("(Test-Path -LiteralPath " + quote(path)
                + " -PathType Container -ErrorAction Stop)").trim());
    }

    private String executePowerShell(String script) throws IOException {
        requirePath(script);
        byte[] encoded = Base64.getEncoder().encode(script.getBytes(StandardCharsets.UTF_16LE));
        String command = "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand "
                + new String(encoded, StandardCharsets.US_ASCII);
        try {
            WinRmExecClient.ExecResult result = winrmClient.exec().command(command).execute();
            if (result.exitCode() != 0) {
                String error = result.stderr() == null || result.stderr().isBlank()
                        ? result.stdout() : result.stderr();
                throw new IOException("WinRM PowerShell 执行失败，exitCode=" + result.exitCode()
                        + ", error=" + error);
            }
            return result.stdout() == null ? "" : result.stdout();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw ioFailure("WinRM PowerShell 执行失败", e);
        }
    }

    private static String quote(String path) throws IOException {
        requirePath(path);
        return "'" + path.replace("'", "''") + "'";
    }

    private static void requirePath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IOException("远程路径不能为空");
        }
    }

    private static IOException ioFailure(String message, Exception cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message + ": " + cause.getMessage(), cause);
    }
}
