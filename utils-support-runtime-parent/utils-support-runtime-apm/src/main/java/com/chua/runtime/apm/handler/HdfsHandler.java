package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * HDFS 处理器 — intercepts Hadoop 文件系统 operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HdfsHandler extends AbstractAppHandler {

    /**
     * 文件 系统
     */
    private static final String FILE_SYSTEM = "org/apache/hadoop/fs/FileSystem";
    /**
     * fs 方法
     */
    private static final String[] FS_METHODS = {"open", "create", "delete", "rename", "listStatus", "mkdirs", "exists", "getFileStatus"};

    /**
     * 名称
    */
    @Override
    public String name() {
        return "hdfs-handler";
    }

    /**
     * 已启用键
    */
    @Override
    protected String enabledKey() {
        return "hdfs.enabled";
    }

    /**
     * Software
    */
    @Override
    protected Software software() {
        return Software.HDFS;
    }

    /**
     * 协议
    */
    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    /**
     * 注册拦截器
    */
    @Override
    protected void registerInterceptors() {
        registerAll(FILE_SYSTEM, FS_METHODS);
    }

    /**
     * 构建Target
    */
    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.INTERNAL)
                .software(Software.HDFS)
                .host("hdfs")
                .port(0)
                .path("/")
                .build();
    }
}