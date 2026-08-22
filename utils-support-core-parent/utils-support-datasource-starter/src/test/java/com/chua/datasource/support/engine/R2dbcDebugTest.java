package com.chua.datasource.support.engine;

import io.r2dbc.spi.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class R2dbcDebugTest {
    @Test
    void debugMySqlGetRowsUpdatedType() {
        JdbcReactorEngine engine = new JdbcReactorEngine();
        engine.addDataSource("mysql", "jdbc:mysql://172.16.0.40:3306/report?useSSL=false&allowPublicKeyRetrieval=true", "root", "root@");
        ConnectionFactory factory = engine.getR2dbcFactory("mysql");
        
        // Use a table that exists - first find one
        Mono.from(factory.create()).flatMap(conn -> {
            Statement stmt = conn.createStatement("SHOW TABLES");
            return Flux.from(stmt.execute())
                .flatMap(r -> Flux.from(r.map((row, meta) -> row.get(0))))
                .next();
        }).doOnNext(t -> System.out.println("First table: " + t)).block();
        
        // Test INSERT and check getRowsUpdated type
        Mono.from(factory.create()).flatMap(conn -> {
            Statement stmt = conn.createStatement("INSERT INTO soft_service_version_builder (id, version) VALUES (99999, 'debug') ON DUPLICATE KEY UPDATE version='debug'");
            return Flux.from(stmt.execute())
                .flatMap(r -> {
                    System.out.println("getRowsUpdated type: " + r.getRowsUpdated().getClass().getName());
                    return Flux.from(r.getRowsUpdated())
                        .doOnNext(v -> System.out.println("Value: " + v + " Type: " + v.getClass().getName()))
                        .reduce(0L, Long::sum);
                })
                .defaultIfEmpty(0L)
                .doOnComplete(() -> {
                    try { conn.close().block(); } catch (Exception e) {}
                });
        }).block();
    }
}
