package com.chua.common.support.scatter;

/**
 * TCP scatter 构建器。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class TcpScatterBuilder extends ScatterBuilder<TcpScatterBuilder> {

    private static final String PROTOCOL_TCP = "tcp";

    public TcpScatterBuilder() {
        super(PROTOCOL_TCP);
    }

    public TcpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_TCP, setting);
    }
}
