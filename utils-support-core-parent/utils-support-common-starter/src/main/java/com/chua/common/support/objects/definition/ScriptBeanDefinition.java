package com.chua.common.support.objects.definition;

import com.chua.common.support.lang.script.marker.ScriptMarker;
import com.chua.common.support.lang.script.marker.listener.Listener;

/**
 * 通用脚本 Bean 定义，内部持有 {@link ScriptMarker} 和 {@link Listener}。
 * <p>通过 {@link ScriptMarker#createObject(Listener, ClassLoader, Object...)} 动态创建脚本对象实例。</p>
 * <p>与 {@link GroovyScriptDefinition} 不同，此类适用于任意脚本引擎，不绑定特定的脚本语言。</p>
 *
 * @author CH
 * @since 2026/07/29
 */
public class ScriptBeanDefinition extends AbstractScriptDefinition {

    /**
     * 构造通用脚本 Bean 定义。
     *
     * @param name         Bean 名称
     * @param scriptMarker 脚本标记器，负责创建脚本对象
     * @param listener     脚本监听器，感知脚本变更
     */
    public ScriptBeanDefinition(String name, ScriptMarker scriptMarker, Listener listener) {
        setName(name);
        setScriptMarker(scriptMarker);
        setListener(listener);
    }

    /**
     * 构造通用脚本 Bean 定义，指定 Bean 类型。
     *
     * @param name         Bean 名称
     * @param beanClass    Bean 类型
     * @param scriptMarker 脚本标记器
     * @param listener     脚本监听器
     */
    public ScriptBeanDefinition(String name, Class<?> beanClass, ScriptMarker scriptMarker, Listener listener) {
        setName(name);
        setBeanClass(beanClass);
        setScriptMarker(scriptMarker);
        setListener(listener);
    }
}