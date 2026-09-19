package com.chua.mysql.support.meta;

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
 * MySQL 视图元数据操作。
 * <p>
 * 读取路径为 {@code INFORMATION_SCHEMA.VIEWS} 左联 {@code INFORMATION_SCHEMA.TABLES}：
 * 前者提供规范化定义、可更新性、检查选项，后者提供视图注释在字典中的实际存放位置。
 * 库名与视图名均为绑定参数，库名参数为 {@code null} 时由 SQL 侧 {@code DATABASE()} 收敛到当前会话默认库，
 * 不再使用 {@code SHOW CREATE VIEW} 那种只能拼字符串的读取方式。
 * </p>
 * <p>
 * 能力边界：MySQL 的 {@code CREATE VIEW} / {@code ALTER VIEW} 语法均不带视图注释子句，
 * 因此写入路径遇到 {@code comment(String)} 显式抛 {@link UnsupportedOperationException}，
 * 而不是静默丢弃；读取路径的 {@code comment} 来自 {@code TABLES.TABLE_COMMENT}，
 * MySQL 未给视图写注释时该值为空，保持 {@code null}。
 * {@code updatable()} 同理：MySQL 的可更新性由 SELECT 结构自动判定，没有可下发的子句。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MysqlMetaView extends AbstractMetaView {

    /**
     * 视图查询：库名绑定，视图名按需追加绑定条件。
     */
    private static final String VIEW_SQL =
            "SELECT v.TABLE_CATALOG, v.TABLE_SCHEMA, v.TABLE_NAME, v.VIEW_DEFINITION, v.IS_UPDATABLE,"
                    + " v.CHECK_OPTION, t.TABLE_COMMENT"
                    + " FROM INFORMATION_SCHEMA.VIEWS v"
                    + " LEFT JOIN INFORMATION_SCHEMA.TABLES t"
                    + " ON t.TABLE_SCHEMA = v.TABLE_SCHEMA AND t.TABLE_NAME = v.TABLE_NAME"
                    + " WHERE v.TABLE_SCHEMA = COALESCE(?, DATABASE())";

    /**
     * 构造方法（无视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     */
    protected MysqlMetaView(AbstractMetaData metaData, Engine engine) {
        super(metaData, engine);
    }

    /**
     * 构造方法（带视图名上下文）。
     *
     * @param metaData 元数据入口
     * @param engine   引擎实例
     * @param viewName 视图名
     */
    protected MysqlMetaView(AbstractMetaData metaData, Engine engine, String viewName) {
        super(metaData, engine, viewName);
    }

    /**
     * 列出当前库下的所有视图。
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
        return new MysqlViewCreateBuilder(this, viewName);
    }

    @Override
    public ViewAlterBuilder alter() {
        return new MysqlViewAlterBuilder(this);
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
        return MysqlMetaData.execute(engine, "删除视图 " + viewName,
                "DROP VIEW IF EXISTS " + MysqlMetaData.quote(viewName), List.of());
    }

    /**
     * 按视图名读取视图定义。
     *
     * @param name 视图名过滤，{@code null} 表示当前库全部视图
     * @return 视图定义列表
     * @throws IllegalStateException 查询失败
     */
    private List<ViewDef> readViews(String name) {
        String sql = name == null ? VIEW_SQL + " ORDER BY v.TABLE_NAME" : VIEW_SQL + " AND v.TABLE_NAME = ?";
        return MysqlMetaData.query(engine, "查询视图" + (name == null ? "" : " " + name), sql,
                MysqlMetaData.args(MysqlMetaData.resolveSchema(metaData), name), MysqlMetaView::mapView);
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
        def.setName(rs.getString("TABLE_NAME"));
        def.setCatalog(MysqlMetaData.trimToNull(rs.getString("TABLE_CATALOG")));
        def.setSchema(rs.getString("TABLE_SCHEMA"));
        def.setDefinition(MysqlMetaData.trimToNull(rs.getString("VIEW_DEFINITION")));
        def.setUpdatable("YES".equalsIgnoreCase(rs.getString("IS_UPDATABLE")));
        def.setComment(MysqlMetaData.trimToNull(rs.getString("TABLE_COMMENT")));
        def.setCheckOption(normalizeCheckOption(rs.getString("CHECK_OPTION")));
        return def;
    }

    /**
     * 归一 MySQL 的检查选项：MySQL 用 {@code NONE} / {@code FULL}（等价 CASCADED）/ {@code LOCAL} 表达。
     *
     * @param checkOption 原始值
     * @return {@code NONE} / {@code CASCADE} / {@code LOCAL}，无法判定时 {@code null}
     */
    private static String normalizeCheckOption(String checkOption) {
        String value = MysqlMetaData.trimToNull(checkOption);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase();
        if ("NONE".equals(upper)) {
            return "NONE";
        }
        if ("LOCAL".equals(upper)) {
            return "LOCAL";
        }
        if ("FULL".equals(upper) || "CASCADED".equals(upper) || "CASCADE".equals(upper)) {
            return "CASCADE";
        }
        return upper;
    }

    /**
     * 把 {@link ViewCreateBuilder#checkOption(String)} 的取值翻译为 MySQL 子句。
     *
     * @param checkOption 检查选项
     * @return {@code WITH CASCADED CHECK OPTION} / {@code WITH LOCAL CHECK OPTION}，{@code null} 表示无需子句
     */
    private static String checkOptionClause(String checkOption) {
        String value = MysqlMetaData.trimToNull(checkOption);
        if (value == null || "NONE".equalsIgnoreCase(value)) {
            return null;
        }
        String upper = value.toUpperCase();
        return switch (upper) {
            case "CASCADE", "CASCADED", "FULL" -> "WITH CASCADED CHECK OPTION";
            case "LOCAL" -> "WITH LOCAL CHECK OPTION";
            default -> throw new IllegalArgumentException("MySQL 视图检查选项仅支持 NONE / CASCADE / LOCAL，实际为: "
                    + checkOption);
        };
    }

    /**
     * 引用标识符。
     *
     * @param name 标识符
     * @return 引用后的标识符
     */
    private String quote(String name) {
        return MysqlMetaData.quote(name);
    }

    /**
     * 按视图名重读字典，供构建器回填返回值。
     *
     * @param name 视图名
     * @return 视图定义，不存在时为 {@code null}
     */
    private ViewDef reload(String name) {
        return readViews(name).stream().filter(v -> name.equals(v.getName())).findFirst().orElse(null);
    }

    /**
     * MySQL 建视图链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlViewCreateBuilder implements ViewCreateBuilder {

        /**
         * 所属视图元数据入口
         */
        private final MysqlMetaView metaView;
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
         * 检查选项
         */
        private String checkOption;

        MysqlViewCreateBuilder(MysqlMetaView metaView, String viewName) {
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
            throw new UnsupportedOperationException("MySQL 不支持为视图设置注释（无视图注释子句）");
        }

        @Override
        public ViewCreateBuilder updatable() {
            throw new UnsupportedOperationException("MySQL 视图可更新性由 SELECT 结构自动判定，无对应子句");
        }

        @Override
        public ViewCreateBuilder checkOption(String checkOption) {
            this.checkOption = checkOption;
            return this;
        }

        /**
         * 执行建视图语句，并以字典读回结果作为返回值。
         *
         * @return 落库后的视图定义
         * @throws IllegalStateException 定义为空或执行失败
         */
        @Override
        public ViewDef execute() {
            if (definition == null || definition.isEmpty()) {
                throw new IllegalStateException("视图定义不能为空");
            }
            StringBuilder sb = new StringBuilder(orReplace ? "CREATE OR REPLACE VIEW " : "CREATE VIEW ")
                    .append(metaView.quote(viewName)).append(" AS ").append(definition);
            String clause = checkOptionClause(checkOption);
            if (clause != null) {
                sb.append(' ').append(clause);
            }
            MysqlMetaData.execute(metaView.engine, "创建视图 " + viewName, sb.toString(), List.of());
            return metaView.reload(viewName);
        }
    }

    /**
     * MySQL 改视图链式构建器。
     *
     * @author CH
     * @since 4.0.0
     */
    private static class MysqlViewAlterBuilder implements ViewAlterBuilder {

        /**
         * 所属视图元数据入口
         */
        private final MysqlMetaView metaView;
        /**
         * 新的 SELECT 定义
         */
        private String definition;
        /**
         * 新视图名
         */
        private String newName;
        /**
         * 检查选项
         */
        private String checkOption;

        MysqlViewAlterBuilder(MysqlMetaView metaView) {
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
         * 修改检查选项，MySQL 通过 {@code CREATE OR REPLACE VIEW} 重写实现。
         *
         * @param checkOption 检查选项
         * @return this
         */
        public ViewAlterBuilder checkOption(String checkOption) {
            this.checkOption = checkOption;
            return this;
        }

        /**
         * 执行变更：先按需重命名（MySQL 用 {@code RENAME TABLE} 改名视图），再按需 {@code CREATE OR REPLACE VIEW}。
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
                MysqlMetaData.execute(metaView.engine, "重命名视图 " + target,
                        "RENAME TABLE " + metaView.quote(target) + " TO " + metaView.quote(newName), List.of());
                target = newName;
                metaView.viewName = newName;
            }
            if (definition != null && !definition.isEmpty()) {
                StringBuilder sb = new StringBuilder("CREATE OR REPLACE VIEW ")
                        .append(metaView.quote(target)).append(" AS ").append(definition);
                String clause = checkOptionClause(checkOption);
                if (clause != null) {
                    sb.append(' ').append(clause);
                }
                MysqlMetaData.execute(metaView.engine, "替换视图 " + target, sb.toString(), List.of());
            }
            return metaView.reload(target);
        }
    }
}
