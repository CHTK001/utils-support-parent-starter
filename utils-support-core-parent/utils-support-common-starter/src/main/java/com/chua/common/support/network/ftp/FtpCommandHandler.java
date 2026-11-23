// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.network.ftp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.chua.common.support.utils.StringUtils;

/**
 * FTP 命令处理器，负责解析和执行 FTP 协议命令。
 *
 * <p>FTP 协议基于文本命令/响应模式，控制连接与数据连接分离。
 * 命令处理器在控制连接上读取命令行，解析命令和参数，执行对应操作，
 * 通过控制连接发送响应码和消息。</p>
 *
 * <p>支持的 FTP 命令集：</p>
 * <ul>
 *   <li>认证：USER、PASS、QUIT</li>
 *   <li>目录：PWD、CWD、CDUP、MKD、RMD、DELE、RNFR、RNTO</li>
 *   <li>传输：TYPE、PORT、PASV、STOR、RETR、LIST、NLST、REST</li>
 *   <li>系统：SYST、FEAT、NOOP、SIZE</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
class FtpCommandHandler {

    /**
     * FTP 150 响应码：文件状态正常，即将打开数据连接传输（RFC 959）
     */
    private static final int CODE_TRANSFER_START = 150;

    /**
     * FTP 200 响应码：命令成功
     */
    private static final int CODE_OK = 200;

    /**
     * FTP 211 响应码：特性列表（FEAT，RFC 3659）
     */
    private static final int CODE_FEATURE_LIST = 211;

    /**
     * FTP 215 响应码：系统类型（SYST，RFC 959）
     */
    private static final int CODE_SYSTEM_TYPE = 215;

    /**
     * FTP 213 响应码：文件状态（SIZE 返回文件大小，RFC 959/3659）
     */
    private static final int CODE_FILE_STATUS = 213;

    /**
     * FTP 220 响应码：服务就绪
     */
    private static final int CODE_SERVICE_READY = 220;

    /**
     * FTP 221 响应码：服务关闭
     */
    private static final int CODE_SERVICE_CLOSE = 221;

    /**
     * FTP 226 响应码：数据连接关闭，请求的文件操作成功
     */
    private static final int CODE_DATA_CLOSE = 226;

    /**
     * FTP 227 响应码：进入被动模式
     */
    private static final int CODE_ENTER_PASV = 227;

    /**
     * FTP 230 响应码：用户登录成功
     */
    private static final int CODE_LOGIN_SUCCESS = 230;

    /**
     * FTP 331 响应码：用户名正确，需要密码
     */
    private static final int CODE_NEED_PASSWORD = 331;

    /**
     * FTP 350 响应码：请求的文件操作需要进一步命令
     */
    private static final int CODE_FILE_ACTION_PENDING = 350;

    /**
     * FTP 250 响应码：请求的文件操作已完成
     */
    private static final int CODE_FILE_ACTION_COMPLETED = 250;

    /**
     * FTP 257 响应码：路径名创建/返回成功（MKD/PWD，RFC 959）
     */
    private static final int CODE_PATHNAME_CREATED = 257;

    /**
     * FTP 421 响应码：服务不可用
     */
    private static final int CODE_SERVICE_UNAVAILABLE = 421;

    /**
     * FTP 425 响应码：无法打开数据连接
     */
    private static final int CODE_CANNOT_OPEN_DATA = 425;

    /**
     * FTP 500 响应码：语法错误，命令无法识别
     */
    private static final int CODE_SYNTAX_ERROR = 500;

    /**
     * FTP 501 响应码：参数语法错误
     */
    private static final int CODE_PARAM_ERROR = 501;

    /**
     * FTP 530 响应码：登录失败
     */
    private static final int CODE_LOGIN_FAILED = 530;

    /**
     * FTP 550 响应码：请求的操作未执行，文件不可用
     */
    private static final int CODE_FILE_UNAVAILABLE = 550;

    /**
     * FTP 536 响应码：请求的保护级别不受支持（RFC 4217）
     */
    private static final int CODE_PROTECTION_LEVEL_UNSUPPORTED = 536;

    /**
     * FTP 隐藏文件前缀
     */
    private static final String HIDDEN_FILE_PREFIX = ".";

    /**
     * 数据连接传输缓冲大小：256KB，替代 InputStream.transferTo 的 8KB 默认值
     */
    private static final int TRANSFER_BUFFER_SIZE = 256 * 1024;

    /**
     * 目录列表输出缓冲大小：64KB，替代逐行 flush
     */
    private static final int LIST_BUFFER_SIZE = 64 * 1024;

    /**
     * 日期格式化器
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.US);

    /**
     * MDTM 时间格式（RFC 3659：UTC，yyyyMMddHHmmss）
     */
    private static final DateTimeFormatter MDTM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * 所属 FTP 服务器（用于发布文件变更事件），由 {@link FtpServer} 构造完成后回填
     */
    private FtpServer ftpServer;

    /**
     * 设置所属 FTP 服务器。
     *
     * @param server FTP 服务器实例
     */
    void setFtpServer(FtpServer server) {
        this.ftpServer = server;
    }

    /**
     * 处理 FTP 命令。
     *
     * @param session FTP 会话
     * @param line    原始命令行
     * @return 是否继续处理（false 表示会话应关闭）
     */
    boolean handleCommand(FtpSession session, String line) {
        if (StringUtils.isBlank(line)) {
            return true;
        }
        // 解析命令和参数
        var parts = line.split("\\s+", 2);
        var command = parts[0].toUpperCase(Locale.ROOT);
        var argument = parts.length > 1 ? parts[1] : "";

        log.debug("FTP RECV [{}]: {} {}", session.getId(), command, argument);

        return switch (command) {
            case "USER" -> handleUser(session, argument);
            case "PASS" -> handlePass(session, argument);
            case "QUIT" -> handleQuit(session);
            case "SYST" -> handleSyst(session);
            case "FEAT" -> handleFeat(session);
            case "TYPE" -> handleType(session, argument);
            case "PWD" -> handlePwd(session);
            case "CWD" -> handleCwd(session, argument);
            case "CDUP" -> handleCdup(session);
            case "MKD" -> handleMkd(session, argument);
            case "RMD" -> handleRmd(session, argument);
            case "DELE" -> handleDele(session, argument);
            case "RNFR" -> handleRnfr(session, argument);
            case "RNTO" -> handleRnto(session, argument);
            case "PORT" -> handlePort(session, argument);
            case "PASV" -> handlePasv(session);
            case "STOR" -> handleStor(session, argument);
            case "RETR" -> handleRetr(session, argument);
            case "LIST" -> handleList(session, argument);
            case "NLST" -> handleNlst(session, argument);
            case "REST" -> handleRest(session, argument);
            case "SIZE" -> handleSize(session, argument);
            case "MDTM" -> handleMdtm(session, argument);
            case "NOTI" -> handleNoti(session, argument);
            case "PBSZ" -> handlePbsz(session);
            case "PROT" -> handleProt(session, argument);
            case "NOOP" -> {
                session.reply(CODE_OK, "NOOP ok.");
                yield true;
            }
            default -> {
                session.reply(CODE_SYNTAX_ERROR, "Command not understood: " + command);
                yield true;
            }
        };
    }

    /**
     * 处理 USER 命令：设置用户名。
     *
     * @param session  FTP 会话
     * @param username 用户名
     * @return 是否继续
     */
    private boolean handleUser(FtpSession session, String username) {
        if (username.isBlank()) {
            session.reply(CODE_PARAM_ERROR, "Username cannot be empty.");
            return true;
        }
        // 检查是否匿名用户
        if ("anonymous".equalsIgnoreCase(username) && session.getConfig().isAnonymousEnabled()) {
            session.setUsername(username);
            session.setAnonymous(true);
            // 匿名用户无需密码，直接登录成功
            session.setAuthenticated(true);
            // 确保匿名上传目录存在
            if (session.getConfig().isAllowAnonymousUpload()) {
                ensureAnonymousUploadDir(session);
            }
            session.reply(CODE_LOGIN_SUCCESS, "Anonymous login successful.");
        } else {
            session.setUsername(username);
            session.setAnonymous(false);
            session.reply(CODE_NEED_PASSWORD, "Password required.");
        }
        return true;
    }

    /**
     * 处理 PASS 命令：验证密码。
     *
     * @param session FTP 会话
     * @param password 密码
     * @return 是否继续
     */
    private boolean handlePass(FtpSession session, String password) {
        // 匿名用户已在 USER 阶段处理
        if (session.isAnonymous()) {
            session.reply(CODE_LOGIN_SUCCESS, "Already logged in as anonymous.");
            return true;
        }
        // 非匿名用户：配置了用户凭据表时严格校验（未知用户/密码错误一律 530）；
        // 未配置时保持旧行为——任意非匿名登录直接成功，仅建议开发/测试环境使用
        if (session.getUsername() != null && !session.getUsername().isBlank()) {
            Map<String, String> users = session.getConfig().getUsers();
            String expected = users == null ? null : users.get(session.getUsername());
            if (users != null && !users.isEmpty() && expected == null) {
                session.reply(CODE_LOGIN_FAILED, "Login incorrect.");
                return true;
            }
            if (expected != null && !expected.equals(password)) {
                session.reply(CODE_LOGIN_FAILED, "Login incorrect.");
                return true;
            }
            session.setAuthenticated(true);
            session.reply(CODE_LOGIN_SUCCESS, "Login successful.");
            log.info("FTP 用户登录: {} from {}", session.getUsername(),
                    session.getControlSocket().getRemoteSocketAddress());
        } else {
            session.reply(CODE_LOGIN_FAILED, "Login incorrect.");
        }
        return true;
    }

    /**
     * 处理 QUIT 命令：退出会话。
     *
     * @param session FTP 会话
     * @return 是否继续（始终返回 false）
     */
    private boolean handleQuit(FtpSession session) {
        session.reply(CODE_SERVICE_CLOSE, "Goodbye.");
        return false;
    }

    /**
     * 处理 SYST 命令：返回系统类型。
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handleSyst(FtpSession session) {
        session.reply(CODE_SYSTEM_TYPE, "UNIX Type: L8");
        return true;
    }

    /**
     * 处理 FEAT 命令：返回支持的扩展特性。
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handleFeat(FtpSession session) {
        session.replyMultiLine(CODE_FEATURE_LIST,
                "Extensions supported:",
                " UTF8",
                " SIZE",
                " MDTM",
                " NOTI",
                " PASV",
                " PORT",
                " REST STREAM",
                " END");
        return true;
    }

    /**
     * 处理 TYPE 命令：设置传输类型。
     *
     * @param session  FTP 会话
     * @param argument 类型参数（I=二进制，A=ASCII）
     * @return 是否继续
     */
    private boolean handleType(FtpSession session, String argument) {
        if (argument.isBlank()) {
            session.reply(CODE_PARAM_ERROR, "Type requires an argument.");
            return true;
        }
        var type = argument.charAt(0);
        switch (Character.toUpperCase(type)) {
            case 'I' -> {
                session.setBinaryMode(true);
                session.setTransferType('I');
                session.reply(CODE_OK, "Type set to binary.");
            }
            case 'A' -> {
                session.setBinaryMode(false);
                session.setTransferType('A');
                session.reply(CODE_OK, "Type set to ASCII.");
            }
            default -> {
                session.reply(CODE_PARAM_ERROR, "Unsupported type: " + argument);
            }
        }
        return true;
    }

    /**
     * 处理 PWD 命令：显示当前工作目录。
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handlePwd(FtpSession session) {
        var dir = session.getCurrentDir();
        // FTP 规范要求路径用双引号包裹
        session.reply(CODE_PATHNAME_CREATED, "\"" + dir + "\" is current directory.");
        return true;
    }

    /**
     * 处理 CWD 命令：切换工作目录。
     *
     * @param session  FTP 会话
     * @param argument 目录路径
     * @return 是否继续
     */
    private boolean handleCwd(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Directory not found: " + argument);
            return true;
        }
        if (!Files.isDirectory(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "Not a directory: " + argument);
            return true;
        }
        // 更新当前目录
        var homePath = session.getConfig().getHomeDirectory().toPath().toAbsolutePath().normalize();
        var relative = homePath.relativize(resolved).toString().replace('\\', '/');
        session.setCurrentDir("/" + relative);
        session.reply(CODE_FILE_ACTION_COMPLETED, "Directory changed to: " + session.getCurrentDir());
        return true;
    }

    /**
     * 处理 CDUP 命令：切换到上级目录。
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handleCdup(FtpSession session) {
        var currentDir = session.getCurrentDir();
        if ("/".equals(currentDir)) {
            session.reply(CODE_FILE_ACTION_COMPLETED, "Already at root directory.");
            return true;
        }
        // 获取上级目录
        var parent = currentDir.substring(0, currentDir.lastIndexOf('/'));
        if (parent.isEmpty()) {
            parent = "/";
        }
        session.setCurrentDir(parent);
        session.reply(CODE_FILE_ACTION_COMPLETED, "Directory changed to: " + session.getCurrentDir());
        return true;
    }

    /**
     * 处理 MKD 命令：创建目录。
     *
     * @param session  FTP 会话
     * @param argument 目录路径
     * @return 是否继续
     */
    private boolean handleMkd(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        try {
            Files.createDirectories(resolved);
            session.reply(CODE_PATHNAME_CREATED, "Directory created: " + argument);
            fireChange(session, FtpChangeEvent.EventType.DIRECTORY_CREATED, toFtpPath(session, resolved), null);
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Failed to create directory: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 RMD 命令：删除目录。
     *
     * @param session  FTP 会话
     * @param argument 目录路径
     * @return 是否继续
     */
    private boolean handleRmd(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        try {
            if (Files.isDirectory(resolved)) {
                // 删除空目录
                Files.deleteIfExists(resolved);
                session.reply(CODE_FILE_ACTION_COMPLETED, "Directory deleted: " + argument);
                fireChange(session, FtpChangeEvent.EventType.DIRECTORY_DELETED, toFtpPath(session, resolved), null);
            } else {
                session.reply(CODE_FILE_UNAVAILABLE, "Not a directory: " + argument);
            }
        } catch (DirectoryNotEmptyException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Directory not empty: " + argument);
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Failed to delete directory: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 DELE 命令：删除文件。
     *
     * @param session  FTP 会话
     * @param argument 文件路径
     * @return 是否继续
     */
    private boolean handleDele(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        if (!session.canWrite(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "Permission denied: " + argument);
            return true;
        }
        try {
            if (Files.deleteIfExists(resolved)) {
                session.reply(CODE_FILE_ACTION_COMPLETED, "File deleted: " + argument);
                fireChange(session, FtpChangeEvent.EventType.FILE_DELETED, toFtpPath(session, resolved), null);
            } else {
                session.reply(CODE_FILE_UNAVAILABLE, "File not found: " + argument);
            }
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Failed to delete file: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 RNFR 命令：设置重命名源文件。
     *
     * @param session  FTP 会话
     * @param argument 原文件路径
     * @return 是否继续
     */
    private boolean handleRnfr(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null || !Files.exists(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "File not found: " + argument);
            return true;
        }
        try {
            session.getControlSocket().setSoTimeout(session.getConfig().getControlTimeout() * 1000);
        } catch (java.net.SocketException e) {
            log.debug("[ftp] 设置超时失败，已忽略: {}", e.getMessage());
        }
        session.setAttribute("RNFR_PATH", resolved.toString());
        session.reply(CODE_FILE_ACTION_PENDING, "File found, ready for rename.");
        return true;
    }

    /**
     * 处理 RNTO 命令：执行重命名。
     *
     * @param session  FTP 会话
     * @param argument 新文件路径
     * @return 是否继续
     */
    private boolean handleRnto(FtpSession session, String argument) {
        var rnfrPath = (String) session.getAttribute("RNFR_PATH");
        if (rnfrPath == null) {
            session.reply(CODE_FILE_ACTION_PENDING, "RNFR not specified.");
            return true;
        }
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        try {
            Files.move(Path.of(rnfrPath), resolved);
            session.reply(CODE_FILE_ACTION_COMPLETED, "Rename successful.");
            fireChange(session, FtpChangeEvent.EventType.MOVED,
                    toFtpPath(session, resolved), toFtpPath(session, Path.of(rnfrPath)));
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Rename failed: " + e.getMessage());
        }
        session.removeAttribute("RNFR_PATH");
        return true;
    }

    /**
     * 处理 PORT 命令：设置主动模式数据连接。
     *
     * @param session  FTP 会话
     * @param argument PORT 参数（h1,h2,h3,h4,p1,p2）
     * @return 是否继续
     */
    private boolean handlePort(FtpSession session, String argument) {
        if (!session.getConfig().isAllowActiveMode()) {
            session.reply(CODE_SYNTAX_ERROR, "Active mode not allowed.");
            return true;
        }
        try {
            var parts = argument.split(",");
            if (parts.length != 6) {
                session.reply(CODE_PARAM_ERROR, "Invalid PORT argument.");
                return true;
            }
            var ip = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            var port = Integer.parseInt(parts[4]) * 256 + Integer.parseInt(parts[5]);
            // 保存到会话属性，等待后续数据命令使用
            session.setAttribute("ACTIVE_IP", ip);
            session.setAttribute("ACTIVE_PORT", port);
            session.reply(CODE_OK, "PORT command successful.");
        } catch (NumberFormatException e) {
            session.reply(CODE_PARAM_ERROR, "Invalid PORT argument.");
        }
        return true;
    }

    /**
     * 处理 PASV 命令：进入被动模式。
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handlePasv(FtpSession session) {
        if (!session.getConfig().isAllowPassiveMode()) {
            session.reply(CODE_SYNTAX_ERROR, "Passive mode not allowed.");
            return true;
        }
        try {
            var serverIp = session.getControlSocket().getLocalAddress().getHostAddress();
            var response = session.getDataChannel().enterPassiveMode(serverIp);
            session.reply(CODE_ENTER_PASV, response.substring(4));
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "Failed to enter passive mode: " + e.getMessage());
        }
        return true;
    }

    /**
     * 大缓冲字节拷贝：替代 {@link java.io.InputStream#transferTo(java.io.OutputStream)} 的 8KB 默认缓冲，
     * 数据连接批量传输时显著减少系统调用次数（实测 128MB 上传/下载吞吐提升数倍）。
     *
     * @param in  输入流
     * @param out 输出流
     * @throws IOException IO 异常
     */
    private static void transferLarge(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[TRANSFER_BUFFER_SIZE];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    /**
     * 处理 STOR 命令：上传文件。
     *
     * @param session  FTP 会话
     * @param argument 远程文件路径
     * @return 是否继续
     */
    private boolean handleStor(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        if (!session.canWrite(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "Permission denied: " + argument);
            return true;
        }
        try {
            // 等待数据连接
            var dataChannel = session.getDataChannel();
            if (!dataChannel.isListening()) {
                session.reply(CODE_CANNOT_OPEN_DATA, "No data connection established. Use PASV first.");
                return true;
            }
            session.reply(CODE_TRANSFER_START, "Opening data connection for STOR " + argument);
            // 接受数据连接
            var dataSocket = dataChannel.acceptDataConnection();
            // 覆盖已存在文件为「修改」，否则为「新建」（供变更事件区分）
            var existed = Files.exists(resolved);
            // 写入文件
            try (var dataIn = dataSocket.getInputStream();
                 var fileOut = Files.newOutputStream(resolved)) {
                transferLarge(dataIn, fileOut);
            }
            // REST 标记仅在紧随其后的传输中生效，用后即复位（RFC 3659）
            session.setRestartMarker(0);
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Transfer complete.");
            log.info("FTP 上传完成: {} -> {}", argument, resolved);
            fireChange(session, existed ? FtpChangeEvent.EventType.FILE_MODIFIED
                    : FtpChangeEvent.EventType.FILE_CREATED, toFtpPath(session, resolved), null);
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "Transfer failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 RETR 命令：下载文件。
     *
     * @param session  FTP 会话
     * @param argument 远程文件路径
     * @return 是否继续
     */
    private boolean handleRetr(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null) {
            session.reply(CODE_FILE_UNAVAILABLE, "Invalid path: " + argument);
            return true;
        }
        if (!Files.exists(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "File not found: " + argument);
            return true;
        }
        try {
            var dataChannel = session.getDataChannel();
            if (!dataChannel.isListening()) {
                session.reply(CODE_CANNOT_OPEN_DATA, "No data connection established. Use PASV first.");
                return true;
            }
            session.reply(CODE_TRANSFER_START, "Opening data connection for RETR " + argument);
            var dataSocket = dataChannel.acceptDataConnection();
            // 读取文件（REST 断点续传：从会话记录的偏移量开始传输，RFC 3659）
            long restOffset = session.getRestartMarker();
            try (var fileIn = Files.newInputStream(resolved);
                 var dataOut = dataSocket.getOutputStream()) {
                skipFully(fileIn, restOffset);
                transferLarge(fileIn, dataOut);
            }
            // REST 标记仅在紧随其后的传输中生效，用后即复位（RFC 3659）
            session.setRestartMarker(0);
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Transfer complete.");
            log.info("FTP 下载完成: {} -> {}", resolved, argument);
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "Transfer failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * 跳过指定数量的字节（REST 断点续传）。
     *
     * <p>{@link InputStream#skip(long)} 单次调用可能未跳完全部字节，需循环直至跳过
     * 指定偏移量；偏移量达到或超出文件长度时按文件结束处理。</p>
     *
     * @param in    输入流
     * @param count 要跳过的字节数
     * @throws IOException 读取失败
     */
    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    break;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    /**
     * 处理 LIST 命令：列出目录内容（详细格式）。
     *
     * @param session  FTP 会话
     * @param argument 目录路径（可为空）
     * @return 是否继续
     */
    private boolean handleList(FtpSession session, String argument) {
        var dirPath = session.resolvePath(argument.isEmpty() ? "." : argument);
        if (dirPath == null || !Files.isDirectory(dirPath)) {
            session.reply(CODE_FILE_UNAVAILABLE, "Directory not found: " + argument);
            return true;
        }
        try {
            var dataChannel = session.getDataChannel();
            if (!dataChannel.isListening()) {
                session.reply(CODE_CANNOT_OPEN_DATA, "No data connection established. Use PASV first.");
                return true;
            }
            session.reply(CODE_TRANSFER_START, "Here comes the directory listing.");
            var dataSocket = dataChannel.acceptDataConnection();
            // 生成目录列表
            try (var dataOut = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.UTF_8), LIST_BUFFER_SIZE))) {
                var entries = Files.list(dirPath).toList();
                for (var entry : entries) {
                    // 隐藏文件不显示（除非是 . 和 ..）
                    var name = entry.getFileName().toString();
                    if (name.startsWith(HIDDEN_FILE_PREFIX) && !".".equals(name) && !"..".equals(name)) {
                        continue;
                    }
                    var line = formatUnixListEntry(entry);
                    // RFC 959：数据连接上的文本行同样以 CRLF 结束
                    dataOut.print(line + "\r\n");
                }
            }
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Directory listing completed.");
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "List failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 NLST 命令：列出目录内容（名称列表）。
     *
     * @param session  FTP 会话
     * @param argument 目录路径（可为空）
     * @return 是否继续
     */
    private boolean handleNlst(FtpSession session, String argument) {
        var dirPath = session.resolvePath(argument.isEmpty() ? "." : argument);
        if (dirPath == null || !Files.isDirectory(dirPath)) {
            session.reply(CODE_FILE_UNAVAILABLE, "Directory not found: " + argument);
            return true;
        }
        try {
            var dataChannel = session.getDataChannel();
            if (!dataChannel.isListening()) {
                session.reply(CODE_CANNOT_OPEN_DATA, "No data connection established. Use PASV first.");
                return true;
            }
            session.reply(CODE_TRANSFER_START, "Here comes the name listing.");
            var dataSocket = dataChannel.acceptDataConnection();
            // 生成名称列表
            try (var dataOut = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.UTF_8), LIST_BUFFER_SIZE))) {
                var entries = Files.list(dirPath).toList();
                for (var entry : entries) {
                    var name = entry.getFileName().toString();
                    if (name.startsWith(HIDDEN_FILE_PREFIX) && !".".equals(name) && !"..".equals(name)) {
                        continue;
                    }
                    dataOut.print(name + "\r\n");
                }
            }
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Name listing completed.");
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "List failed: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 PBSZ 命令：设置保护缓冲大小（RFC 4217 固定为 0）。
     *
     * <p>真实 FTPS 客户端（curl/lftp/FileZilla）在 {@code AUTH TLS} 之后固定发
     * {@code PBSZ 0} 再发 {@code PROT P}，不支持就会在登录后直接放弃加密传输。</p>
     *
     * @param session FTP 会话
     * @return 是否继续
     */
    private boolean handlePbsz(FtpSession session) {
        session.reply(CODE_OK, "PBSZ=0");
        return true;
    }

    /**
     * 处理 PROT 命令：设置数据通道保护级别（RFC 4217）。
     *
     * <p>只有 {@code PROT P}（私有/加密）能被满足：本实现的 FTPS 数据连接固定走 TLS，
     * 无法降级为明文，因此请求 {@code C} 时明确回 536 而不是假装接受。</p>
     *
     * @param session  FTP 会话
     * @param argument 保护级别（P=私有、C=明文）
     * @return 是否继续
     */
    private boolean handleProt(FtpSession session, String argument) {
        if (!session.isDataTls()) {
            session.reply(CODE_PROTECTION_LEVEL_UNSUPPORTED,
                    "PROT not supported: data connection is not protected by TLS.");
            return true;
        }
        if (argument.isEmpty() || !"P".equalsIgnoreCase(argument.substring(0, 1))) {
            session.reply(CODE_PROTECTION_LEVEL_UNSUPPORTED,
                    "Only PROT P (private) is supported.");
            return true;
        }
        session.reply(CODE_OK, "Protection level set to Private.");
        return true;
    }

    /**
     * 处理 REST 命令：设置恢复标记（断点续传）。
     *
     * @param session  FTP 会话
     * @param argument 字节偏移量
     * @return 是否继续
     */
    private boolean handleRest(FtpSession session, String argument) {
        try {
            var offset = Long.parseLong(argument);
            session.setRestartMarker(offset);
            session.reply(CODE_FILE_ACTION_PENDING, "Restart position accepted: " + offset);
        } catch (NumberFormatException e) {
            session.reply(CODE_PARAM_ERROR, "Invalid REST argument.");
        }
        return true;
    }

    /**
     * 处理 SIZE 命令：获取文件大小。
     *
     * @param session  FTP 会话
     * @param argument 文件路径
     * @return 是否继续
     */
    private boolean handleSize(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null || !Files.exists(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "File not found: " + argument);
            return true;
        }
        try {
            var size = Files.size(resolved);
            session.reply(CODE_FILE_STATUS, String.valueOf(size));
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Cannot get file size: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 MDTM 命令：获取文件最后修改时间（RFC 3659，UTC，yyyyMMddHHmmss）。
     *
     * @param session  FTP 会话
     * @param argument 文件路径
     * @return 是否继续
     */
    private boolean handleMdtm(FtpSession session, String argument) {
        var resolved = session.resolvePath(argument);
        if (resolved == null || !Files.exists(resolved)) {
            session.reply(CODE_FILE_UNAVAILABLE, "File not found: " + argument);
            return true;
        }
        try {
            var mtime = LocalDateTime.ofInstant(Files.getLastModifiedTime(resolved).toInstant(), ZoneOffset.UTC);
            session.reply(CODE_FILE_STATUS, MDTM_FORMAT.format(mtime));
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Cannot get modification time: " + e.getMessage());
        }
        return true;
    }

    /**
     * 处理 NOTI 命令：开关服务端主动变更通知。
     *
     * <p>{@code NOTI ON} 后，本会话文件变更会以 {@code NOTIFY <type> <path> [<from>]}
     * 行从控制连接主动推送（非请求-应答语义，客户端需自行识别）。</p>
     *
     * @param session  FTP 会话
     * @param argument ON / OFF
     * @return 是否继续
     */
    private boolean handleNoti(FtpSession session, String argument) {
        var flag = argument.trim().toUpperCase(Locale.ROOT);
        if ("ON".equals(flag)) {
            session.setNotifyEnabled(true);
            session.reply(CODE_OK, "Notify enabled.");
        } else if ("OFF".equals(flag)) {
            session.setNotifyEnabled(false);
            session.reply(CODE_OK, "Notify disabled.");
        } else {
            session.reply(CODE_PARAM_ERROR, "Usage: NOTI ON|OFF");
        }
        return true;
    }

    /**
     * 发布文件变更事件（回调监听器并向开启通知的会话推送 NOTIFY 行）。
     *
     * @param session      触发变更的会话
     * @param type         事件类型
     * @param path         变更后的 FTP 路径
     * @param previousPath 原路径（仅 MOVED 事件）
     */
    private void fireChange(FtpSession session, FtpChangeEvent.EventType type, String path, String previousPath) {
        if (ftpServer == null) {
            return;
        }
        ftpServer.fireChange(new FtpChangeEvent(type, path, previousPath, session.getUsername()));
    }

    /**
     * 将服务器文件系统绝对路径转换为 FTP 路径（以 / 开头、正斜杠分隔，相对于根目录）。
     *
     * @param session  FTP 会话
     * @param resolved 服务器绝对路径
     * @return FTP 路径
     */
    private static String toFtpPath(FtpSession session, Path resolved) {
        var homePath = session.getConfig().getHomeDirectory().toPath().toAbsolutePath().normalize();
        var relative = homePath.relativize(resolved.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return "/" + relative;
    }

    /**
     * 确保匿名上传目录存在。
     *
     * @param session FTP 会话
     */
    private void ensureAnonymousUploadDir(FtpSession session) {
        var uploadDir = Path.of(
                session.getConfig().getHomeDirectory().getAbsolutePath(),
                session.getConfig().getAnonymousUploadDir());
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException e) {
            log.warn("无法创建匿名上传目录: {}", uploadDir);
        }
    }

    /**
     * 格式化 Unix 风格的目录列表条目。
     *
     * @param path 文件路径
     * @return Unix ls -l 格式的字符串
     */
    private String formatUnixListEntry(Path path) {
        var isDir = Files.isDirectory(path);
        var size = 0L;
        var lastModified = LocalDateTime.now();
        try {
            if (!isDir) {
                size = Files.size(path);
            }
            var attr = Files.getLastModifiedTime(path);
            lastModified = LocalDateTime.ofInstant(attr.toInstant(), java.time.ZoneId.systemDefault());
        } catch (IOException e) {
            log.debug("[ftp] IO 操作失败，已忽略: {}", e.getMessage());
        }
        var perm = isDir ? "drwxr-xr-x" : "-rw-r--r--";
        var linkCount = isDir ? "2" : "1";
        var owner = "ftp";
        var group = "ftp";
        var dateStr = lastModified.format(DATE_FORMAT);
        var name = path.getFileName().toString();
        // 目录名称加 / 后缀
        var displayName = isDir ? name + "/" : name;
        return String.format("%s %s %s %s %d %s %s",
                perm, linkCount, owner, group, size, dateStr, displayName);
    }
}