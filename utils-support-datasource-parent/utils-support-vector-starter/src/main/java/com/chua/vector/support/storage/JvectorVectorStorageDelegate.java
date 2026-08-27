package com.chua.vector.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;
import com.chua.vector.support.configuration.VectorStorageProperties;

import java.util.List;

/**
 * jvector CPU 向量存储适配层。
 *
 * <p>将 {@link VectorStorageProperties} 映射为 {@link JVectorStorageProperties}，
 * 复用已有的 {@link JVectorVectorStorage} 实现作为无 GPU 环境时的 CPU fallback。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class JvectorVectorStorageDelegate extends AbstractVectorStorage {

    private final JVectorVectorStorage delegate;

    /**
     * 构造 jvector CPU 向量存储适配器。
     *
     * @param dimension 向量维度
     * @param algorithm 比较算法
     * @param props     向量存储配置属性
     */
    public JvectorVectorStorageDelegate(int dimension, VectorCompareAlgorithm algorithm,
                                         VectorStorageProperties props) {
        super(dimension, algorithm);
        this.delegate = createJvectorStorage(dimension, algorithm, props);
    }

    /**
     * 创建并配置 JVector 存储实例。
     *
     * @param dimension 向量维度
     * @param algorithm 比较算法
     * @param props     配置属性
     * @return JVector 存储实例
     */
    private static JVectorVectorStorage createJvectorStorage(int dimension,
                                                              VectorCompareAlgorithm algorithm,
                                                              VectorStorageProperties props) {
        JVectorStorageProperties jprops = new JVectorStorageProperties();
        jprops.setMode(toJvectorMode(props.jvectorMode()));
        jprops.setGraphM(props.jvectorGraphM());
        jprops.setGraphEfConstruction(props.jvectorEfConstruction());
        jprops.setIndexPath(props.jvectorIndexPath());
        return new JVectorVectorStorage(dimension, algorithm, jprops);
    }

    /**
     * 将内部枚举映射为 JVector 存储模式。
     *
     * @param mode 内部枚举值
     * @return 对应的 JVector 存储模式
     */
    private static JVectorStorageProperties.Mode toJvectorMode(VectorStorageProperties.JvectorMode mode) {
        return switch (mode) {
            case ON_DISK -> JVectorStorageProperties.Mode.ON_DISK;
            case LARGER_THAN_MEMORY -> JVectorStorageProperties.Mode.LARGER_THAN_MEMORY;
            case MEMORY -> JVectorStorageProperties.Mode.MEMORY;
        };
    }

    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        return delegate.add(id, vector);
    }

    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        return delegate.search(query, topK);
    }

    @Override
    public synchronized int size() {
        return delegate.size();
    }

    @Override
    public synchronized void clear() {
        delegate.clear();
    }

    @Override
    public synchronized void close() {
        delegate.close();
    }

    @Override
    public synchronized boolean remove(String id) {
        return delegate.remove(id);
    }

    @Override
    public synchronized boolean update(String id, float[] vector) {
        return delegate.update(id, vector);
    }
}
