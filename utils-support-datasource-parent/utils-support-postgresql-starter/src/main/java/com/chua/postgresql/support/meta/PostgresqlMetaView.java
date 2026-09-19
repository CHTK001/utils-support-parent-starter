package com.chua.postgresql.support.meta;

import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.lang.datasource.meta.ViewAlterBuilder;
import com.chua.common.support.lang.datasource.meta.ViewCreateBuilder;
import com.chua.common.support.lang.datasource.meta.model.ViewDef;
import com.chua.datasource.support.meta.AbstractMetaData;
import com.chua.datasource.support.meta.AbstractMetaView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL 视图元数据操作。
 * <p>
 * 读取路径为 {@code information_schema.views}（标准视图定义、可更新性、检查选项）
 * 联 {@code pg_class} 取 {@code obj_description} 作为视图注释，
 * 以 {@code table_schema = COALESCE(?, current_schema())}（可选追加视图名）过滤，全部为绑定参数。
 * </p>
 * <p>
 * 能力边界：
 * <ul>
 *   <li>物化视图在 {@code information_schema.views} 中不出现，且 {@link ViewDef} 没有区分物化视图的字段，
 *       故本类只覆盖普通视图。</li>
 *   <li>PostgreSQL 的 {@code WITH LOCAL CHECK OPTION} 语法兼容 SQL 标准但实际按 CASCADED 执行，
 *       读取时原样回显字典值，写入时按用户选择原样下发。</li>
 *   <li>可更新性是结构推导结果，PostgreSQL 没有对应建视图子句，{@code updatable()} 显式拒绝而非静默忽略。</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PostgresqlMetaView extends AbstractMetaView {

    /**
     * 视图查询：模式名绑定，视图名按需追加绑定条件。
     */
    private static final String VIEW_SQL =
            "SELECT current_database() AS table_catalog, v.table_schema, v.table_name, v.view_definition,"
                    + " v.is_updatable, v.check_option,"
                    + " pg_catalog.obj_description(c.oid, 'pg_class') AS table_comment"
                    + " FROM information_schema.views v"
                    + " JOIN pg_catalog.pg_namespace n ON n.nspname = v.table_schema"
                    + " JOIN pg_catalog.pg_class c ON c.relname = v.table_name AND c.relnamespace = n.oid"
                    + " WHERE v.table_schema = COALESCE(?, current_schema())";

    /**
     * 构造方法（无视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected PostgresqlMetaView(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     * @param viewName 视图名
     */
    protected PostgresqlMetaView(AbstractMetaData metaData, Engine engine, String viewName) {
        super(metaData, engine, viewName);
    }

    /**
     * 列出当前模式下的所有视图。
     *
     * @return 视图定义列表
     * @throws IllegalStateException 查询失败
     */
    @Override
    public List<ViewDef> list() {
        return readViews(null);
    }

    /**
     * 获取当前上下文视图的定义。
     *
     * @return 视图定义；视图不存在时返回 {@code null}
     * @throws IllegalStateException 未指定视图名或查询失败
     */
    @Override
    public ViewDef get() {
        if (viewName == null) {
            throw new IllegalStateException("未指定视图名，请先调用 meta().view(String)");
        }
        List<ViewDef> views = readViews(viewName);
        return views.isEmpty() ? null : views.get(0);
    }

    @Override
    public ViewCreateBuilder create(String viewName) {
        return new PostgresViewCreateBuilder(this, viewName);
    }

    @Override
    public ViewAlterBuilder alter() {
        return new PostgresViewAlterBuilder(this);
    }

    /**
     * 删除当前上下文视图。
     *
     * @return 是否成功
     * @throws IllegalStateException 未指定视图名或执行失败
     */
    @Override
    public boolean drop() {
        if (viewName == null) {
            throw new IllegalStateException("未指定视图名");
        }
        return PostgresqlMetaData.execute(metaData, "删除视图 " + viewName,
                "DROP VIEW IF EXISTS " + PostgresqlMetaData.quote(viewName), PostgresqlMetaData.args());
    }

    /**
     * 按视图名读取视图定义。
     *
     * @param name 视图名过滤，{@code null} 表示当前模式全部视图
     * @return 视图定义列表
     * @throws IllegalStateException 查询失败
     */
    private List<ViewDef> readViews(String name) {
        String sql = name == null ? VIEW_SQL + " ORDER BY v.table_name" : VIEW_SQL + " AND v.table_name = ?";
        return PostgresqlMetaData.query(metaData, "查询视图" + (name == null ? "" : " " + name), sql,
                PostgresqlMetaData.args(PostgresqlMetaData.resolveSchema(metaData), name),
                PostgresqlMetaView::mapView);
    }

    /**
     * 视图结果集映射，逐列对应 {@link ViewDef} 属性。
     *
     * @param rs 结果集当前行
     * @return 视图定义
     * @throws SQLException 读取失败
     */
    private static ViewDef mapView(ResultSet rs) throws SQLException {
        ViewDef def = new ViewDef();
        def.setName(rs.getString("table_name"));
        def.setCatalog(PostgresqlMetaData.trimToNull(rs.getString("table_catalog")));
        def.setSchema(rs.getString("table_schema"));
        def.setDefinition(PostgresqlMetaData.trimToNull(rs.getString("view_definition")));
        def.setUpdatable("YES".equalsIgnoreCase(rs.getString("is_updatable")));
        def.setComment(PostgresqlMetaData.trimToNull(rs.getString("table_comment")));
        def.setCheckOption(PostgresqlMetaData.trimToNull(rs.getString("check_option")));
        return def;
    }

    /**
     * 把检查选项翻译为 PostgreSQL 子句。
     *
     * @param checkOption 检查选项
     * @return {@code WITH CASCADED CHECK OPTION} / {@code WITH LOCAL CHECK OPTION}，{@code null} 表示无需子句
     */
    private static String checkOptionClause(String checkOption) {
        String value = PostgresqlMetaData.trimToNull(checkOption);
        if (value == null || "NONE".equalsIgnoreCase(value)) {
            return null;
        }
        String upper = value.toUpperCase();
        return switch (upper) {
            case "CASCADE", "CASCADED" -> "WITH CASCADED CHECK OPTION";
            case "LOCAL" -> "WITH LOCAL CHECK OPTION";
            default -> throw new IllegalArgumentException(
                    "PostgreSQL 视图检查选项仅支持 NONE / CASCADED / LOCAL，实际为: " + checkOption);
        };
    }

    /**
     * PostgreSQL 建视图链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresViewCreateBuilder implements ViewCreateBuilder {

        /**
         * 所属视图元数据入口
         */
        private final PostgresqlMetaView metaView;
        /**
         * 视图名
         */
        private final String viewName;
        /**
         * SELECT 定义
         */
        private String definition;
        /**
         * 是否使用 OR REPLACE
         */
        private boolean orReplace;
        /**
         * 注释
         */
        private String comment;
        /**
         * 检查选项
         */
        private String checkOption;

        PostgresViewCreateBuilder(PostgresqlMetaView metaView, String viewName) {
            this.metaView = metaView;
            this.viewName = viewName;
        }

        @Override
        public ViewCreateBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        public ViewCreateBuilder orReplace() {
            this.orReplace = true;
            return this;
        }

        @Override
        public ViewCreateBuilder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        public ViewCreateBuilder updatable() {
            throw new UnsupportedOperationException("PostgreSQL 视图可更新性由 SELECT 结构自动判定，无对应子句");
        }

        @Override
        public ViewCreateBuilder checkOption(String checkOption) {
            this.checkOption = checkOption;
            return this;
        }

        /**
         * 执行建视图语句（注释用 {@code COMMENT ON VIEW} 真实下发），并以字典读回结果。
         *
         * @return 落库后的视图定义
         * @throws IllegalStateException 定义为空或执行失败
         */
        @Override
        public ViewDef execute() {
            if (definition == null || definition.isEmpty()) {
                throw new IllegalStateException("视图定义不能为空");
            }
            StringBuilder sql = new StringBuilder(orReplace ? "CREATE OR REPLACE VIEW " : "CREATE VIEW ")
                    .append(PostgresqlMetaData.quote(viewName)).append(" AS ").append(definition);
            String clause = checkOptionClause(checkOption);
            if (clause != null) {
                sql.append(' ').append(clause);
            }
            PostgresqlMetaData.execute(metaView.metaData, "创建视图 " + viewName, sql.toString(),
                    PostgresqlMetaData.args());
            if (comment != null && !comment.isEmpty()) {
                PostgresqlMetaData.execute(metaView.metaData, "注释视图 " + viewName,
                        "COMMENT ON VIEW " + PostgresqlMetaData.quote(viewName) + " IS '"
                                + PostgresqlMetaData.escapeSql(comment) + "'", PostgresqlMetaData.args());
            }
            return metaView.reload(viewName);
        }
    }

    /**
     * PostgreSQL 改视图链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class PostgresViewAlterBuilder implements ViewAlterBuilder {

        /**
         * 所属视图元数据入口
         */
        private final PostgresqlMetaView metaView;
        /**
         * 新的 SELECT 定义
         */
        private String definition;
        /**
         * 新视图名
         */
        private String newName;

        PostgresViewAlterBuilder(PostgresqlMetaView metaView) {
            this.metaView = metaView;
        }

        @Override
        public ViewAlterBuilder definition(String definition) {
            this.definition = definition;
            return this;
        }

        @Override
        public ViewAlterBuilder renameTo(String newName) {
            this.newName = newName;
            return this;
        }

        /**
         * 执行变更：先按需 {@code ALTER VIEW ... RENAME TO}，再按需 {@code CREATE OR REPLACE VIEW}。
         *
         * @return 变更后的视图定义
         * @throws IllegalStateException 未指定视图名、无任何变更或执行失败
         */
        @Override
        public ViewDef execute() {
            if (metaView.viewName == null) {
                throw new IllegalStateException("未指定视图名，请先调用 meta().view(String)");
            }
            if ((definition == null || definition.isEmpty()) && (newName == null || newName.isEmpty())) {
                throw new IllegalStateException("没有需要执行的变更");
            }
            String target = metaView.viewName;
            if (newName != null && !newName.isEmpty()) {
                PostgresqlMetaData.execute(metaView.metaData, "重命名视图 " + target,
                        "ALTER VIEW " + PostgresqlMetaData.quote(target) + " RENAME TO "
                                + PostgresqlMetaData.quote(newName), PostgresqlMetaData.args());
                target = newName;
                metaView.viewName = newName;
            }
            if (definition != null && !definition.isEmpty()) {
                PostgresqlMetaData.execute(metaView.metaData, "替换视图 " + target,
                        "CREATE OR REPLACE VIEW " + PostgresqlMetaData.quote(target) + " AS " + definition,
                        PostgresqlMetaData.args());
            }
            return metaView.reload(target);
        }
    }

    /**
     * 按视图名重读字典，供构建器回填返回值。
     *
     * @param name 视图名
     * @return 视图定义，不存在时为 {@code null}
     */
    private ViewDef reload(String name) {
        List<ViewDef> views = readViews(name);
        return views.isEmpty() ? null : views.get(0);
    }
}
