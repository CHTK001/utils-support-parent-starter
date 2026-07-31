package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collection;
import java.util.List;
/**
 * @author CH
 */

public interface LoadBalance extends AutoCloseable {

    static LoadBalance auto(String type, BalanceConfig config) {
        return ServiceProvider.of(LoadBalance.class).getNewExtension(type, config);
    }

    Node selectNode();

    <T> T select(List<T> values);

    LoadBalance create();

    LoadBalance clear();

    LoadBalance addNode(Node node);

    default LoadBalance addNode(Object... node) {
        for (Object node1 : node) {
            addNode(new Node(node1));
        }
        return this;
    }

    default LoadBalance addNode(Collection<?> node) {
        if (null == node) { return this; }
        for (Object node1 : node) {
            if (node1 instanceof Node) { addNode((Node) node1); continue; }
            addNode(new Node(node1));
        }
        return this;
    }

    default LoadBalance addNodes(Node... node) {
        for (Node node1 : node) addNode(node1);
        return this;
    }

    default LoadBalance addNodes(Collection<Node> node) {
        for (Node node1 : node) addNode(node1);
        return this;
    }

    @Override
    default void close() throws Exception { }
}
