package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * 轮询负载均衡器。
 * <p>按顺序依次分配请求到各节点，基于原子计数器实现无锁轮询；适用于节点权重相同的场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@SpiDefault
@Spi({"round", "polling"})
public class RoundLoadBalance implements LoadBalance {

 /**
 * 轮询计数器
 */
 final AtomicInteger count = new AtomicInteger(0);

 /**
 * 节点列表
 */
 private final List<Node> nodes = new ArrayList<>();

 @Override
 public Node selectNode() {
 if (nodes.isEmpty()) {
 return null;
 }
 int andIncrement = count.getAndIncrement();
 return nodes.get(andIncrement % nodes.size());
 }

 @Override
 public LoadBalance create() {
 return new RoundLoadBalance();
 }

 @Override
 public synchronized LoadBalance clear() {
 nodes.clear();
 return this;
 }

 @Override
 public LoadBalance addNode(Node node) {
 if (node != null) {
 nodes.add(node);
 }
 return this;
 }

 @Override
 public <T> T select(List<T> values) {
 if (values == null || values.isEmpty()) {
 return null;
 }
 if (values.size() == 1) {
 return values.get(0);
 }
 int index = count.getAndIncrement();
 if (index < 0) {
 count.set(0);
 index = 0;
 }
 return values.get(index % values.size());
 }
}
