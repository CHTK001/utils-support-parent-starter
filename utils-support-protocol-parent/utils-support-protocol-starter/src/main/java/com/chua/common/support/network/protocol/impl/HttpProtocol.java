package com.chua.common.support.network.protocol.impl;

import com.chua.common.support.network.protocol.AbstractProtocol;
import com.chua.common.support.network.protocol.ProtocolSetting;
import com.chua.common.support.network.protocol.client.BaseProtocolClient;
import com.chua.common.support.network.protocol.server.ProtocolServer;
import com.chua.common.support.core.utils.ClassUtils;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 *  http协议
 * @author CH
 * @since 2025/7/25 9:32
 */
public class HttpProtocol extends AbstractProtocol {
    public HttpProtocol(ProtocolSetting protocolSetting) {
        super(protocolSetting);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends BaseProtocolClient> getClientType() {
        return (Class<? extends BaseProtocolClient>) ClassUtils.forName(
                "com.chua.common.support.network.protocol.client.impl.HttpProtocolClient");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends ProtocolServer> getServerType() {
        return (Class<? extends ProtocolServer>) ClassUtils.forName(
                "com.chua.common.support.network.protocol.server.impl.HttpProtocolServer");
    }
}
