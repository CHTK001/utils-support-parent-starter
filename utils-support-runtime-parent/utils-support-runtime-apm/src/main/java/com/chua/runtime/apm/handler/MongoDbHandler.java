package com.chua.runtime.apm.handler;

import com.chua.runtime.spy.InterceptContext;
import com.chua.runtime.protocol.Endpoint;
import com.chua.runtime.protocol.EndpointKind;
import com.chua.runtime.protocol.Protocol;
import com.chua.runtime.protocol.Software;

/**
 * MongoDB 应用层 Handler — 拦截 MongoDB Java Driver 关键调用并生成应用语义传输记录。
 *
 * <p>拦截目标：</p>
 * <ul>
 *   <li>{@code com.mongodb.client.internal.MongoDatabaseImpl} — createCollection / dropCollection / listCollections</li>
 *   <li>{@code com.mongodb.client.internal.MongoCollectionImpl} — insertOne / find / updateOne / deleteOne 等 CRUD</li>
 * </ul>
 *
 * <p>采用零编译期依赖策略：MongoDB 驱动不在 classpath 时 SpyTransformer 找不到类而不生效（无副作用）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MongoDbHandler extends AbstractAppHandler {

    /**
     * MongoDatabaseImpl 类内部名
     */
    private static final String MONGO_DATABASE_CLASS = "com/mongodb/client/internal/MongoDatabaseImpl";

    /**
     * MongoCollectionImpl 类内部名
     */
    private static final String MONGO_COLLECTION_CLASS = "com/mongodb/client/internal/MongoCollectionImpl";

    /**
     * 数据库级方法集合
     */
    private static final String[] DATABASE_METHODS = {"createCollection", "dropCollection", "listCollections"};

    /**
     * 集合级 CRUD 方法集合
     */
    private static final String[] COLLECTION_METHODS = {
            "insertOne", "insertMany", "find", "findOneAndUpdate", "findOneAndDelete",
            "updateOne", "updateMany", "replaceOne", "deleteOne", "deleteMany",
            "countDocuments", "aggregate", "distinct", "bulkWrite"
    };

    @Override
    public String name() {
        return "mongodb-handler";
    }

    @Override
    protected String enabledKey() {
        return "mongodb.enabled";
    }

    @Override
    protected Software software() {
        return Software.MONGODB_DRIVER;
    }

    @Override
    protected Protocol protocol() {
        return Protocol.MONGODB;
    }

    @Override
    protected void registerInterceptors() {
        registerAll(MONGO_DATABASE_CLASS, DATABASE_METHODS);
        registerAll(MONGO_COLLECTION_CLASS, COLLECTION_METHODS);
    }

    @Override
    protected Endpoint buildTarget(InterceptContext ctx, Object instance) {
        Object cluster = findField(instance, "cluster");
        String url = cluster != null ? String.valueOf(findField(cluster, "connectionString")) : null;
        return Endpoint.builder()
                .kind(EndpointKind.SERVER)
                .protocol(Protocol.MONGODB)
                .software(Software.MONGODB_DRIVER)
                .host(parseUrlHost(url) != null ? parseUrlHost(url) : "mongodb")
                .port(parseUrlPort(url, Protocol.MONGODB.defaultPort()))
                .path(url != null ? parseUrlDb(url) : "/")
                .build();
    }
}