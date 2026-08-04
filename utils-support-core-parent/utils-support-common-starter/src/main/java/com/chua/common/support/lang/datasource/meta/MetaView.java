package com.chua.common.support.lang.datasource.meta;

import com.chua.common.support.lang.datasource.meta.model.ViewDef;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 视图元数据操作接口。
 * <p>
 * 提供视图的查询、创建、修改、删除等链式操作。
 * </p>
 * <p>
 * 使用示例：
 * <pre>{@code
 * // 列出所有视图
 * List<ViewDef> views = engine.meta().view().list();
 *
 * // 查询视图定义
 * ViewDef v = engine.meta().view("v_user").get();
 *
 * // 创建视图
 * ViewDef created = engine.meta().view("v_user")
 *     .definition("select id, name from user where status = 1")
 *     .orReplace()
 *     .comment("有效用户视图")
 *     .execute();
 *
 * // 删除视图
 * boolean dropped = engine.meta().view("v_old").drop();
 * }</pre>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
public interface MetaView {

    /**
     * 列出当前 catalog/schema 下的所有视图。
     *
     * @return 视图定义列表
     */
    List<ViewDef> list();

    /**
     * 获取当前视图的定义。
     *
     * @return 视图定义
     */
    ViewDef get();

    /**
     * 创建视图（链式构建器）。
     *
     * @param viewName 视图名
     * @return 建视图构建器
     */
    ViewCreateBuilder create(String viewName);

    /**
     * 修改视图（链式构建器）。
     *
     * @return 改视图构建器
     */
    ViewAlterBuilder alter();

    /**
     * 删除视图。
     *
     * @return true 删除成功
     */
    boolean drop();
}
