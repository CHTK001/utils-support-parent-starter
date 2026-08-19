package com.chua.ssh.support.directory;

import com.chua.common.support.lang.directory.DiffPolledDirectory;
import com.chua.common.support.lang.directory.PolledListener;
import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SFTP 目录轮询实现，基于 Apache MINA SSHD。
 * <p>
 * 通过 {@link DiffPolledDirectory} 快照对比机制，定时对比远程 SFTP 目录文件变更。
 * </p>
 * <p>
 * 配合 {@link DirectoryPollerEnvironment} 配置连接参数，{@link VirtualThreadPollerExecutor} 驱动虚拟线程轮询。
 * </p>
 * 环境配置属性：
 * <ul>
 *   <li>{@code host} — SFTP 服务器地址</li>
 *   <li>{@code port} — SFTP 端口（默认 22）</li>
 *   <li>{@code username} — 登录用户名</li>
 *   <li>{@code password} — 登录密码</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SftpPolledDirectory extends DiffPolledDirectory<SftpClient.DirEntry> {

    /**
     * 日志实例
     */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SftpPolledDirectory.class);

    /**
     * 主机地址
     */
    private final String host;
    /**
     * 端口号
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
     * 客户端实例
     */
    private SshClient client;
    /**
     * 会话对象
     */
    private ClientSession session;
    /**
     * sftp
     */
    private SftpClient sftp;

    /**
     * 构造 SFTP 目录轮询器。
     *
     * @param listenPath SFTP 远程目录路径
     * @param environment 环境配置（需包含 host、port、username、password）
     */
    public SftpPolledDirectory(String listenPath, DirectoryPollerEnvironment environment) {
        super(listenPath);
        this.host = environment.getProperty("host");
        this.port = Integer.parseInt(environment.getProperty("port", "22"));
        this.username = environment.getProperty("username");
        this.password = environment.getProperty("password");
    }

    @Override
    /**
     * 开始
     * @param environment environment
     * @param executor executor
     */
    public void start(DirectoryPollerEnvironment environment,
                      com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor executor) {
        try {
            client = SshClient.setUpDefaultClient();
            client.start();

            session = client.connect(username, host, port)
                    .verify(10000)
                    .getSession();
            session.addPasswordIdentity(password);
            session.auth().verify(10000);

            sftp = SftpClientFactory.instance().createSftpClient(session);
            log.info("SFTP 连接成功: {}@{}:{}", username, host, port);
        } catch (IOException e) {
            throw new RuntimeException("SFTP 连接失败: " + host + ":" + port, e);
        }

        super.start(environment, executor);
    }

    @Override
    /** ListAndModified */
    protected List<SftpClient.DirEntry> listAndModified(String path) {
        try {
            Iterable<SftpClient.DirEntry> dirEntries = sftp.readDir(path);
            List<SftpClient.DirEntry> dirEntryList = new ArrayList<>();
            dirEntries.iterator()
                    .forEachRemaining(dirEntryList::add);
            return dirEntryList;
        } catch (IOException e) {
            log.error("SFTP 读取目录失败: {}", path, e);
            return List.of();
        }
    }

    @Override
    /** 获取FileName */
    protected String getFileName(SftpClient.DirEntry item) {
        return item.getFilename();
    }

    @Override
    /** 获取Modified */
    protected Long getModified(SftpClient.DirEntry item) {
        try {
            var attrs = item.getAttributes();
            return attrs != null ? attrs.getModifyTime().toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        super.close();
        try { if (sftp != null) { sftp.close(); } } catch (Exception ignored) {}
        try { if (session != null) { session.close(); } } catch (Exception ignored) {}
        if (client != null) {
            client.stop();
        }
    }
}
