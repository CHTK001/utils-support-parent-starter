package com.chua.common.support.network.rpc;

/**
* RPC 调用统一异常，区分「业务异常」与「传输异常」两类语义。
*
* <p>各协议实现（native/json/dubbo/sofa）调用失败时应优先抛出本异常并标注类型，
* 便于上层（重试、熔断、异常传播）按类型统一决策：</p>
* <ul>
*   <li>{@link #BUSINESS} —— 服务端业务逻辑执行失败（远程方法自身抛错），重试无意义且可能放大副作用</li>
*   <li>{@link #TRANSPORT} —— 网络/序列化等传输层失败（连接断开、超时、报文非法），可安全重试</li>
* </ul>
*
* @author CH
* @since 1.0.0
 */
public class RpcException extends RuntimeException {

    /**
    * 异常类型：业务 / 传输
     */
    public enum Type {
        /**
        * 业务异常：服务端执行业务方法时抛错
         */
        BUSINESS,
        /**
        * 传输异常：网络、连接、序列化、报文等传输层失败
         */
        TRANSPORT
    }

    /**
    * 异常类型
     */
    private final Type type;

    /**
    * 构造器。
    *
    * @param type    异常类型
    * @param message 异常消息
     */
    public RpcException(Type type, String message) {
        super(message);
        this.type = type;
    }

    /**
    * 构造器。
    *
    * @param type    异常类型
    * @param message 异常消息
    * @param cause   根因
     */
    public RpcException(Type type, String message, Throwable cause) {
        super(message, cause);
        this.type = type;
    }

    /**
    * 创建业务异常。
    *
    * @param message 异常消息
    * @return 业务异常实例
     */
    public static RpcException business(String message) {
        return new RpcException(Type.BUSINESS, message);
    }

    /**
    * 创建业务异常（携带根因）。
    *
    * @param message 异常消息
    * @param cause   根因
    * @return 业务异常实例
     */
    public static RpcException business(String message, Throwable cause) {
        return new RpcException(Type.BUSINESS, message, cause);
    }

    /**
    * 创建传输异常。
    *
    * @param message 异常消息
    * @return 传输异常实例
     */
    public static RpcException transport(String message) {
        return new RpcException(Type.TRANSPORT, message);
    }

    /**
    * 创建传输异常（携带根因）。
    *
    * @param message 异常消息
    * @param cause   根因
    * @return 传输异常实例
     */
    public static RpcException transport(String message, Throwable cause) {
        return new RpcException(Type.TRANSPORT, message, cause);
    }

    /**
    * 获取异常类型。
    *
    * @return 异常类型
     */
    public Type getType() {
        return type;
    }

    /**
    * 是否业务异常。
    *
    * @return 业务异常返回 {@code true}
     */
    public boolean isBusiness() {
        return type == Type.BUSINESS;
    }

    /**
    * 是否传输异常。
    *
    * @return 传输异常返回 {@code true}
     */
    public boolean isTransport() {
        return type == Type.TRANSPORT;
    }
}
