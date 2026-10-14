package com.chua.datasource.support.middleware;

/**
 * 中间件功能未开放：宿主按 {@code type} 找不到 SPI 实现时抛出。
 *
 * <p>这不是系统错误，而是「该类型需要安装对应能力包」。宿主把它转成业务提示
 * （如「Kafka 功能未开放，请安装 kafka 能力包」），<b>不落 500、不打 ERROR 栈</b>，
 * 前端也不需要为每种中间件单独适配错误分支。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MiddlewareNotInstalledException extends RuntimeException {

    /** 数据源类型标识 */
    private final String type;

    /**
     * 构造。
     *
     * @param type 数据源类型标识，如 {@code kafka}
     */
    public MiddlewareNotInstalledException(String type) {
        super("数据源类型 " + type + " 的功能未开放：未安装对应的中间件能力实现（SPI）");
        this.type = type;
    }

    /**
     * @return 数据源类型标识
     */
    public String type() {
        return type;
    }
}
