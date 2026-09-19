package com.chua.smb.client;

import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msdtyp.AccessMask;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * 链式 SMB 客户端，基于 smb-jna。
 *
 * <p>用法：</p>
 * <pre>{@code
 * try (SmbClient client = SmbClient.create("smb://admin:pass@192.168.1.10:445/share")
 *         .connect()
 *         .login()
 *         .openShare()
 *         .cd("/folder")
 *         .upload(inputStream, "test.txt")
 *         .download("test.txt", outputStream)
 *         .listFiles("/")
 *         .close();
 * }</pre>       .download("test.txt", outputStream)
 *         .listFiles("/")
 *         .close();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SmbClient implements AutoCloseable {

    /**
     * URI 地址
     */
    private final String uri;
    /**
     * smb 客户端
     */
    private final SMBClient smbClient;
    /**
     * connection
     */
    private Connection connection;
    /**
     * 会话对象
     */
    private Session session;
    /**
     * disk 共享
     */
    private DiskShare diskShare;

    /**
     * 主机地址
     */
    private String host;
    /**
     * 端口号
     */
    private int port;
    /**
     * 用户
     */
    private String user;
    /**
     * 登录密码
     */
    private String password;
    /**
     * 共享 名称
     */
    private String shareName;
    /**
     * work 路径
     */
    private String workPath = "/";

    /**
     * 创建 smb客户端 实例
     * @param uri uri
     */
    private SmbClient(String uri) {
        this.uri = uri;
        this.smbClient = new SMBClient();
        parseUri(uri);
    }

    /**
     * 创建
     *
     * @param uri uri
     * @return 创建的结果
     */
    public static SmbClient create(String uri) {
        return new SmbClient(uri);
    }

    /**
     * 解析Uri
     *
     * @param uri uri
     */
    private void parseUri(String uri) {
        String rest = uri.replaceFirst("^smb://", "");
        String userPass = "";
        int at = rest.indexOf('@');
        if (at > 0) {
            userPass = rest.substring(0, at);
            rest = rest.substring(at + 1);
        }
        String[] up = userPass.split(":", 2);
        this.user = up.length > 0 && !up[0].isEmpty() ? up[0] : null;
        this.password = up.length > 1 ? up[1] : "";

        int slash = rest.indexOf('/');
        String hostPort = slash > 0 ? rest.substring(0, slash) : rest;
        this.shareName = slash > 0 ? rest.substring(slash + 1) : "";

        String[] hp = hostPort.split(":", 2);
        this.host = hp[0];
        this.port = hp.length > 1 ? Integer.parseInt(hp[1]) : 445;
    }

    /**
     * 连接
     *
     * @return 连接的结果
     */
    public SmbClient connect() {
        try {
            connection = smbClient.connect(host, port);
        } catch (Exception e) {
            throw new RuntimeException("SMB 连接失败: " + host + ":" + port, e);
        }
        return this;
    }

    /**
     * Login
     *
     * @return login的结果
     */
    public SmbClient login() {
        try {
            AuthenticationContext authCtx = (user == null || user.isEmpty())
                ? AuthenticationContext.anonymous()
                : new AuthenticationContext(user, password.toCharArray(), null);
            session = connection.authenticate(authCtx);
        } catch (Exception e) {
            throw new RuntimeException("SMB 认证失败: " + (user == null ? "<anonymous>" : user), e);
        }
        return this;
    }

    /**
     * 打开共享
     *
     * @return 打开共享的结果
     */
    public SmbClient openShare() {
        if (session == null) {
            throw new IllegalStateException("请先 login()");
        }
        try {
            diskShare = (DiskShare) session.connectShare(shareName);
        } catch (Exception e) {
            throw new RuntimeException("SMB 打开 share 失败: " + shareName, e);
        }
        return this;
    }

    /**
     * Cd
     *
     * @param path 路径
     * @return cd的结果
     */
    public SmbClient cd(String path) {
        if (path == null || path.isEmpty()) {
            return this;
        }
        if (!path.startsWith("/")) {
            path = workPath + "/" + path;
        }
        workPath = normalize(path);
        return this;
    }

    /**
     * Upload
     *
     * @param in 入
     * @param remoteName 远程名称
     * @return upload的结果
     */
    public SmbClient upload(InputStream in, String remoteName) {
        checkShare();
        try {
            String fullPath = normalize(workPath + "/" + remoteName);
            ensureParentPath(fullPath);
            File file = diskShare.openFile(
                    fullPath,
                    EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.noneOf(SMB2ShareAccess.class),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            );
            try (OutputStream out = file.getOutputStream()) {
                in.transferTo(out);
            } finally {
                file.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("SMB 上传失败: " + remoteName, e);
        }
        return this;
    }

    /**
     * Download
     *
     * @param remoteName 远程名称
     * @param out 出
     * @return download的结果
     */
    public SmbClient download(String remoteName, OutputStream out) {
        checkShare();
        try {
            String fullPath = normalize(workPath + "/" + remoteName);
            File file = diskShare.openFile(
                    fullPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.noneOf(SMB2ShareAccess.class),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            );
            try (InputStream in = file.getInputStream()) {
                in.transferTo(out);
            } finally {
                file.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("SMB 下载失败: " + remoteName, e);
        }
        return this;
    }

    /**
     * 列表文件
     *
     * @param path 路径
     * @return 列表文件的结果
     */
    public List<SmbFileEntry> listFiles(String path) {
        checkShare();
        String dirPath = path != null ? normalize(path) : workPath;
        List<SmbFileEntry> result = new ArrayList<>();
        try {
            for (FileIdBothDirectoryInformation info : diskShare.list(dirPath)) {
                String name = info.getFileName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                result.add(new SmbFileEntry(
                        name,
                        info.getEndOfFile(),
                        (info.getFileAttributes() & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0,
                        info.getLastWriteTime().toDate().getTime(),
                        dirPath
                ));
            }
        } catch (Exception e) {
            throw new RuntimeException("SMB 列目录失败: " + dirPath, e);
        }
        return result;
    }

    /**
     * 创建目录
     *
     * @param path 路径
     * @return mkdir的结果
     */
    public SmbClient mkdir(String path) {
        checkShare();
        try {
            String fullPath = path.startsWith("/") ? path : workPath + "/" + path;
            diskShare.mkdir(normalize(fullPath));
        } catch (Exception e) {
            throw new RuntimeException("SMB 创建目录失败: " + path, e);
        }
        return this;
    }

    /**
     * 删除
     *
     * @param path 路径
     * @return 删除的结果
     */
    public SmbClient delete(String path) {
        checkShare();
        try {
            String fullPath = path.startsWith("/") ? path : workPath + "/" + path;
            diskShare.rm(normalize(fullPath));
        } catch (Exception e) {
            throw new RuntimeException("SMB 删除失败: " + path, e);
        }
        return this;
    }

    @Override
    /** 关闭 */
    public void close() {
        try {
            if (diskShare != null) {
                diskShare.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (Exception ignored) {
        }
        try {
            smbClient.close();
        } catch (Exception ignored) {
        }
    }

    /** 校验共享 */
    private void checkShare() {
        if (diskShare == null) {
            throw new IllegalStateException("请先 openShare()");
        }
    }

    /**
     * ensure父路径
     *
     * @param fullPath 完整路径
     */
    private void ensureParentPath(String fullPath) throws IOException {
        int lastSep = fullPath.lastIndexOf('/');
        if (lastSep <= 0) {
            return;
        }
        String parent = fullPath.substring(0, lastSep);
        String[] parts = parent.split("/");
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            path.append("/").append(part);
            if (!diskShare.folderExists(path.toString())) {
                diskShare.mkdir(path.toString());
            }
        }
    }

    /**
     * Normalize
     *
     * @param p p
     * @return normalize的结果
     */
    private static String normalize(String p) {
        String s = p.replace('\\', '/');
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    /**
     * smb文件entry
     * @param name 名称
     * @param size 大小
     * @param isDirectory 是否目录
     * @param lastModified 最后一个modified
     * @param path 路径
     */
    public record SmbFileEntry(
            String name,
            long size,
            boolean isDirectory,
            long lastModified,
            String path
    ) {}
}
