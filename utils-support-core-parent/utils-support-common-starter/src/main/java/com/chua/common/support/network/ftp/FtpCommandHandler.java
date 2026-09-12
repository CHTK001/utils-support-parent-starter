package com.chua.common.support.network.ftp;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

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
    * FTP 200 响应码：命令成功
     */
    private static final int CODE_OK = 200;

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
    * FTP 隐藏文件前缀
     */
    private static final String HIDDEN_FILE_PREFIX = ".";

    /**
    * 日期格式化器
     */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MMM dd HH:mm", Locale.US);

    /**
    * 处理 FTP 命令。
    *
    * @param session FTP 会话
    * @param line    原始命令行
    * @return 是否继续处理（false 表示会话应关闭）
     */
    boolean handleCommand(FtpSession session, String line) {
        if (line == null || line.isBlank()) {
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
        // 非匿名用户：简单密码验证（实际生产环境应使用数据库/LDAP）
        if (session.getUsername() != null && !session.getUsername().isBlank()) {
            // 所有用户名密码组合都允许登录（简化实现）
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
        session.reply(CODE_OK, "UNIX Type: L8");
        return true;
    }

    /**
    * 处理 FEAT 命令：返回支持的扩展特性。
    *
    * @param session FTP 会话
    * @return 是否继续
     */
    private boolean handleFeat(FtpSession session) {
        session.replyMultiLine(CODE_OK,
                "Extensions supported:",
                " UTF8",
                " SIZE",
                " MDTM",
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
        session.reply(CODE_FILE_ACTION_PENDING, "\"" + dir + "\" is current directory.");
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
        session.reply(CODE_FILE_ACTION_PENDING, "Directory changed to: " + session.getCurrentDir());
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
        if (currentDir.equals("/")) {
            session.reply(CODE_FILE_ACTION_PENDING, "Already at root directory.");
            return true;
        }
        // 获取上级目录
        var parent = currentDir.substring(0, currentDir.lastIndexOf('/'));
        if (parent.isEmpty()) {
            parent = "/";
        }
        session.setCurrentDir(parent);
        session.reply(CODE_FILE_ACTION_PENDING, "Directory changed to: " + session.getCurrentDir());
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
            session.reply(CODE_FILE_ACTION_PENDING, "Directory created: " + argument);
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
                session.reply(CODE_DATA_CLOSE, "Directory deleted: " + argument);
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
                session.reply(CODE_DATA_CLOSE, "File deleted: " + argument);
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
        } catch (java.net.SocketException ignored) {
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
            session.reply(CODE_FILE_ACTION_PENDING, "Rename successful.");
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
            session.reply(CODE_OK, "Opening data connection for STOR " + argument);
            // 接受数据连接
            var dataSocket = dataChannel.acceptDataConnection();
            // 写入文件
            try (var dataIn = dataSocket.getInputStream();
                 var fileOut = Files.newOutputStream(resolved)) {
                dataIn.transferTo(fileOut);
            }
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Transfer complete.");
            log.info("FTP 上传完成: {} -> {}", argument, resolved);
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
            session.reply(CODE_OK, "Opening data connection for RETR " + argument);
            var dataSocket = dataChannel.acceptDataConnection();
            // 读取文件
            try (var fileIn = Files.newInputStream(resolved);
                 var dataOut = dataSocket.getOutputStream()) {
                fileIn.transferTo(dataOut);
            }
            dataChannel.close();
            session.reply(CODE_DATA_CLOSE, "Transfer complete.");
            log.info("FTP 下载完成: {} -> {}", resolved, argument);
        } catch (IOException e) {
            session.reply(CODE_CANNOT_OPEN_DATA, "Transfer failed: " + e.getMessage());
        }
        return true;
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
            session.reply(CODE_OK, "Here comes the directory listing.");
            var dataSocket = dataChannel.acceptDataConnection();
            // 生成目录列表
            try (var dataOut = new PrintWriter(dataSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                var entries = Files.list(dirPath).toList();
                for (var entry : entries) {
                    // 隐藏文件不显示（除非是 . 和 ..）
                    var name = entry.getFileName().toString();
                    if (name.startsWith(HIDDEN_FILE_PREFIX) && !name.equals(".") && !name.equals("..")) {
                        continue;
                    }
                    var line = formatUnixListEntry(entry);
                    dataOut.println(line);
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
            session.reply(CODE_OK, "Here comes the name listing.");
            var dataSocket = dataChannel.acceptDataConnection();
            // 生成名称列表
            try (var dataOut = new PrintWriter(dataSocket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                var entries = Files.list(dirPath).toList();
                for (var entry : entries) {
                    var name = entry.getFileName().toString();
                    if (name.startsWith(HIDDEN_FILE_PREFIX) && !name.equals(".") && !name.equals("..")) {
                        continue;
                    }
                    dataOut.println(name);
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
            session.reply(CODE_OK, String.valueOf(size));
        } catch (IOException e) {
            session.reply(CODE_FILE_UNAVAILABLE, "Cannot get file size: " + e.getMessage());
        }
        return true;
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
        } catch (IOException ignored) {
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
