package com.chua.vector.support.storage;

import com.chua.common.support.vector.AbstractVectorStorage;
import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorCompareAlgorithm;
import com.chua.jvector.support.configuration.JVectorStorageProperties;
import com.chua.jvector.support.storage.JVectorVectorStorage;
import com.chua.vector.support.configuration.VectorStorageProperties;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class JvectorVectorStorageDelegate extends AbstractVectorStorage {

    private final JVectorVectorStorage delegate;

    public JvectorVectorStorageDelegate(int dimension, VectorCompareAlgorithm algorithm,
                                         VectorStorageProperties props) {
        super(dimension, algorithm);
        this.delegate = createJvectorStorage(dimension, algorithm, props);
    }

    private static JVectorVectorStorage createJvectorStorage(int dimension,
                                                              VectorCompareAlgorithm algorithm,
                                                              VectorStorageProperties props) {
        JVectorStorageProperties jprops = new JVectorStorageProperties();
        jprops.setMode(toJvectorMode(props.getJvectorMode()));
        jprops.setGraphM(props.getJvectorGraphM());
        jprops.setGraphEfConstruction(props.getJvectorEfConstruction());
        jprops.setIndexPath(props.getJvectorIndexPath());
        return new JVectorVectorStorage(dimension, algorithm, jprops);
    }

    private static JVectorStorageProperties.Mode toJvectorMode(VectorStorageProperties.JvectorMode mode) {
        return switch (mode) {
            case ON_DISK -> JVectorStorageProperties.Mode.ON_DISK;
            case LARGER_THAN_MEMORY -> JVectorStorageProperties.Mode.LARGER_THAN_MEMORY;
            case MEMORY -> JVectorStorageProperties.Mode.MEMORY;
        };
    }

    @Override
    protected synchronized boolean doAdd(String id, float[] vector) {
        return delegate.doAdd(id, vector);
    }

    @Override
    protected synchronized List<Vector> doSearch(float[] query, int topK) {
        return delegate.doSearch(query, topK);
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
