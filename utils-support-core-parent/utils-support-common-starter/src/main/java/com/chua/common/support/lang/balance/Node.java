package com.chua.common.support.lang.balance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * 负载均衡节点。
 * <p>封装负载均衡候选节点的内容与权重。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullMarked
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Node implements Comparable<Node> {

    /**
     * 节点内容
     */
    private @Nullable Object content;

    /**
     * 节点权重
     */
    private double weight = 0D;

    /**
     * 使用给定内容构造节点。
     *
     * @param content 节点内容，可为 null
     */
    public Node(@Nullable Object content) {
        this.content = content;
    }

    /**
     * 按目标类型取值。
     *
     * @param targetType 目标类型
     * @param <T>        泛型类型
     * @return 类型匹配的值，否则 null
     */
    public <T> @Nullable T getValue(Class<T> targetType) {
        if (content == null) {
            return null;
        }
        if (targetType.isInstance(content)) {
            return targetType.cast(content);
        }
        return null;
    }

    /**
     * 按权重升序比较。
     *
     * @param node 另一个节点
     * @return 比较结果
     */
    @Override
    public int compareTo(Node node) {
        return weight > node.weight ? 1 : 0;
    }
}
