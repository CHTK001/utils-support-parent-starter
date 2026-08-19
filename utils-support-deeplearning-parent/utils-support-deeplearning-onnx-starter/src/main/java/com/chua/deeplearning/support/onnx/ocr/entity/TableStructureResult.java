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
    /** OCRitem列表 */
    private final List<OcrItem> ocrItemList;
    /** 结构字符串列表 */
    /** StructureSTR列表 */
    private final List<String> structureStrList;

    /**
     * 创建 TableStructureResult 实例
     * @param ocrItemList ocrItemList
     * @param List List
     * @param structureStrList structureStrList
     */
    public TableStructureResult(List<OcrItem> ocrItemList, List<String> structureStrList) {
        this.ocrItemList = ocrItemList;
        this.structureStrList = structureStrList;
    }

    /** 获取OcrItemList */
    public List<OcrItem> getOcrItemList() {
        return ocrItemList;
    }

    /** 获取StructureStrList */
    public List<String> getStructureStrList() {
        return structureStrList;
    }
}
