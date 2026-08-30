package com.chua.sqlserver.support.engine;

import com.chua.common.support.lang.datasource.engine.EngineDataSource;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.datasource.support.engine.JdbcReactorEngine;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SQL Server 鑰佺増鏈吋瀹瑰搷搴斿紡寮曟搸锛圫QL Server 2000/2005锛夛紝浣跨敤 jTDS 椹卞姩銆? *
 * <p>褰撳墠涓轰吉鍝嶅簲寮忓疄鐜帮紙{@code boundedElastic} 璋冨害闃诲 JDBC 璋冪敤锛夛紝
 * jTDS 鏃?R2DBC 椹卞姩锛屾棤娉曞疄鐜扮湡姝ｉ潪闃诲銆? *
 * <pre>{@code
 * SqlServerLegacyReactorEngine engine = new SqlServerLegacyReactorEngine();
 * engine.addDataSource("default", "localhost", 1433, "master", "sa", "password");
 * Flux<User> users = engine.query(User.class).eq(User::getName, "寮犱笁").list();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 */
 @Spi("sqlserver-legacy")
public class SqlServerLegacyReactorEngine extends JdbcReactorEngine {

    /** 瀵瑰簲鐨勫悓姝ュ紩鎿庯紝鐢ㄤ簬鎵胯浇 jTDS 鏁版嵁婧愰厤缃?*/
    private final SqlServerLegacyEngine delegate = new SqlServerLegacyEngine();

    /**
     * 娣诲姞涓€涓?SQL Server 鑰佺増鏈暟鎹簮锛堝鎵樼粰鍚屾寮曟搸锛夈€?     *
     * @param name     鏁版嵁婧愬悕绉?     * @param host     涓绘満鍦板潃
     * @param port     绔彛鍙?     * @param database 鏁版嵁搴撳悕
     * @param username 鐢ㄦ埛鍚?     * @param password 瀵嗙爜
     * @return 褰撳墠寮曟搸瀹炰緥
     */
    public SqlServerLegacyReactorEngine addDataSource(String name, String host, int port, String database, String username, String password) {
        ((SqlServerLegacyEngine) delegate).addDataSource(name, host, port, database, username, password);
        // 鑾峰彇鍚屾寮曟搸娉ㄥ唽鐨勬暟鎹簮 URL 骞舵敞鍐屽埌鍝嶅簲寮?JDBC 璺緞
        EngineDataSource<?> ds = delegate.getDataSource(name);
        if (ds != null) {
            registerJdbcDataSource(name, ds.url(), username, password);
        }
        return this;
    }

    /**
     * 鏌ヨ鎵€鏈夎褰曪紙鍝嶅簲寮忥級銆?     *
     * @param table 琛ㄥ悕
     * @return 鍝嶅簲寮忕粨鏋滄祦
     */
    public Flux<Map<String, Object>> queryAll(String table) {
        return query("SELECT * FROM " + table);
    }

    /**
     * 鎸夋潯浠舵煡璇紙鍝嶅簲寮忥級銆?     *
     * @param table  琛ㄥ悕
     * @param where  WHERE 瀛愬彞锛堜笉鍚?WHERE 鍏抽敭瀛楋級
     * @param params 鍙傛暟
     * @return 鍝嶅簲寮忕粨鏋滄祦
     */
    public Flux<Map<String, Object>> queryWhere(String table, String where, Object... params) {
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);
        if (where != null && !where.isEmpty()) {
            sql.append(" WHERE ").append(where);
        }
        return query(sql.toString(), params);
    }

    /**
     * 鎵ц鎻掑叆锛堝搷搴斿紡锛夈€?     *
     * @param table 琛ㄥ悕
     * @param cols  鍒楀悕鏁扮粍
     * @param vals  鍊兼暟缁?     * @return 褰卞搷琛屾暟
     */
    public Mono<Integer> insert(String table, String[] cols, Object... vals) {
        StringBuilder sb = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(String.join(", ", cols)).append(") VALUES (");
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        return execute(sb.toString(), vals);
    }
}