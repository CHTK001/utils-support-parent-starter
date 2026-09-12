package com.chua.deeplearning.support.recognition;

import java.util.ArrayList;
import java.util.List;

/**
* 文档解析上下文。
*
* <p>承载一次文档解析的状态：当前图像与已收集的解析结果列表。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DocumentParseContext {

    /**
    * 当前图像数据。
     */
    private final byte[] imageData;

    /**
    * 已收集的解析结果。
     */
    private final List<Object> results = new ArrayList<>();

    /**
    * 构造。
    *
    * @param imageData 图像数据
     */
    public DocumentParseContext(byte[] imageData) {
        this.imageData = imageData;
    }

    /**
    * 当前图像数据。
    *
    * @return 图像字节
     */
    public byte[] currentImage() {
        return imageData;
    }

    /**
    * 追加解析结果。
    *
    * @param result 结果
     */
    public void addResult(Object result) {
        if (result != null) {
            results.add(result);
        }
    }

    /**
    * 已收集的解析结果。
    *
    * @return 结果列表
     */
    public List<Object> results() {
        return List.copyOf(results);
    }
}
