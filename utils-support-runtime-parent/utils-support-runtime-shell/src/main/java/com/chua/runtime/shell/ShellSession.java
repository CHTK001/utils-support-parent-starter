package com.chua.runtime.shell;

import com.chua.runtime.shell.command.Command;
import com.chua.runtime.shell.command.CommandRegistry;
import com.chua.runtime.shell.output.Console;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.List;

/**
 * Shell 会话 — 处理单个 Telnet 连接的命令交互。
 *
 * <p>支持 Tab 键补全：当客户端发送 \t 时，调用 Command.complete()
 * 或 命令registry.完成() 给出补全建议并回显。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class ShellSession implements Runnable {


    /**
     * 日志
     */
    private static final Logger LOG = Logger.getLogger(ShellSession.class.getName());
    /**
     * 欢迎横幅
     */
    private static final String BANNER = "Chua Runtime Shell v4.0.0.42";

    /**
     * "exit" 命令
     */
    private static final String CMD_EXIT = "exit";

    /**
     * "quit" 命令
     */
    private static final String CMD_QUIT = "quit";

    /**
     * 客户端连接
     */
    private final Socket socket;

    /**
     * 命令注册表
     */
    private final CommandRegistry registry;

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
    /** 运行 */
    public void run() {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

            Console console = new Console(writer);
            console.println(BANNER);
            console.println("输入 help 查看可用命令，输入 exit 退出。Tab 键可补全。");
            console.blank();

            StringBuilder buffer = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                if (ch == '\n' || ch == '\r') {
                    // 回车：执行当前行
                    writer.println();
                    String line = buffer.toString().trim();
                    buffer.setLength(0);
                    if (!line.isEmpty()) {
                        processLine(line, console, writer);
                    }
                    writer.print("> ");
                    writer.flush();
                } else if (ch == '\t') {
                    // Tab：补全
                    handleCompletion(buffer.toString(), writer);
                } else if (ch == 127 || ch == 8) {
                    // 退格
                    if (buffer.length() > 0) {
                        buffer.deleteCharAt(buffer.length() - 1);
                        writer.print("\b \b");
                        writer.flush();
                    }
                } else if (ch >= 32 && ch < 127) {
                    buffer.append((char) ch);
                    writer.print((char) ch);
                    writer.flush();
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, String.format("会话关闭: %s", e.getMessage()));
        }
    }

    /**
     * 处理单行命令。
     *
     * @param line    行内容
     * @param console 控制台输出
     * @param writer  Telnet 输出
     */
    private void processLine(String line, Console console, PrintWriter writer) {
        String[] parts = line.split("\\s+");
        String cmdName = parts[0];
        String[] args = new String[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);

        if (CMD_EXIT.equalsIgnoreCase(cmdName) || CMD_QUIT.equalsIgnoreCase(cmdName)) {
            console.success("再见");
            return;
        }

        Command command = registry.find(cmdName);
        if (command == null) {
            console.error("未知命令: " + cmdName + "，输入 help 查看");
            return;
        }
        try {
            command.execute(args, console);
        } catch (Exception e) {
            console.error("命令执行异常: " + e.getMessage());
        }
    }

    /**
     * 处理 Tab 补全。
     *
     * @param current 当前行缓冲
     * @param writer  Telnet 输出
     */
    private void handleCompletion(String current, PrintWriter writer) {
        String[] parts = current.split("\\s+", -1);
        if (parts.length <= 1) {
            // 顶层命令补全
            String prefix = parts.length == 0 ? "" : parts[0];
            List<String> matches = registry.complete(prefix);
            printCompletions(matches, writer);
        } else {
 // 子命令/参数补全（委派给具体 命令）
            String cmdName = parts[0];
            Command command = registry.find(cmdName);
            if (command == null) {
                return;
            }
            String[] args = new String[parts.length - 1];
            System.arraycopy(parts, 1, args, 0, args.length);
            List<String> matches = command.complete(args);
            printCompletions(matches, writer);
        }
    }

    /**
     * 打印补全候选。
     *
     * @param matches 候选列表
     * @param writer  输出
     */
    private void printCompletions(List<String> matches, PrintWriter writer) {
        if (matches == null || matches.isEmpty()) {
            return;
        }
        writer.println();
        for (String m : matches) {
            writer.print("  " + m);
        }
        writer.println();
        writer.flush();
    }
}
