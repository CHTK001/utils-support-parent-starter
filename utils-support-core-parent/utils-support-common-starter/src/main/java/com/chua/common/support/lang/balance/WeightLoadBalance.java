package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


/**
 * 加权随机负载均衡器。
 * <p>按节点权重构造概率区间后随机抽样，被选中的节点权重衰减以避免热点命中。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("weight")
public class WeightLoadBalance implements LoadBalance {

 /**
 * 权重衰减系数
 */
 private static final double WEIGHT_DECAY_FACTOR = 2.0;

 /**
 * 节点列表
 */
 private final List<Node> nodes;

 /**
 * 默认构造，初始化空节点列表。
 */
 public WeightLoadBalance() {
 this(new LinkedList<>());
 }

 /**
 * 使用给定节点列表构造。
 *
 * @param nodes 节点列表，为 null 时使用空列表
 */
 public WeightLoadBalance(List<Node> nodes) {
 this.nodes = nodes == null ? new LinkedList<>() : nodes;
 }

 @Override
 /** 选择Node */
 public Node selectNode() {
 if (CollectionUtils.isEmpty(nodes)) {
 return null;
 }

 double weight = 0;
 for (Node node : nodes) {
 weight += node.getWeight();
 }

 double[] avgWeight = new double[nodes.size()];
 for (int i = 0; i < nodes.size(); i++) {
 avgWeight[i] = nodes.get(i).getWeight() / weight;
 }
 for (int i = 1; i < avgWeight.length; i++) {
 avgWeight[i] = avgWeight[i] + avgWeight[i - 1];
 }

 var nextDouble = ThreadLocalRandom.current().nextDouble(1);
 var index = -Arrays.binarySearch(avgWeight, nextDouble) - 1;
 if (index < 0 || index >= nodes.size()) {
 index = 0;
 }
 var node = nodes.get(index);
 node.setWeight(node.getWeight() / WEIGHT_DECAY_FACTOR);
 return node;
 }

 @Override
 /** 创建 */
 public LoadBalance create() {
 return new WeightLoadBalance(nodes);
 }

 @Override
 /** Clear */
 public LoadBalance clear() {
 nodes.clear();
 return this;
 }

 @Override
 /** 添加Node */
 public LoadBalance addNode(Node node) {
 if (node != null) {
 nodes.add(node);
 }
 return this;
 }

 @Override
 /** 选择 */
 public <T> T select(List<T> values) {
 if (values == null || values.isEmpty()) {
 return null;
 }
 Node node = selectNode();
 if (null == node) {
 return null;
 }
 int index = nodes.indexOf(node);
 if (index < 0 || index >= values.size()) {
 return values.get(0);
 }
 return values.get(index);
 }
}
