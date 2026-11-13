package com.chua.common.support.nativeapi;

/**
 * native 能力 SPI 的公共契约。
 *
 * <p>所有 native 能力接口都继承本接口，调用方只依赖 utils 侧契约模块即可编译；
 * 具体实现由 {@code utils-support-native-*} 模块通过 Java SPI（{@code META-INF/services}）
 * 注册，运行期缺失时 {@link NativeServices#get(Class)} 返回 {@code null}，调用方按能力
 * 不可用降级，不再使用反射探测 native 类。</p>
 *
 * @author CH
 * @since 4.0.0.50
 */
public interface NativeService {

    /**
     * 原生能力是否可用。
     *
     * <p>实现方可以在这里触发一次惰性加载；调用方不应假设本方法无副作用。</p>
     *
     * @return 可用返回 {@code true}
     */
    boolean available();
}
