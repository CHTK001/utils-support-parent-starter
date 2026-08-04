package com.chua.common.support.lang.balance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.jspecify.annotations.NullUnmarked;

/**
 * @author CH
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Node implements Comparable<Node> {

    /**
     * 内容
     */
    private Object content;
    private double weight = 0D;

    public Node(Object content) {
        this.content = content;
    }

    public <T> T getValue(Class<T> targetType) {
        if (content == null) { return null; }
        if (targetType.isInstance(content)) { return targetType.cast(content); }
        return null;
    }

    @Override
    public int compareTo(Node node) {
        return weight > node.weight ? 1 : 0;
    }
}
