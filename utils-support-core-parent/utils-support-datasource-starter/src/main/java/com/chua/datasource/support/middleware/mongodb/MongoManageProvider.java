package com.chua.datasource.support.middleware.mongodb;

import java.util.List;
import java.util.Map;

/**
 * MongoDB 管理面 SPI：探测、逻辑库/集合浏览、只读查询与单文档查看。
 *
 * <h3>为什么单独一个契约</h3>
 * <p>MongoDB 没有稳定的只读 HTTP 查询面，管理面必须用官方驱动；把驱动塞进通用中间件契约
 * （{@link com.chua.datasource.support.middleware.MiddlewareClientProvider}）会让所有中间件
 * 实现都背上 MongoDB 客户端。因此单独一个契约，按需安装。</p>
 *
 * <h3>装配方式</h3>
 * <ul>
 *   <li><b>默认装载</b>：宿主引了 {@code utils-support-mongodb-starter}，
 *       实现方在 {@code META-INF/extensions/com.chua.datasource.support.middleware.mongodb.
 *       MongoManageProvider} 登记，按 {@link #SPI_NAME} 取用；</li>
 *   <li><b>OSGi 后装</b>：同一份 jar 装进框架后由 {@code OsgiServiceResolver} 命中；</li>
 *   <li><b>未装配</b>：宿主抛出「功能未开放」，而不是编译期依赖驱动。</li>
 * </ul>
 *
 * <p><b>实现约定</b>：只接收 {@link MongoManageTarget}，不查库、不依赖宿主 Spring 容器；
 * 驱动异常一律翻译成 {@link IllegalStateException}（含可读中文原因，宿主转成业务错误码），
 * 语句不合法保留 {@link IllegalArgumentException}，不要返回演示数据。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface MongoManageProvider {

    /**
     * SPI 名称
     */
    String SPI_NAME = "mongodb";

    /**
     * 实现名，缺省即 {@link #SPI_NAME}。
     *
     * @return 名称，大小写不敏感
     */
    default String name() {
        return SPI_NAME;
    }

    /**
     * 探测连通性并取版本号。
     *
     * @param target 连接目标
     * @return 测试结果；连接失败时 {@code connected=false} 并带可读原因
     */
    MongoTestResult test(MongoManageTarget target);

    /**
     * 列出全部逻辑库。
     *
     * @param target 连接目标
     * @return 逻辑库列表
     */
    List<MongoDatabaseInfo> databases(MongoManageTarget target);

    /**
     * 列出指定逻辑库的集合及其类型与估算文档数。
     *
     * @param target   连接目标
     * @param database 逻辑库名，空按目标默认库
     * @return 集合列表
     */
    List<MongoCollectionInfo> collections(MongoManageTarget target, String database);

    /**
     * 执行一句只读 shell 查询。
     *
     * @param target     连接目标
     * @param database   当前逻辑库（shell 里的 {@code db}），空按目标默认库
     * @param expression shell 表达式，如 {@code db.users.find({}).limit(10)}
     * @return 查询结果
     * @throws IllegalArgumentException 语句为空或不在只读白名单内
     * @throws IllegalStateException    连接失败、认证失败或查询被服务端拒绝
     */
    MongoQueryResult query(MongoManageTarget target, String database, String expression);

    /**
     * 按 {@code _id} 取单个文档。
     *
     * @param target     连接目标
     * @param database   逻辑库名，空按目标默认库
     * @param collection 集合名
     * @param id         文档 ID（ObjectId 十六进制串或原始主键）
     * @return 文档内容（键 → 归一化值）；不存在时返回 {@code null}
     */
    Map<String, Object> document(MongoManageTarget target, String database, String collection, String id);
}
