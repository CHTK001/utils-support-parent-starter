package com.chua.common.support.spi.annotations;

import java.lang.annotation.*;

/**
* SPI (服务 提供者 接口) 注解
*
* <p>用于标记服务提供者接口（SPI）的实现类或注入点，
* 实现基于名称或类型的服务发现与依赖注入。
* SPI 机制允许在不修改核心代码的情况下，通过配置扩展功能。
*
* <p>SPI 的核心特性：
* <ul>
*   <li>支持通过别名或全限定类名进行服务定位</li>
*   <li>支持服务排序，控制多个实现时的优先级</li>
*   <li>支持在字段上注入特定的 SPI 实现</li>
*   <li>支持在类上标记为 SPI 实现，并指定其支持的名称</li>
* </ul>
*
* <p>典型应用场景：
* <ul>
*   <li>数据库驱动加载（如 MySQL, PostgreSQL）</li>
*   <li>日志框架适配（如 Logback, Log4j）</li>
*   <li>缓存实现（如 Redis, Ehcache, Caffeine）</li>
*   <li>消息队列适配（如 RabbitMQ, Kafka, ActiveMQ）</li>
* </ul>
*
* <p>使用示例：
* <pre>{@code
* // 定义 SPI 接口
* public interface DataProcessor {
*     void process(String data);
* }
*
* // JSON 实现
* @Spi({"json", "application/json"})
* public class JsonDataProcessor implements DataProcessor {
*     @Override
*     public void process(String data) {
*         // JSON 处理逻辑
*     }
* }
*
* // XML 实现，指定优先级
* @Spi(value = {"xml", "application/xml"}, order = 10)
* public class XmlDataProcessor implements DataProcessor {
*     @Override
*     public void process(String data) {
*         // XML 处理逻辑
*     }
* }
*
* // 仅指定名称和优先级的 CSV 实现
* @Spi(value = "csv", order = 10)
* public class CsvDataProcessor implements DataProcessor {
*     @Override
*     public void process(String data) {
*         // CSV 处理逻辑
*     }
* }
*
* // 在字段上注入指定的 SPI 实现
* public class DataService {
*     @Spi("json")
*     private DataProcessor jsonProcessor;
*
*     @Spi({"xml", "default"})
*     private DataProcessor xmlProcessor;
* }
* }</pre>xml处理器;
* }
* }</pre>
*
* <p>相关的 SPI 扩展注解：
* <ul>
*   <li>SpiDefault - 标记默认的 SPI 实现</li>
*   <li>SpiDescribe - 提供 SPI 实现的描述信息</li>
*   <li>SpiSupport - 标记 SPI 实现所支持的条件或环境</li>
*   <li>SpiParam - 配置 SPI 实现的参数</li>
* </ul>
*
* @author CH
* @since 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.FIELD})
public @interface Spi {

    /**
    * SPI 的名称或别名数组
    *
    * <p>用于标识当前实现类或注入点所对应的服务名称。
    * 支持多个名称，第一个名称通常作为主名称，其余作为别名。
    *
    * <p>名称的常见形式：
    * <ul>
    *   <li>简单的服务名称，如 "redis", "mysql"</li>
    *   <li>带有版本号的名称，如 "redis-v2", "mysql-8.0"</li>
    *   <li>MIME 类型，如 "application/json"</li>
    *   <li>全限定类名，如 "com.mysql.cj.jdbc.Driver"</li>
    * </ul>
    *
    * <p>使用示例：
    * <pre>{@code
    * // 单一名称
    * @Spi("redis")
    *
    * // 多个别名
    * @Spi({"json", "application/json", "text/json"})
    *
    * // 包含全限定类名
    * @Spi({"mysql", "mysql-8.0", "com.mysql.cj.jdbc.Driver"})
    * }</pre>
    *
    * @return SPI 的名称或别名数组
     */
    String[] value() default {};

    /**
    * 服务的优先级顺序
    *
    * <p>当存在多个相同名称的 SPI 实现时，用于决定它们的加载或使用顺序。
    * 数值越大，优先级越高。
    *
    * <p>优先级规则说明：
    * <ul>
    *   <li>数值越大，优先级越高，越优先被加载或使用</li>
    *   <li>相同优先级时，按照加载顺序或字母顺序决定</li>
    *   <li>负数优先级通常用于兜底或默认实现</li>
    * </ul>
    *
    * <p>常见优先级设定：
    * <ul>
    *   <li>高优先级实现：100, 50 等</li>
    *   <li>默认优先级：0</li>
    *   <li>低优先级/兜底实现：-50, -100 等</li>
    * </ul>
    *
    * <p>使用示例：
    * <pre>{@code
    * @Spi(value = "redis", order = 100)   // 高优先级
    * public class RedisCache implements Cache { }
    *
    * @Spi(value = "memory", order = 0)    // 默认优先级
    * public class MemoryCache implements Cache { }
    *
    * @Spi(value = "file", order = -100)   // 低优先级，作为兜底
    * public class FileCache implements Cache { }
    * }</pre> { }
    * }</pre>
    *
    * @return 优先级顺序，默认为 0
     */
    int order() default 0;
}

