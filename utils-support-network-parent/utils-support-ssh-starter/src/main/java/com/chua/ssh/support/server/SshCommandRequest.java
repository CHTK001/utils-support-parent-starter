package com.chua.ssh.support.server;

import com.chua.common.support.lang.cmd.CommandLine;
import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;
import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SSH 命令请求，{@link ServerRequest} 实现。
 * <p>使用 {@link CommandLine} 将用户输入的 Shell 命令解析为路径（命令名）和参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class SshCommandRequest implements ServerRequest {

    /**
     * 原始命令行字符串
     */
    private final String commandLine;

    /**
     * 命令名称（第一个单词）
     */
    private final String commandName;

    /**
     * 命令参数数组（不含命令名）
     */
    private final String[] args;

    /**
     * 请求属性映射
     */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造 SSH 命令请求。
     * <p>先按空白符切分令牌，再通过 {@link CommandLine} 解析。
     * 第一个位置参数作为命令名，其余位置参数作为命令参数。</p>
     *
     * @param commandLine 用户输入的完整命令行字符串
     */
    public SshCommandRequest(String commandLine) {
        this.commandLine = commandLine != null ? commandLine.trim() : "";
        String[] parsed = parse(this.commandLine);
        this.commandName = parsed[0];
        this.args = parsed.length > 1
                ? Arrays.copyOfRange(parsed, 1, parsed.length)
                : new String[0];
    }

    /**
     * 解析命令行字符串，返回令牌数组。
     * <p>优先使用 {@link CommandLine} 解析，失败时回退到空白符分割。</p>
     *
     * @param line 命令行字符串
     * @return 令牌数组
     */
    private static String[] parse(String line) {
        String[] tokens = StringUtils.tokenizeToStringArray(line, " \t");
        if (tokens.length == 0) {
            return new String[]{""};
        }
        CommandLine cli = CommandLine.builder()
                .disableHelpOption()
                .build();
        try {
            CommandLine.Result result = cli.parse(tokens);
            List<String> positional = result.positionalArgs();
            if (positional.isEmpty()) {
                return new String[]{""};
            }
            String[] parsed = new String[positional.size()];
            for (int i = 0; i < positional.size(); i++) {
                parsed[i] = positional.get(i);
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            log.trace("CommandLine 解析回退到简单分割: {}", e.getMessage());
            return tokens;
        }
    }

    @Override
    public String getUri() {
        return commandLine;
    }

    @Override
    public String getPath() {
        return commandName;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.GET;
    }

    @Override
    public String getHeader(String name) {
        return null;
    }

    @Override
    public HttpHeader getHeaders() {
        return new HttpHeader();
    }

    @Override
    public Map<String, String> getParams() {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            result.put(String.valueOf(i), args[i]);
        }
        return result;
    }

    @Override
    public String getParam(String name) {
        if (name == null) {
            return null;
        }
        try {
            int idx = Integer.parseInt(name);
            return idx >= 0 && idx < args.length ? args[idx] : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取命令参数数组。
     *
     * @return 参数数组副本
     */
    public String[] getArgs() {
        return args.clone();
    }

    @Override
    public String getContentType() {
        return "text/plain";
    }

    @Override
    public long getContentLength() {
        return commandLine.length();
    }

    @Override
    public byte[] getBody() {
        return commandLine.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String getBodyString() {
        return commandLine;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(getBody());
    }

    @Override
    public String getRemoteAddress() {
        return null;
    }

    @Override
    public int getRemotePort() {
        return 0;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    public Map<String, String> getFormData() {
        return Map.of();
    }

    @Override
    public List<FormFile> getFiles() {
        return List.of();
    }
}
