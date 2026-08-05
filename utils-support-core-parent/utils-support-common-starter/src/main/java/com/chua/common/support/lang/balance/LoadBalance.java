package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collection;
import java.util.List;


/**
 * 负载均衡器接口。
 * <p>定义节点选择、注册、清理等核心契约，实现类通过 SPI 机制加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface LoadBalance extends AutoCloseable {

 /**
 * 通过 SPI 创建指定类型的负载均衡器。
 *
 * @param type SPI 类型标识
 * @param config 负载均衡配置
 * @return 负载均衡器实例，未找到扩展时返回 null
 */
 static LoadBalance auto(String type, BalanceConfig config) {
 return ServiceProvider.of(LoadBalance.class).getNewExtension(type, config);
 }

 /**
 * 按当前负载均衡策略选择一个节点。
 *
 * @return 选中的节点，无可用节点时返回 null
 */
 Node selectNode();

 /**
 * 按当前负载均衡策略从给定列表中选取一个元素。
 *
 * @param values 候选元素列表
 * @param <T> 元素类型
 * @return 选中的元素，列表为空或为 null 时返回 null
 */
 <T> T select(List<T> values);

 /**
 * 创建当前负载均衡器的新实例。
 *
 * @return 新的负载均衡器实例
 */
 LoadBalance create();

 /**
 * 清空所有节点。
 *
 * @return 当前实例（链式调用）
 */
 LoadBalance clear();

 /**
 * 添加一个节点。
 *
 * @param node 节点，可为 null
 * @return 当前实例（链式调用）
 */
 LoadBalance addNode(Node node);

 /**
 * 批量添加多个对象作为节点。
 *
 * @param node 节点对象数组
 * @return 当前实例（链式调用）
 */
 default LoadBalance addNode(Object... node) {
 for (Object node1 : node) {
 addNode(new Node(node1));
 }
 return this;
 }

 /**
 * 批量添加集合中的对象，已是 Node 类型直接添加，其余包装为 Node。
 *
 * @param node 集合，可为 null
 * @return 当前实例（链式调用）
 */
 default LoadBalance addNode(Collection<?> node) {
 if (null == node) {
 return this;
 }
 for (Object node1 : node) {
 if (node1 instanceof Node) {
 addNode((Node) node1);
 continue;
 }
 addNode(new Node(node1));
 }
 return this;
 }

 /**
 * 批量添加多个节点。
 *
 * @param node 节点数组
 * @return 当前实例（链式调用）
 */
 default LoadBalance addNodes(Node... node) {
 for (Node node1 : node) {
 addNode(node1);
 }
 return this;
 }

 /**
 * 批量添加多个节点。
 *
 * @param node 节点集合
 * @return 当前实例（链式调用）
 */
 default LoadBalance addNodes(Collection<Node> node) {
 for (Node node1 : node) {
 addNode(node1);
 }
 return this;
 }

 @Override
 default void close() throws Exception { }
}
