package com.chua.common.support.function;

import org.jspecify.annotations.NullUnmarked;

/**
 * 初始化感知接口，用于在对象属性设置完成后执行初始化操作。
 *
 * <p>实现此接口的类可以在所有属性设置完成后执行自定义的初始化逻辑，
 * 这类似于 Spring 的 {@link org.springframework.beans.factory.InitializingBean} 接口，
 * 但不依赖于 Spring 框架。</p>
 *
 * <p>典型使用场景：</p>
 * <ul>
 *   <li>验证属性配置的正确性</li>
 *   <li>初始化依赖于其他属性的字段</li>
 *   <li>建立与外部资源的连接</li>
 *   <li>执行启动时的准备工作</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * public class MyService implements InitializingAware {
 *     private String host;
 *     private int port;
 *
 *     @Override
 *     public void afterPropertiesSet() {
 *         // 在属性设置完成后初始化连接
 *         this.connection = createConnection(host, port);
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 1.0
 * @see org.springframework.beans.factory.InitializingBean
 */
@NullUnmarked
public interface InitializingAware {

    /**
     * 初始化回调方法。
     *
     * <p>该方法在所有属性设置完成后被调用，用于执行初始化操作。
     * 实现类可以在此方法中：</p>
     * <ul>
     *   <li>验证配置参数</li>
     *   <li>初始化内部状态</li>
     *   <li>建立资源连接</li>
     *   <li>执行预处理逻辑</li>
     * </ul>
     *
     * <p>注意：该方法应该在对象完全构造后调用，确保所有依赖已就绪。</p>
     */
    void afterPropertiesSet();
}
