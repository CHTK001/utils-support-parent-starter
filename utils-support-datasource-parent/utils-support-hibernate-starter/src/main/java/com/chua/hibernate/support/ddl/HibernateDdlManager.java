package com.chua.hibernate.support.ddl;

import com.chua.common.support.lang.datasource.engine.ddl.DslManager;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.ddl.DefaultDslManager;

/**
 * DDL 管理器的 {@code hibernate} 命名实现。
 *
 * <p>本类不引入 Hibernate ORM，也不重复实现表结构读取与 DDL 生成：
 * 全部能力继承自 {@link DefaultDslManager}（JDBC {@code DatabaseMetaData} 读结构
 * + 框架注入的 {@code Dialect} 生成方言感知语句），因此注册与解析行为完全一致。</p>
 *
 * <p>保留本类的原因：{@code utils-support-hibernate-starter} 历史上以
 * {@code hibernate} 别名对外提供 DDL 管理能力，已有调用方按该别名显式解析，
 * 需要保持二进制与别名兼容。</p>
 *
 * <p>方言由框架注入（{@code DialectAware}），不再自行从 JDBC URL 嗅探：
 * 嗅探需要遍历全部已注册方言逐一比对 URL 前缀，既慢，又会在方言 URL 模板
 * 调整后静默失配。</p>
 *
 * <p>注册文件：
 * {@code META-INF/extensions/com.chua.common.support.lang.datasource.engine.ddl.DslManager}，
 * 内容为 {@code hibernate=com.chua.hibernate.support.ddl.HibernateDdlManager}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see DefaultDslManager
 */
@Spi(value = DslManager.SPI_NAME, order = -50)
public class HibernateDdlManager extends DefaultDslManager {

    @Override
    /**
     * 类型
     *
     * @return 类型名称
     */
    public String type() {
        return "hibernate";
    }
}
