package com.chua.common.support.lang.script.marker;

import com.chua.common.support.lang.script.marker.listener.Listener;
import lombok.extern.slf4j.Slf4j;

/**
* 脚本标记器抽象基类。
*
* <p>为 {@link ScriptMarker} 接口提供默认实现骨架，简化具体脚本引擎的实现。
* 子类只需实现核心的 {@link #createObject} 和 {@link #getType} 方法。</p>
*
* <h3>继承指南</h3>
* <ul>
*   <li>所有具体脚本引擎的标记器应继承此类</li>
*   <li>重写 {@link #createObject} 完成脚本编译和对象创建</li>
*   <li>重写 {@link #getType} 返回最近编译的类类型</li>
* </ul>
*
* @author CH
* @since 2024/12/12
* @see ScriptMarker
* @see Listener
 */
@Slf4j
public abstract class AbstractScriptMarker implements ScriptMarker {

    /**
    * 最近一次编译生成的脚本类类型。
     */
    protected volatile Class<?> type;

    @Override
    public Class<?> getType() {
        return type;
    }
}
