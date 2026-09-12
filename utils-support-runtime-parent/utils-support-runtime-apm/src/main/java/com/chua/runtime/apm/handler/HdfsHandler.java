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

    @Override
    /** 名称 */
    public String name() {
        return "hdfs-handler";
    }

    @Override
    /** 已启用键 */
    protected String enabledKey() {
        return "hdfs.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.HDFS;
    }

    @Override
    /** 协议 */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册拦截器 */
    protected void registerInterceptors() {
        registerAll(FILE_SYSTEM, FS_METHODS);
    }

    @Override
    /** 构建Target */
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