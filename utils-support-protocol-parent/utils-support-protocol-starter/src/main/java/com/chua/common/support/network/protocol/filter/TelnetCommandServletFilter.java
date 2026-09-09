package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.core.annotation.SpiSupport;
import com.chua.common.support.network.protocol.ProtocolType;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.request.TelnetServletRequest;
import com.chua.common.support.network.protocol.request.TelnetServletResponse;
import com.chua.common.support.network.protocol.server.AbstractServletFilter;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Telnet命令处理过滤器
 * <p>
 * 提供基本的Telnet命令处理功能，包括：
 * 1. 内置命令处理（help、time、echo、quit等）
 * 2. 命令参数解析
 * 3. 会话信息显示
 * 4. 错误处理
 * 5. 命令统计
 *
 * @author CH
 * @since 2024/7/8
 */
@Slf4j
@Spi("telnetCommand")
@SpiSupport("telnet")
@SpiDescribe("Telnet命令处理过滤器")
public class TelnetCommandServletFilter  extends AbstractServletFilter implements ServletFilter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, ServletFilterChain chain) throws Exception {
        // 检查是否为Telnet请求
        if (!(request instanceof TelnetServletRequest telnetRequest) || 
            !(response instanceof TelnetServletResponse telnetResponse)) {
            // 不是Telnet请求，继续执行过滤器链
            chain.doFilter(request, response);
            return;
        }

        String command = telnetRequest.getCommandName().toLowerCase();
        
        if (log.isDebugEnabled()) {
            log.debug("处理Telnet命令: {} from {}", command, telnetRequest.getRemoteAddr());
        }
        
        // 处理内置命令
        boolean handled = handleBuiltinCommand(telnetRequest, telnetResponse, command);
        
        if (!handled) {
            // 命令未被处理，继续执行过滤器链
            chain.doFilter(request, response);
            
            // 如果过滤器链也没有处理，返回命令不存在
            if (response.getStatusCode() == 0 || response.getBodyString().isEmpty()) {
                telnetResponse.setStatusCode(404);
                telnetResponse.setBodyString("命令不存在: " + command + "\n输入 'help' 查看可用命令");
            }
        }
    }

    /**
     * 处理内置命令
     */
    private boolean handleBuiltinCommand(TelnetServletRequest request, TelnetServletResponse response, String command) {
        switch (command) {
            case "help":
            case "?":
                handleHelpCommand(request, response);
                return true;
                
            case "time":
                handleTimeCommand(request, response);
                return true;
                
            case "echo":
                handleEchoCommand(request, response);
                return true;
                
            case "session":
                handleSessionCommand(request, response);
                return true;
                
            case "quit":
            case "exit":
                handleQuitCommand(request, response);
                return true;
                
            case "clear":
                handleClearCommand(request, response);
                return true;
                
            case "status":
                handleStatusCommand(request, response);
                return true;
                
            default:
                return false; // 命令未处理
        }
    }

    /**
     * 处理help命令
     */
    private void handleHelpCommand(TelnetServletRequest request, TelnetServletResponse response) {
        String help = "可用命令:\n" +
                "  help, ?     - 显示此帮助信息\n" +
                "  time        - 显示当前时间\n" +
                "  echo <text> - 回显文本\n" +
                "  session     - 显示会话信息\n" +
                "  status      - 显示服务器状态\n" +
                "  clear       - 清屏\n" +
                "  quit, exit  - 退出连接\n";
        
        response.setStatusCode(200);
        response.setBodyString(help);
    }

    /**
     * 处理time命令
     */
    private void handleTimeCommand(TelnetServletRequest request, TelnetServletResponse response) {
        LocalDateTime now = LocalDateTime.now();
        String timeStr = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        
        response.setStatusCode(200);
        response.setBodyString("当前时间: " + timeStr);
    }

    /**
     * 处理echo命令
     */
    private void handleEchoCommand(TelnetServletRequest request, TelnetServletResponse response) {
        String[] args = request.getCommandArgs();
        if (args.length == 0) {
            response.setStatusCode(400);
            response.setBodyString("用法: echo <text>");
            return;
        }
        
        String text = String.join(" ", args);
        response.setStatusCode(200);
        response.setBodyString("回显: " + text);
    }

    /**
     * 处理session命令
     */
    private void handleSessionCommand(TelnetServletRequest request, TelnetServletResponse response) {
        String info = "会话信息:\n" +
                "  会话ID: " + request.getTelnetSession().getSessionId() + "\n" +
                "  客户端地址: " + request.getRemoteAddr() + ":" + request.getRemotePort() + "\n" +
                "  连接时间: " + request.getTelnetSession().getCreateTime() + "\n" +
                "  持续时间: " + request.getTelnetSession().getDurationSeconds() + " 秒\n" +
                "  命令数量: " + request.getTelnetSession().getCommandCount().get() + "\n" +
                "  接收字节: " + request.getTelnetSession().getBytesReceived().get() + "\n" +
                "  发送字节: " + request.getTelnetSession().getBytesSent().get() + "\n";
        
        response.setStatusCode(200);
        response.setBodyString(info);
    }

    /**
     * 处理quit命令
     */
    private void handleQuitCommand(TelnetServletRequest request, TelnetServletResponse response) {
        response.setStatusCode(200);
        response.setBodyString("再见!");
        response.setTerminateEarly(true); // 标记提前终止，关闭连接
    }

    /**
     * 处理clear命令
     */
    private void handleClearCommand(TelnetServletRequest request, TelnetServletResponse response) {
        // ANSI清屏序列
        String clearScreen = "\u001B[2J\u001B[H";
        response.setStatusCode(200);
        response.setBodyString(clearScreen);
    }

    /**
     * 处理status命令
     */
    private void handleStatusCommand(TelnetServletRequest request, TelnetServletResponse response) {
        String status = "服务器状态:\n" +
                "  协议: Telnet\n" +
                "  服务器地址: " + request.getLocalAddr() + ":" + request.getLocalPort() + "\n" +
                "  当前时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "\n" +
                "  请求ID: " + request.getRequestId() + "\n";
        
        response.setStatusCode(200);
        response.setBodyString(status);
    }

    @Override
    public String getFilterName() {
        return "TelnetCommandFilter";
    }

    @Override
    public int getOrder() {
        return 100; // 较低优先级，让其他过滤器先处理
    }

    @Override
    public String getDescription() {
        return "Telnet内置命令处理过滤器";
    }

    @Override
    public boolean supportProtocol(String protocol) {
        // 只支持Telnet协议
        return "telnet".equalsIgnoreCase(protocol);
    }

    @Override
    public ProtocolType[] getSupportedProtocolTypes() {
        return new ProtocolType[]{ProtocolType.TELNET};
    }
}
