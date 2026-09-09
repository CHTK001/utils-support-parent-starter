package com.chua.common.support.network.stdio.invoke;

import com.chua.common.support.network.stdio.StdioClientInvoker;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * Stdio 客户端执行器抽象类
 *
 * @author CH
 */
public abstract class AbstractStdioClientInvoker implements StdioClientInvoker {

    protected String command;
    protected List<String> args;
    protected Map<String, String> env;
    protected String workDirectory;
    protected boolean useCmdWrapper;
    protected String remoteHost;

    /**
     * 设置命令
     *
     * @param command 命令
     */
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * 设置参数
     *
     * @param args 参数列表
     */
    public void setArgs(List<String> args) {
        this.args = args;
    }

    /**
     * 设置环境变量
     *
     * @param env 环境变量映射
     */
    public void setEnv(Map<String, String> env) {
        this.env = env;
    }

    /**
     * 设置工作目录
     *
     * @param workDirectory 工作目录
     */
    public void setWorkDirectory(String workDirectory) {
        this.workDirectory = workDirectory;
    }

    /**
     * 设置是否使用 cmd 包装
     *
     * @param useCmdWrapper 是否使用
     */
    public void setUseCmdWrapper(boolean useCmdWrapper) {
        this.useCmdWrapper = useCmdWrapper;
    }

    /**
     * 设置远程主机
     *
     * @param remoteHost 远程主机地址
     */
    public void setRemoteHost(String remoteHost) {
        this.remoteHost = remoteHost;
    }
}

