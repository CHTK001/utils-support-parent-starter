package com.chua.deeplearning.support.onnx.ocr.entity;

import java.util.List;

/**
 * OCR         
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TableStructureResult {

    /** OCR 条目列表 */
    private final List<OcrItem> ocrItemList;
    /** 结构字符串列表 */
    private final List<String> structureStrList;

    public TableStructureResult(List<OcrItem> ocrItemList, List<String> structureStrList) {
        this.ocrItemList = ocrItemList;
        this.structureStrList = structureStrList;
    }

    public List<OcrItem> getOcrItemList() {
        return ocrItemList;
    }

    public List<String> getStructureStrList() {
        return structureStrList;
    }
}
