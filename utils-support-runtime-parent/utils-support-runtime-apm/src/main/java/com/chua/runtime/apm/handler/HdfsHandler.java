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

    private static final String FILE_SYSTEM = "org/apache/hadoop/fs/FileSystem";
    private static final String[] FS_METHODS = {"open", "create", "delete", "rename", "listStatus", "mkdirs", "exists", "getFileStatus"};

    @Override
    public String name() {
        return "hdfs-handler";
    }

    @Override
    protected String enabledKey() {
        return "hdfs.enabled";
    }

    @Override
    protected Software software() {
        return Software.HDFS;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.INTERNAL;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(FILE_SYSTEM, FS_METHODS);
    }

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