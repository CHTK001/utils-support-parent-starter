package com.chua.common.support.objects.definition;

import com.chua.common.support.lang.script.marker.ScriptMarker;
import com.chua.common.support.lang.script.marker.listener.Listener;

import java.nio.file.Path;

/**
* 脚本 Bean 定义接口。
*
* <p>扩展 {@link BeanDefinition}，增加脚本引擎相关的元数据能力，
* 包括脚本标记器、源码监听器和脚本类加载器生命周期管理。</p>
*
* <p>核心职责：
* <ul>
*   <li>关联 {@link ScriptMarker}，负责脚本源码编译和对象创建</li>
*   <li>关联 {@link Listener}，负责脚本源码变更检测</li>
*   <li>管理脚本类加载器，热重载时销毁旧 ClassLoader 释放 Metaspace</li>
* </ul></p>
*
* @author CH
* @since 4.0.0.42
* @see BeanDefinition
* @see ScriptMarker
* @see Listener
 */
public interface ScriptDefinition extends BeanDefinition {

    /**
    * 空的脚本定义实例。
     */
    ScriptDefinition EMPTY_SCRIPT_DEFINITION = new AbstractScriptDefinition() {
        @Override
        /** 获取名称 */
        public String getName() {
            return "";
        }

        @Override
        /** 获取类型 */
        public String getType() {
            return "";
        }

        @Override
        /** 获取script类加载 */
        public ClassLoader getScriptClassLoader() {
            return null;
        }

        @Override
        /** 获取script记号笔 */
        public ScriptMarker getScriptMarker() {
            return null;
        }

        @Override
        /** 获取监听器 */
        public Listener getListener() {
            return null;
        }
    };

    /**
    * 获取脚本标记器。
    *
    * <p>脚本标记器负责将脚本源码编译为 Java Class 并创建对象实例。</p>
    *
    * @return 脚本标记器实例
     */
    ScriptMarker getScriptMarker();

    /**
    * 获取脚本源码监听器。
    *
    * <p>监听器负责检测脚本源码是否发生变化，触发热重载。</p>
    *
    * @return 脚本源码监听器实例
     */
    Listener getListener();

    /**
    * 获取脚本编译使用的类加载器。
    *
    * <return>脚本类加载器，未编译时返回 null</return>
     */
    ClassLoader getScriptClassLoader();

    /**
    * 设置脚本编译使用的类加载器。
    *
    * @param classLoader 脚本类加载器
     */
    void setScriptClassLoader(ClassLoader classLoader);
}
