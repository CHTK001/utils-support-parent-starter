package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
 * SPI 默认实现注解
 *
 * <p> 本注解用于标记 SPI (Service Provider Interface) 的默认实现。
 * SPI 是一种服务提供接口机制，用于在运行时动态发现和加载接口的实现类。
 *
 * <p> SPI (Service Provider Interface) 是 Java 提供的一种服务发现机制。
 * 通过 SPI 机制，框架可以解耦接口定义与具体实现，提高系统的可扩展性。
 *
 * <p> 主要特性：
 * <ul>
 *   <li> 支持在类上标记为接口的默认实现 </li>
 *   <li> 支持在字段上标记为默认注入的实现 </li>
 *   <li> 与 {@link Spi} 注解配合使用，指定实现名称 </li>
 *   <li> 简化服务发现和加载过程 </li>
 *   <li> 提高系统的可扩展性和灵活性 </li>
 * </ul>
 *
 * <p> 使用示例：
 * <pre>{@code
 * // 定义 SPI 接口
 * public interface MessageService {
 *     void sendMessage(String message);
 * }
 *
 * // 默认实现 (控制台)
 * @SpiDefault
 * @Spi("console")
 * public class ConsoleMessageService implements MessageService {
 *     @Override
 *     public void sendMessage(String message) {
 *         System.out.println("Console: " + message);
 *     }
 * }
 *
 * // 其他实现 (邮件)
 * @Spi("email")
 * public class EmailMessageService implements MessageService {
 *     @Override
 *     public void sendMessage(String message) {
 *         // 发送邮件逻辑
 *     }
 * }
 *
 * // 注入默认实现
 * public class NotificationService {
 *     @SpiDefault
 *     @Spi("sms")
 *     private MessageService defaultMessageService;
 * }
 * }</pre>募 消息服务 默认消息服务;
 * }
 * }</pre>
 *
 * <p> 注意事项：
 * <ul>
 *   <li> 同一个接口的 SPI 默认实现只能有一个，多个会导致冲突 </li>
 *   <li> 通常与 {@link Spi} 注解结合使用，以提供具体的实现标识 </li>
 *   <li> 框架在初始化时会自动扫描并注册带有此注解的实现类 </li>
 *   <li> 可以通过外部配置覆盖默认的 SPI 实现 </li>
 * </ul>
 *
 * <p> 相关注解：
 * <ul>
 *   <li> {@link Spi} 用于指定 SPI 实现的名称或标识 </li>
 * </ul>
 *
 * @author CH
 * @since 2024-01-01
 * @版本 1.0.0
 * @see Spi
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface SpiDefault {
}

