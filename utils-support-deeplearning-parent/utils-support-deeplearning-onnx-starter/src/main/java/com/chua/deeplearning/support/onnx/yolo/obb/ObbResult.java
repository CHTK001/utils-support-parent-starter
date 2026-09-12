package com.chua.deeplearning.support.onnx.yolo.obb;

import lombok.Data;

import java.util.List;


/**
* OBB             
* <p>
*                            OBB - Oriented Bounding Box                     
*
* @author CH
* @版本 4.0.0.32
* @since 2025-01-22
 */
@Data
public class ObbResult {

    /**
    * OBB              
     */
    private List<YoloRotatedBox> rotatedBoxList;

    /**
    *             
    *
    * @param rotatedBoxList                
     */
    public ObbResult(List<YoloRotatedBox> rotatedBoxList) {
        this.rotatedBoxList = rotatedBoxList;
    }
}
