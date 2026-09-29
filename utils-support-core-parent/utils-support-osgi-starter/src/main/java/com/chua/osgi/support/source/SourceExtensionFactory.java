package com.chua.osgi.support.source;

/**
 * 源码扩展点的注册契约。
 *
 * <p>标注文档中声明「一个源码编译产物如何成为 SPI 扩展点」。这是把
 * {@code ServiceProvider} 发现器接入源码热重载的<b>唯一契约</b>：
 * 编译器只负责产出实现类，由本契约把实现类翻译成
 * {@code (spiType, extensionName) → 实例} 三元组。</p>
 *
 * <p><b>为什么不让源码自己声明 SPI 类型</b>：Java 侧用注解声明即可
 * （见 {@link SourceSpi}），但 Groovy 脚本无法定义带注解的类，
 * 只能在脚本返回值里给出类型信息。两者统一收敛到本契约后，
 * 调度方不需要分支判断语言。</p>
 *
 * <p>实现必须是无状态的<b>工厂</b>而非单例持有者——源码会被反复重新编译，
 * 每次编译都产生一批新类，旧实例必须能被整体替换而不影响调用方。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface SourceExtensionFactory {

    /**
     * 创建待注册的扩展实例。
     *
     * @return 扩展实例；返回 {@code null} 表示本次不注册任何扩展点
     */
    Object create();

    /**
     * 取扩展点所属的 SPI 类型。
     *
     * <p>必须是 {@link com.chua.common.support.spi.ServiceProvider} 可索引的类型，
     * 即以接口/抽象类作为注册键。</p>
     *
     * @return SPI 类型，不为 {@code null}
     */
    Class<?> spiType();

    /**
     * 取扩展名。
     *
     * <p>注册表按键统一转大写，故此处大小写不敏感；重复注册同名字段时
     * 后者覆盖前者，故扩展名需在整批源码内唯一。</p>
     *
     * @return 扩展名，不为 {@code null} 且非空白
     */
    String extensionName();
}
