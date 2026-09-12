package com.chua.common.support.concurrent.pool;


/**
* 对象工厂接口
*
* <p>负责创建、初始化、销毁和验证池化对象。对象池通过此接口与具体对象类型解耦。
*
* <p>对象生命周期：
* <pre>
*   create()     → 创建原始对象
*   initObject() → 初始化对象（设置属性、建立连接、执行 SQL 等）
*   validate()   → 验证对象是否可用（借出/归还时检查）
*   destroy()    → 销毁对象（释放资源）
* </pre>
*
* <p>实现类需保证线程安全，因为多个线程可能同时调用 create()。
*
* @param <T> 池化对象类型
* @author CH
* @since 2026/07/16
 */
public interface ObjectFactory<T> {

    /**
    * 创建新对象
    *
    * <p>当池中无可用对象且未达到最大容量时调用。
    * 仅创建原始对象，不包含业务初始化逻辑。
    *
    * @return 新创建的对象
    * @throws Exception 创建失败时抛出
     */
    T create() throws Exception;

    /**
    * 初始化对象
    *
    * <p>在 create() 之后、对象被借出之前调用。
    * 用于执行对象的个性化初始化逻辑，如：
    * <ul>
    *   <li>数据库连接：设置 autoCommit、schema、执行 SET 语句</li>
    *   <li>HTTP 客户端：设置超时、代理、默认 Header</li>
    *   <li>Worker 线程：绑定 ThreadLocal、初始化上下文</li>
    *   <li>Socket：设置 keepAlive、timeout、编码</li>
    * </ul>
    *
    * <p>默认实现为空操作（no-op），子类可覆写。
    * 若初始化失败，对象将被销毁，不会进入池中。
    *
    * @param object 待初始化的对象
    * @throws Exception 初始化失败时抛出（对象将被销毁）
     */
    default void initObject(T object) throws Exception {
        // 默认空实现，子类按需覆写
    }

    /**
    * 销毁对象
    *
    * <p>对象被移除出池时调用（空闲超时、归还时验证失败、池关闭等）。
    * 实现类应释放对象持有的资源（关闭连接、释放内存等）。
    *
    * @param object 待销毁的对象
     */
    void destroy(T object);

    /**
    * 验证对象是否可用
    *
    * <p>在 borrow() 和 returnObject() 时调用（取决于配置）。
    * 返回 false 表示对象已失效，应被销毁。
    *
    * @param object 待验证的对象
    * @return true 表示可用，false 表示已失效
     */
    boolean validate(T object);
}
