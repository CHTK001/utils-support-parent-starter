package com.chua.common.support.lang.balance;

import com.chua.common.support.spi.annotations.Spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.NullUnmarked;

/**
 * @author CH
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Spi("random")
public class RandomRoundLoadBalance implements LoadBalance {

    private final List<Node> nodes = new ArrayList<>();

    @Override
    public Node selectNode() {
        if (nodes.isEmpty()) { return null; }
        Collections.shuffle(nodes);
        return nodes.get(ThreadLocalRandom.current().nextInt(nodes.size()));
    }

    @Override
    public LoadBalance create() { return new RandomRoundLoadBalance(); }

    @Override
    public synchronized LoadBalance clear() { nodes.clear(); return this; }

    @Override
    public LoadBalance addNode(Node node) { nodes.add(node); return this; }

    @Override
    public <T> T select(List<T> values) {
        if (values == null || values.isEmpty()) { return null; }
        if (values.size() == 1) { return values.get(0); }
        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }
}
