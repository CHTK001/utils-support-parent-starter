package com.chua.common.support.network.rpc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
* RPC 事件注解，标记 RPC 服务端中可响应的事件处理方法。
*
* <p>不同于 {@link RpcService} 的面向服务接口的设计（一个接口对应一组业务方法），
* {@code OnRpcEvent} 是面向事件驱动的轻量级 RPC 响应模型。
* 它允许将服务类中的特定方法暴露为事件端点，通过事件名称（路径）进行调用。</p>
*
* <h2>使用场景</h2>
* <ul>
*   <li><b>回调/通知</b>：服务端主动向客户端推送状态变更通知</li>
*   <li><b>事件订阅</b>：客户端订阅特定事件，事件发生时触发服务器端方法执行</li>
*   <li><b>轻量远程调用</b>：不需要完整 RPC 接口定义的简单远程调用</li>
* </ul>
*
* <h2>使用示例</h2>
* <pre>{@code
* public class EventService {
*
*     @OnRpcEvent(value = "user.login", produces = "json")
*     public String onUserLogin(String userId) {
*         System.out.println("User logged in: " + userId);
*         return "{\"status\":\"ok\"}";
*     }
*
*     @OnRpcEvent("order.paid")
*     public void onOrderPaid(Long orderId, String userId) {
*         // 处理订单支付成功事件
*     }
* }
* }</pre>
*
* <p>与服务端实现配合：服务端启动后会扫描已注册 Bean 中的 {@code @OnRpcEvent} 注解，
* 将方法注册到事件路由表中。当收到对应事件路径的请求时，自动匹配并调用注册的方法。</p>
*
* @author CH
* @since 1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnRpcEvent {

    /**
    * 事件路径（名称）数组
    *
    * <p>指定此方法可以响应的事件名称。一个方法可以绑定多个不同的事件路径。
    * 路径通常采用点分隔的风格（类似 Java 包名），例如：</p>
    * <ul>
    *   <li>{@code "user.login"}</li>
    *   <li>{@code "order.created"}</li>
    *   <li>{@code "system.alert.error"}</li>
    * </ul>
    *
    * <p>客户端发送请求时需指定此路径，服务端根据路径匹配到对应方法并执行。</p>
    *
    * @return 事件路径字符串数组
    */
    String[] value();

    /**
    * 响应数据的 MIME 类型
    *
    * <p>指定当方法返回值序列化时采用的格式编码。常见取值：</p>
    * <ul>
    *   <li>{@code "json"} — JSON 格式（默认）</li>
    *   <li>{@code "text"} — 纯文本格式</li>
    *   <li>{@code "xml"} — XML 格式</li>
    * </ul>
    *
    * @return MIME 类型标识
    */
    String produces() default "json";
}
