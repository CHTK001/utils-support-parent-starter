package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * HDFS Handler — intercepts Hadoop FileSystem operations.
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HdfsHandler extends AbstractAppHandler {

    /**
     * file system
     */
    private static final String FILE_SYSTEM = "org/apache/hadoop/fs/FileSystem";
    /**
     * fs methods
     */
    private static final String[] FS_METHODS = {"open", "create", "delete", "rename", "listStatus", "mkdirs", "exists", "getFileStatus"};

    @Override
    /** Name */
    public String name() {
        return "hdfs-handler";
    }

    @Override
    /** EnabledKey */
    protected String enabledKey() {
        return "hdfs.enabled";
    }

    @Override
    /** Software */
    protected Software software() {
        return Software.HDFS;
    }

    @Override
    /** Protocol */
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    /** 注册Interceptors */
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