package com.chua.common.support.lang.script.marker.listener;


/**
* 脚本源码监听器接口。
*
* <p>提供对脚本源码的变更检测和获取能力，用于脚本引擎动态加载和热更新场景。
* 实现类需要维护脚本源码的状态，并在脚本内容发生变化时标记变更状态。
*
* <h3>使用场景</h3>
* <ul>
*   <li><b>脚本热更新</b> — 检测脚本文件是否发生变化，触发重新编译</li>
*   <li><b>动态编译</b> — 在运行时获取最新的脚本源码进行编译</li>
*   <li><b>脚本管理</b> — 监控脚本的生命周期和状态变更</li>
* </ul>
*
* <h3>示例</h3>
* <pre>{@code
*     Listener listener = new FileScriptListener("transform.groovy");
*     if (listener.isChange()) {
*         String source = listener.getSource();
*         // 重新编译并执行
*     }
* }</pre>
*
* @author CH
* @since 2024/12/12
 */
public interface Listener {

    /**
    * 判断脚本源码是否发生变化。
    *
    * <p>当脚本文件被修改或源码内容与上次获取不一致时返回 {@code true}。
    * 此方法通常用于触发重新编译或重新加载流程。
    *
    * @return 脚本内容已变更返回 {@code true}，否则返回 {@code false}
     */
    boolean isChange();

    /**
    * 获取脚本源码内容。
    *
    * <p>返回当前最新的脚本源码字符串，用于编译或执行。
    * 源码的格式取决于具体的脚本语言（如 Groovy、JavaScript 等）。
    *
    * @return 脚本源码内容的字符串表示，不会返回 {@code null}
     */
    String getSource();
}
