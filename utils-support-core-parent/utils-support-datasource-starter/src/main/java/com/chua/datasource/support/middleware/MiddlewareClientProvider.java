package com.chua.datasource.support.middleware;

import com.chua.common.support.spi.annotations.Spi;

/**
 * 中间件管理面 SPI 扩展点。
 *
 * <p><b>定位</b>：把「某一种中间件怎么连、怎么查」从宿主系统里抽出来，宿主只认这个接口。
 * 具体实现按中间件各自归位（{@code utils-support-kafka-starter}、
 * {@code utils-support-influxdb-starter}、{@code utils-support-redis-starter} …），
 * 宿主主依赖树里<b>不含</b>任何重型中间件客户端 —— 需要哪种就装哪种。</p>
 *
 * <p><b>装配方式</b>（由 {@code com.chua.common.support.spi.ServiceProvider} 统一负责）：</p>
 * <ol>
 *   <li><b>默认装载</b>：实现方在 {@code META-INF/extensions/com.chua.datasource.support.middleware.MiddlewareClientProvider}
 *       写一行 {@code <名称>=<实现类全限定名>}，宿主引了该 starter 即生效；</li>
 *   <li><b>OSGi 后装</b>：实现方打成 bundle 由 {@code spring-support-bundle-starter} 安装，
 *       {@code OsgiServiceResolver} 会优先从 OSGi 容器取服务，无需改宿主。</li>
 * </ol>
 *
 * <p><b>未装配时的行为</b>：宿主侧 {@code ServiceProvider.of(MiddlewareClientProvider.class)}
 * 取不到对应名称的实现，此时宿主返回「功能未开放」提示，<b>不报错、不 404</b>。
 * 因此新增一种中间件不需要改宿主任何一行代码。</p>
 *
 * <p><b>命名约定</b>：{@link #name()} 与「数据源管理」里的 {@code type} 字段一致，
 * 全小写（{@code kafka} / {@code influxdb} / {@code redis} / {@code nacos} /
 * {@code zookeeper} / {@code mongodb} / {@code neo4j} …）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi(MiddlewareClientProvider.SPI_NAME)
public interface MiddlewareClientProvider {

    /**
     * SPI 名称，同时是 {@code META-INF/extensions/} 注册文件的文件名。
     */
    String SPI_NAME = "com.chua.datasource.support.middleware.MiddlewareClientProvider";

    /**
     * 数据源类型标识，与数据源行的 {@code type} 列对应。
     *
     * @return 小写类型名，如 {@code kafka}
     */
    String name();

    /**
     * 展示名，用于「功能未开放」提示与前端下拉，如 {@code Apache Kafka}。
     *
     * @return 中文展示名
     */
    String displayName();

    /**
     * 默认端口，用于新建数据源时预填与连通性探测。
     *
     * @return 端口号
     */
    int defaultPort();

    /**
     * 能力声明：告诉宿主这个实现支持哪些只读操作，宿主据此决定渲染哪些按钮。
     *
     * <p>宿主不需要知道 Kafka 有 Topic、InfluxDB 有 Bucket 这些细节 ——
     * 能力由实现方自己声明，宿主只做分发。</p>
     *
     * @return 能力描述，永不为 {@code null}
     */
    MiddlewareCapability capability();

    /**
     * 连接测试：按数据源行的 host/port/账号密码探一次真实连通性。
     *
     * @param request 连接参数（已由宿主从数据源行解析好）
     * @return 测试结果；实现方应保证失败时也返回对象（{@code connected=false} + 原因），
     *         而不是抛异常——异常会被宿主当成系统错误，而连接失败是业务结果
     */
    MiddlewareTestResult test(MiddlewareConnectRequest request);

    /**
     * 列出顶层节点（逻辑库 / Bucket / Topic / 命名空间 …）。
     *
     * @param request 连接参数
     * @return 节点列表；读取失败时返回空列表，原因通过返回值里的 message 透出
     */
    java.util.List<MiddlewareTreeNode> listTopNodes(MiddlewareConnectRequest request);

    /**
     * 列出某个顶层节点下的子节点（集合 / 度量 / 分区 …）。
     *
     * @param request   连接参数
     * @param parentKey 父节点标识，取自 {@link MiddlewareTreeNode#key()}
     * @return 子节点列表
     */
    java.util.List<MiddlewareTreeNode> listChildren(MiddlewareConnectRequest request, String parentKey);

    /**
     * 执行一条只读查询。
     *
     * <p>查询语法由实现方自定义（Mongo Shell / Flux / Cypher / Redis 命令 …），
     * 宿主只负责把表达式原样转交，并把结果渲染成表格。</p>
     *
     * @param request    连接参数
     * @param expression 查询表达式
     * @param limit      期望返回的最大行数（实现方可自行兜底）
     * @return 查询结果
     */
    MiddlewareQueryResult query(MiddlewareConnectRequest request, String expression, int limit);

    /**
     * 按标识取一条完整内容（文档 / 消息 / 键值 …）用于详情查看。
     *
     * @param request 连接参数
     * @param scope   作用域（库名/桶名/主题名），取自前端上下文
     * @param key     标识，取自结果行
     * @return 详情文本；不存在时返回 {@code null}
     */
    MiddlewareDocument document(MiddlewareConnectRequest request, String scope, String key);
}
