package com.chua.runtime.shell;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.command.CommandRegistry;
import com.chua.runtime.shell.output.Console;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/**
 * Shell 会话 — 处理单个 Telnet 连接的命令交互。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ShellSession implements Runnable {

    /**
     * 客户端连接
     */
    private final Socket socket;

    /**
     * 命令注册表
     */
    private final CommandRegistry registry;

    /**
     * 欢迎横幅
     */
    private static final String BANNER = "Chua Runtime Shell v4.0.0.42";

    /**
     * 创建会话。
     *
     * @param socket   客户端连接
     * @param registry 命令注册表
     */
    public ShellSession(Socket socket, CommandRegistry registry) {
        this.socket = socket;
        this.registry = registry;
    }

    @Override
    public void run() {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            Console console = new Console(writer);
            console.println(BANNER);
            console.println("输入 help 查看可用命令，输入 exit 退出。");
            console.blank();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                // 处理命令
                String[] parts = line.split("\\s+");
                String cmdName = parts[0];
                String[] args = new String[parts.length - 1];
                System.arraycopy(parts, 1, args, 0, args.length);

                if ("exit".equalsIgnoreCase(cmdName) || "quit".equalsIgnoreCase(cmdName)) {
                    console.success("再见");
                    break;
                }

                Command command = registry.find(cmdName);
                if (command == null) {
                    console.error("未知命令: " + cmdName + "，输入 help 查看");
                    continue;
                }
                try {
                    command.execute(args, console);
                } catch (Exception e) {
                    console.error("命令执行异常: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.debug("会话关闭: {}", e.getMessage());
        }
    }
}