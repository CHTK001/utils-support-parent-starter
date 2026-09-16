package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


/**
* 随机轮询负载均衡器。
* <p>每次选择时对节点列表做随机洗牌后取首节点，提供最朴素的随机分摊效果。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("random")
public class RandomRoundLoadBalance implements LoadBalance {

 /**
 * 节点列表
 */
 private final List<Node> nodes = new ArrayList<>();

 @Override
 /** 选择Node */
 public Node selectNode() {
 if (nodes.isEmpty()) {
 return null;
 }
 Collections.shuffle(nodes);
 return nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()));
 }

 @Override
 /** 创建 */
 public LoadBalance create() {
 return new RandomRoundLoadBalance();
 }

 @Override
 /** Clear */
 public synchronized LoadBalance clear() {
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
 if (values.size() == 1) {
 return values.getFirst();
 }
 return values.get(ThreadLocalRandom.current().nextInt(values.size()));
 }
}
