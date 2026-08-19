package com.chua.ssh.support.client;

import com.chua.common.support.network.tunnel.Tunnel;
import com.chua.common.support.network.tunnel.TunnelInfo;
import com.chua.common.support.network.tunnel.TunnelStatus;
import com.chua.common.support.network.tunnel.TunnelType;
import com.chua.common.support.network.tunnel.TunnelException;

import java.util.function.Consumer;

/**
 * SSH 隧道实现，支持正反向隧道和动态 SOCKS5 隧道。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class SshTunnel implements Tunnel {

    /**
     * ssh Client
     */
    private final SshClient sshClient;
    /**
     * definition
     */
    private final SshClient.TunnelDefinition definition;
    /**
     * bind Address
     */
    private final String bindAddress;

    /**
     * tracker
     */
    private AutoCloseable tracker;
    /**
     * open
     */
    private volatile boolean open;
    /**
     * callback
     */
    private Consumer<TunnelInfo> callback;
    /**
     * actual Port
     */
    private int actualPort = -1;

    /**
     * 创建 SshTunnel 实例
     * @param sshClient sshClient
     * @param SshClient SshClient
     * @param definition definition
     * @param String String
     */
    public SshTunnel(SshClient sshClient, SshClient.TunnelDefinition definition, String bindAddress) {
        this.sshClient = sshClient;
        this.definition = definition;
        this.bindAddress = bindAddress != null ? bindAddress : "127.0.0.1";
    }

    @Override
    /** 打开 */
    public int open() {
        if (open) {
            return actualPort;
        }

        try {
            SshClient.ForwardOperation forward = sshClient.forward();
            forward.bindAddress(bindAddress);

            switch (definition.getType()) {
                case LOCAL -> forward.local(definition.getLocalPort(), definition.getRemoteHost(), definition.getRemotePort());
                case REMOTE -> forward.remote(definition.getRemotePort(), definition.getRemoteHost(), definition.getLocalPort());
                case DYNAMIC -> forward.dynamic(definition.getLocalPort());
            }

            tracker = forward.start();
            this.actualPort = forward.getActualPort();
            this.open = true;

            TunnelInfo info = TunnelInfo.of(
                    actualPort,
                    TunnelStatus.OPEN,
                    toTunnelType(definition.getType()),
                    bindAddress,
                    definition.getRemoteHost(),
                    definition.getRemotePort()
            );
            if (callback != null) {
                callback.accept(info);
            }

            return actualPort;
        } catch (Exception e) {
            TunnelInfo info = TunnelInfo.error(TunnelStatus.ERROR, e.getMessage());
            if (callback != null) {
                callback.accept(info);
            }
            throw new TunnelException("SSH 隧道开启失败: " + definition.getType(), e);
        }
    }

    @Override
    /** 关闭 */
    public void close() {
        if (!open) {
            return;
        }

        try {
            if (tracker != null) {
                tracker.close();
            }
            this.open = false;

            TunnelInfo info = TunnelInfo.of(
                    actualPort,
                    TunnelStatus.CLOSED,
                    toTunnelType(definition.getType()),
                    bindAddress,
                    definition.getRemoteHost(),
                    definition.getRemotePort()
            );
            if (callback != null) {
                callback.accept(info);
            }
        } catch (Exception e) {
            throw new TunnelException("SSH 隧道关闭失败", e);
        }
    }

    @Override
    /** 获取Info */
    public TunnelInfo getInfo() {
        return TunnelInfo.of(
                actualPort,
                open ? TunnelStatus.OPEN : TunnelStatus.CLOSED,
                toTunnelType(definition.getType()),
                bindAddress,
                definition.getRemoteHost(),
                definition.getRemotePort()
        );
    }

    @Override
    /** OnInfo */
    public void onInfo(Consumer<TunnelInfo> callback) {
        this.callback = callback;
    }

    @Override
    /** 是否打开 */
    public boolean isOpen() {
        return open;
    }

    /** ToTunnelType */
    private static TunnelType toTunnelType(SshClient.TunnelDefinition.Type type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case LOCAL -> TunnelType.LOCAL;
            case REMOTE -> TunnelType.REMOTE;
            case DYNAMIC -> TunnelType.DYNAMIC;
        };
    }
}
