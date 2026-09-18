package com.chua.common.support.scatter;

/**
 * TCP scatter 构建器。
 *
 * @author CH
 * @since 4.0.0.42
*/
public class TcpScatterBuilder extends ScatterBuilder<TcpScatterBuilder> {

    private static final String PROTOCOL_TCP = "tcp";

    /**
     * 构造方法，创建 TcpScatterBuilder 实例。
     */
    public TcpScatterBuilder() {
        super(PROTOCOL_TCP);
    }

    /**
     * 构造方法，创建 TcpScatterBuilder 实例。
     *
     * @param setting 方法入参 setting
     */
    public TcpScatterBuilder(ScatterSetting setting) {
        super(PROTOCOL_TCP, setting);
    }
}
