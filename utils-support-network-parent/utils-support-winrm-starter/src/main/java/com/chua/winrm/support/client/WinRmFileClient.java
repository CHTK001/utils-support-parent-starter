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
* winrm 文件客户端，实现 winrm 协议下的文件操作。
*
* <p>注意：WinRM 本身不直接提供文件传输功能，本实现通过 PowerShell 命令模拟文件操作。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class WinRmFileClient implements FileClient {

    /**
    * PowerShell 布尔真值字符串
     */
    private static final String POWERSHELL_TRUE = "True";

    /**
    * 换行符，用于分割命令输出
     */
    private static final String NEWLINE = "\n";

    /**
    * PowerShell 递归创建目录的强制参数
     */
    private static final String FORCE_FLAG = " -Force";

    /**
    * winrm 命令执行客户端
     */
    private final WinRmExecClient winrmClient;

    /**
    * 通过客户端设置构造 winrm 文件客户端。
    *
    * @param setting 客户端连接配置
     */
    public WinRmFileClient(com.chua.common.support.network.protocol.ClientSetting setting) {
        this.winrmClient = WinRmExecClient.builder()
                .host(setting.getHost())
                .port(setting.getPort())
                .username(setting.getUsername())
                .password(setting.getPassword())
                .build();
    }

    /**
    * 复用已连接的 winrm 命令客户端构造文件客户端，避免重复认证配置。
    *
    * @param execClient 已建立连接的 winrm 命令客户端
     */
    public WinRmFileClient(WinRmExecClient execClient) {
        this.winrmClient = execClient;
    }

    @Override
    /** 连接 */
    public void connect() throws IOException {
        winrmClient.connect();
    }

    @Override
    /** 关闭Quietly */
    public void closeQuietly() {
        try {
            winrmClient.disconnect();
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }

    @Override
    /** 列表文件 */
    public List<String> listFiles(String path) throws IOException {
        String command = "Get-ChildItem -Path \"" + path + "\" | Select-Object -ExpandProperty Name";
        String output = winrmClient.exec().command(command).executeAndGetOutput();
        List<String> result = new ArrayList<>();
        for (String line : output.split(NEWLINE)) {
            if (!line.trim().isEmpty()) {
                result.add(line.trim());
            }
        }
        return result;
    }

    @Override
    /** upload文件 */
    public void uploadFile(InputStream inputStream, String path) throws IOException {
        byte[] data = inputStream.readAllBytes();
        String encoded = Base64.getEncoder().encodeToString(data);
        String command = "powershell \"[System.IO.File]::WriteAllBytes('"
                + path + "', [System.Convert]::FromBase64String('" + encoded + "'))\"";
        winrmClient.exec().command(command).execute();
        log.info("文件上传成功: {}", path);
    }

    @Override
    /** download文件 */
    public void downloadFile(String path, OutputStream outputStream) throws IOException {
        String command = "powershell \"[Convert]::ToBase64String([IO.File]::ReadAllBytes('" + path + "'))\"";
        String output = winrmClient.exec().command(command).executeAndGetOutput();
        byte[] data = Base64.getDecoder().decode(output.trim());
        outputStream.write(data);
        log.info("文件下载成功: {}", path);
    }

    @Override
    /** 读取文件 */
    public String readFile(String path) throws IOException {
        String command = "Get-Content -Path \"" + path + "\"";
        return winrmClient.exec().command(command).executeAndGetOutput();
    }

    @Override
    /** 创建目录 */
    public void createDirectory(String path, boolean recursive) throws IOException {
        String forceFlag = recursive ? FORCE_FLAG : "";
        String command = "New-Item -ItemType Directory -Path \"" + path + "\"" + forceFlag;
        winrmClient.exec().command(command).execute();
        log.info("目录创建成功: {}", path);
    }

    @Override
    /** 删除 */
    public void delete(String path) throws IOException {
        String command = "Remove-Item -Path \"" + path + "\" -Force -Recurse";
        winrmClient.exec().command(command).execute();
        log.info("删除成功: {}", path);
    }

    @Override
    /** 重命名 */
    public void rename(String oldPath, String newPath) throws IOException {
        String command = "Move-Item -Path \"" + oldPath + "\" -Destination \"" + newPath + "\"";
        winrmClient.exec().command(command).execute();
        log.info("重命名成功: {} -> {}", oldPath, newPath);
    }

    @Override
    /** 是否存在 */
    public boolean exists(String path) throws IOException {
        String command = "Test-Path -Path \"" + path + "\"";
        String result = winrmClient.exec().command(command).executeAndGetOutput();
        return result.contains(POWERSHELL_TRUE);
    }

    @Override
    /** 是否目录 */
    public boolean isDirectory(String path) throws IOException {
        String command = "(Get-Item -Path \"" + path + "\").PSIsContainer";
        String result = winrmClient.exec().command(command).executeAndGetOutput();
        return result.contains(POWERSHELL_TRUE);
    }
}
