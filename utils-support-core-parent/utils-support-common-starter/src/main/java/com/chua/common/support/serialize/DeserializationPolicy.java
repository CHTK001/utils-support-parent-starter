package com.chua.common.support.serialize;

/**
 * 反序列化类名策略扩展点。
 *
 * <p>所有 JDK / Fury / Kryo 等按类名还原对象图的序列化路径，都会在实例化前把类名交给
 * classpath 上全部 {@code DeserializationPolicy} 实现逐个判定，<strong>任一策略拒绝即拒绝</strong>
 * （AND 语义）。因此第三方插件只能通过新增策略收紧范围，无法放宽框架默认防线，
 * 无需改动任何框架代码即可接入自定义黑名单 / 白名单。</p>
 *
 * <p>注册方式：实现本类并标注 {@link com.chua.common.support.spi.annotations.Spi}，
 * 在 {@code META-INF/extensions/com.chua.common.support.serialize.DeserializationPolicy}
 * 中以 {@code 别名=实现全限定名} 登记。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DeserializationPolicy {

    /**
     * 判断指定类名是否允许被反序列化。
     *
     * @param className 待还原的类全名，已去除数组维度与 {@code L...;} 包装
     * @return {@code false} 表示本策略明确拒绝该类；{@code true} 表示本策略不反对
     */
    boolean allows(String className);
}
