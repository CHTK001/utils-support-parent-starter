package com.chua.datalake.support.pipeline;

import com.chua.common.support.lang.json.Json;
import com.chua.datalake.support.spi.pipeline.PipelineConfig;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC 持久化管线管理器 —— 在 {@link DefaultPipelineManager} 的内存编译缓存之上
 * 增加「写穿数据库」能力，使管线配置在服务重启后仍然存在。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>继承而非平级实现</b>：引擎读路径（{@code DefaultPipelineEngine}）只认
 *       {@link DefaultPipelineManager} 的编译缓存；若换成平级实现，引擎每次 execute 都会
 *       重新 JSON 解析。继承后编译缓存、{@code setEngine} 失效回调全部保留，本类只负责
 *       把 store 的变化同步进数据库。</li>
 *   <li><b>写穿</b>：save/delete 先落库（失败即抛出，内存不产生幻影数据），再回调 super
 *       更新内存与引擎缓存。</li>
 *   <li><b>读回填</b>：get 未命中内存时从库回填并编译入缓存。</li>
 *   <li><b>启动预载</b>：构造时建表（{@code CREATE TABLE IF NOT EXISTS}，MySQL/H2 兼容）
 *       并全表载入内存，之后查询不再触库。</li>
 * </ul>
 *
 * <p>持久层为单表 {@code sys_datalake_pipeline}，仅存 DSL 原文与审计时间，
 * 不承载任何业务模块的数据，符合「底层只提供能力」的边界。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public class JdbcPipelineManager extends DefaultPipelineManager {

    /**
     * 管线配置表名
     */
    private static final String TABLE = "sys_datalake_pipeline";

    /**
     * 建表语句：id 为主键，dsl 存原始 JSON。
     *
     * <p>列类型必须用 TEXT：MySQL 没有 CLOB 类型（2026-11-23 194 实证：
     * {@code dsl CLOB} 触发 SQLSyntaxErrorException，建表失败降级纯内存模式）；
     * TEXT 在 MySQL 与 H2 下均可用。</p>
     */
    private static final String CREATE_TABLE_SQL = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + "id VARCHAR(128) PRIMARY KEY, "
            + "dsl TEXT, "
            + "gmt_create DATETIME, "
            + "gmt_modified DATETIME)";

    private static final String INSERT_SQL =
            "INSERT INTO " + TABLE + " (id, dsl, gmt_create, gmt_modified) VALUES (?, ?, ?, ?)";

    private static final String UPDATE_SQL =
            "UPDATE " + TABLE + " SET dsl = ?, gmt_modified = ? WHERE id = ?";

    private static final String DELETE_SQL =
            "DELETE FROM " + TABLE + " WHERE id = ?";

    private static final String SELECT_DSL_SQL =
            "SELECT dsl FROM " + TABLE + " WHERE id = ?";

    private static final String SELECT_ALL_SQL =
            "SELECT id, dsl FROM " + TABLE;

    private static final String SELECT_META_SQL =
            "SELECT id, gmt_create, gmt_modified FROM " + TABLE + " ORDER BY id";

    /**
     * 数据源，由宿主注入
     */
    private final DataSource dataSource;

    /**
     * 构造 JDBC 管线管理器：建表并预载全表配置。
     *
     * <p>数据库不可达时不抛出，降级为纯内存管理器并记录 error——数据中台本身
     * 不应因管理面持久化故障而拒绝启动。</p>
     *
     * @param dataSource 数据源
     */
    public JdbcPipelineManager(DataSource dataSource) {
        this.dataSource = dataSource;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE_SQL);
        } catch (Exception e) {
            log.error("[pipeline-mgr] 管线配置表初始化失败，降级为纯内存模式: {}", e.getMessage(), e);
            return;
        }
        preload();
    }

    /**
     * 启动时全表预载进内存编译缓存。
     */
    private void preload() {
        int loaded = 0;
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(SELECT_ALL_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String id = rs.getString(1);
                String dsl = rs.getString(2);
                if (id != null && dsl != null) {
                    // 走 super：只进内存与编译缓存，不回写数据库
                    super.savePipeline(id, dsl);
                    loaded++;
                }
            }
        } catch (Exception e) {
            log.error("[pipeline-mgr] 管线配置预载失败: {}", e.getMessage(), e);
            return;
        }
        if (loaded > 0) {
            log.info("[pipeline-mgr] 已从数据库预载 {} 条管线配置", loaded);
        }
    }

    /**
     * 保存或更新管线配置：先写穿数据库，再更新内存编译缓存。
     *
     * @param pipelineId 管线唯一标识
     * @param jsonDsl    管线 DSL 的 JSON 字符串
     */
    @Override
    public void savePipeline(String pipelineId, String jsonDsl) {
        upsert(pipelineId, jsonDsl);
        super.savePipeline(pipelineId, jsonDsl);
    }

    /**
     * 删除管线配置：先删数据库，再清内存与编译缓存。
     *
     * @param pipelineId 管线标识
     */
    @Override
    public void deletePipeline(String pipelineId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(DELETE_SQL)) {
            ps.setString(1, pipelineId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("管线配置删除失败: " + pipelineId + " - " + e.getMessage(), e);
        }
        super.deletePipeline(pipelineId);
    }

    /**
     * 获取管线原始 DSL：内存未命中时从数据库回填并编译入缓存。
     *
     * @param pipelineId 管线标识
     * @return 原始 JSON，不存在返回 {@code null}
     */
    @Override
    public String getPipeline(String pipelineId) {
        String cached = super.getPipeline(pipelineId);
        if (cached != null) {
            return cached;
        }
        String fromDb = selectDsl(pipelineId);
        if (fromDb != null) {
            // 回填内存（走 super 避免二次落库）
            super.savePipeline(pipelineId, fromDb);
        }
        return fromDb;
    }

    /**
     * 查询数据库中全部管线的元信息（含时间戳），按 id 升序。
     *
     * <p>管理面展示用；内存中已预载同样的 id 集合，时间戳只能从这里取。</p>
     *
     * @return 管线元信息列表
     */
    public List<PipelineMeta> listMeta() {
        List<PipelineMeta> result = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(SELECT_META_SQL);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new PipelineMeta(rs.getString(1), rs.getTimestamp(2), rs.getTimestamp(3)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("管线元信息查询失败: " + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 新增或更新一行（先 UPDATE，0 行命中再 INSERT，MySQL/H2 通用）。
     */
    private void upsert(String pipelineId, String jsonDsl) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(UPDATE_SQL)) {
                ps.setString(1, jsonDsl);
                ps.setTimestamp(2, now);
                ps.setString(3, pipelineId);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                ps.setString(1, pipelineId);
                ps.setString(2, jsonDsl);
                ps.setTimestamp(3, now);
                ps.setTimestamp(4, now);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("管线配置持久化失败: " + pipelineId + " - " + e.getMessage(), e);
        }
    }

    /**
     * 按主键查 DSL 原文。
     */
    private String selectDsl(String pipelineId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(SELECT_DSL_SQL)) {
            ps.setString(1, pipelineId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("管线配置查询失败: " + pipelineId + " - " + e.getMessage(), e);
        }
    }

    /**
     * 管线元信息（id + 审计时间）。
     */
    @Getter
    @AllArgsConstructor
    public static class PipelineMeta {

        /**
         * 管线标识
         */
        private final String id;

        /**
         * 创建时间
         */
        private final Timestamp gmtCreate;

        /**
         * 最近修改时间
         */
        private final Timestamp gmtModified;
    }
}
