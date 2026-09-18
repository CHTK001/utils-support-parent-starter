package com.chua.deeplearning.support.onnx.vggt;

import lombok.Data;

/**
* VGGT             
* <p>
*        VGGT                                      3D                      
*
* @author CH
* @版本 4.0.0.32
* @since 2024/11/08
 */
@Data
public class VggtOutput {

    /**
    * 3D                   
    * <p>
    *                                                    
    * -        (xyz)
    * -        (scale)
    * -        (rotation quaternion)
    * -              (opacity)
    * -              (SH coefficients)             
    */
    private float[] gaussianData;

    /**
    *             
    * <p>
    * [num_gaussians, 特征_dim]
    */
    private long[] shape;

    /**
    * 3D                  
    */
    private int numGaussians;

    /**
    *                            
    */
    private int featureDimension;

    /**
    *             
    *
    * @param gaussianData                   
    * @param shape             
    */
    public VggtOutput(float[] gaussianData, long[] shape) {
        this.gaussianData = gaussianData;
        this.shape = shape;

        //                                                                         
        if (shape != null && shape.length >= 2) {
            long gaussianCount = 1L;
            for (int i = 0; i < shape.length - 1; i++) {
                gaussianCount *= Math.max(1L, shape[i]);
            }
            this.numGaussians = (int) gaussianCount;
            this.featureDimension = (int) Math.max(1L, shape[shape.length - 1]);
        } else if (shape != null && shape.length == 1) {
            //                                                                      
            this.numGaussians = gaussianData == null || gaussianData.length == 0 ? 0 : 1;
            this.featureDimension = (int) Math.max(1L, shape[0]);
        }
    }

    /**
    *             
    *
    * @return true                       3D       
    */
    public boolean isValid() {
        return gaussianData != null && gaussianData.length > 0 && numGaussians > 0;
    }

    /**
    *                               
    *
    * @return             
    */
    public long getDataSizeBytes() {
        return gaussianData != null ? (long) gaussianData.length * Float.BYTES : 0;
    }
}
