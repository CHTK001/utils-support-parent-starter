package com.chua.ftp.support.directory;

import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FTP 目录轮询实现，基于 Apache Commons Net。
 * <p>
 * 通过 {@link DiffPolledDirectory} 快照对比机制，定时对比远程 FTP 目录文件变更。
 * </p>
 * <p>
 * 配合 {@link DirectoryPollerEnvironment} 配置连接参数，由虚拟线程执行器驱动轮询。
 * </p>
 * 环境配置属性：
 * <ul>
 *   <li>{@code host} — FTP 服务器地址</li>
 *   <li>{@code port} — FTP 端口（默认 21）</li>
 *   <li>{@code username} — 登录用户名（默认 anonymous）</li>
 *   <li>{@code password} — 登录密码（默认空）</li>
 *   <li>{@code passiveMode} — 是否使用被动模式（默认 true）</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FtpPolledDirectory extends DiffPolledDirectory<FTPFile> {

    /**
     * FTP 服务器主机地址
     */
    private final String host;
    /**
     * FTP 服务器端口号
     */
    private final int port;
    /**
     * 登录用户名
     */
    private final String username;
    /**
     * 登录密码
     */
    private final String password;
    /**
     * 是否使用被动模式
     */
    private final boolean passiveMode;

    /**
     * Apache Commons Net FTP 客户端实例
     */
    private FTPClient client;

    /**
     * 构造 FTP 目录轮询器。
     *
     * @param listenPath  FTP 远程目录路径
     * @param environment 环境配置（需包含 host、port 等）
     */
    public FtpPolledDirectory(String listenPath, DirectoryPollerEnvironment environment) {
        super(listenPath);
        this.host = environment.getProperty("host");
        this.port = Integer.parseInt(environment.getProperty("port", "21"));
        this.username = environment.getProperty("username", "anonymous");
        this.password = environment.getProperty("password", "");
        this.passiveMode = Boolean.parseBoolean(environment.getProperty("passiveMode", "true"));
    }

    @Override
    public void start(DirectoryPollerEnvironment environment,
                      com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor executor) {
        client = new FTPClient();
        try {
            client.connect(host, port);
            client.login(username, password);
            if (passiveMode) {
                client.enterLocalPassiveMode();
            }
            log.info("FTP 连接成功: {}@{}:{}", username, host, port);
        } catch (IOException e) {
            throw new RuntimeException("FTP 连接失败: " + host + ":" + port, e);
        }

        super.start(environment, executor);
    }

    @Override
    protected List<FTPFile> listAndModified(String path) {
        try {
            FTPFile[] files = client.listFiles(path);
            return files != null ? Arrays.asList(files) : new ArrayList<>();
        } catch (IOException e) {
            log.error("FTP 读取目录失败: {}", path, e);
            return new ArrayList<>();
        }
    }

    @Override
    protected String getFileName(FTPFile item) {
        return item.getName();
    }

    @Override
    protected Long getModified(FTPFile item) {
        long t = item.getTimestamp() != null ? item.getTimestamp().getTimeInMillis() : 0L;
        return t;
    }

    @Override
    public void close() {
        super.close();
        if (client != null && client.isConnected()) {
            try {
                client.logout();
                client.disconnect();
            } catch (IOException ignored) {
            }
        }
    }
}
