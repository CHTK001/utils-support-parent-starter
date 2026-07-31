package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.CollectionUtils;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author CH
 */
@Spi("weight")
public class WeightLoadBalance implements LoadBalance {

    private static final double WEIGHT_DECAY_FACTOR = 2.0;
    private final List<Node> nodes;

    public WeightLoadBalance() { this(new LinkedList<>()); }

    public WeightLoadBalance(List<Node> nodes) { this.nodes = nodes; }

    @Override
    public Node selectNode() {
        if (CollectionUtils.isEmpty(nodes)) { return null; }

        double weight = 0;
        for (Node node : nodes) weight += node.getWeight();

        double[] avgWeight = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            avgWeight[i] = nodes.get(i).getWeight() / weight;
        }
        for (int i = 1; i < avgWeight.length; i++) {
            avgWeight[i] = avgWeight[i] + avgWeight[i - 1];
        }

        var nextDouble = ThreadLocalRandom.current().nextDouble(1);
        var index = -Arrays.binarySearch(avgWeight, nextDouble) - 1;
        if (index < 0 || index >= nodes.size()) index = 0;
        var node = nodes.get(index);
        node.setWeight(node.getWeight() / WEIGHT_DECAY_FACTOR);
        return node;
    }

    @Override
    public LoadBalance create() { return new WeightLoadBalance(nodes); }

    @Override
    public LoadBalance clear() { nodes.clear(); return this; }

    @Override
    public LoadBalance addNode(Node node) { nodes.add(node); return this; }

    @Override
    public <T> T select(List<T> values) {
        if (CollectionUtils.isEmpty(values)) { return null; }
        Node node = selectNode();
        if (null == node) { return null; }
        int index = nodes.indexOf(node);
        if (index < 0 || index >= values.size()) { return values.get(0); }
        return values.get(index);
    }
}
