package com.chua.deeplearning.support.onnx.text;

/**
* tokenizer                       
* <p>
* 令牌 ids         attention mask
* <p>
* 输入标识: 令牌 ids
* attentionmask:                     mask
* valid令牌数量:                 令牌
* sequence长度: 输入标识
* 是否valid:
*
* @author CH
* @since 2024-11-14
 */
public record TokenizerResult(
        long[] inputIds,

        long[] attentionMask,

        int validTokenCount
) {

    /**
    * 输入标识
    *
    * @return inputIds              
    */
    public long[] getInputIds() {
        return inputIds;
    }

    /**
    * attentionmask                     mask
    *
    * @return attentionMask          
    */
    public long[] getAttentionMask() {
        return attentionMask;
    }

    /**
    * 令牌
    *
    * @return validTokenCount         
    */
    public int getValidTokenCount() {
        return validTokenCount;
    }

    /**
    * 输入标识
    *
    * @return                     
    */
    public int getSequenceLength() {
        return inputIds != null ? inputIds.length : 0;
    }

    /**
    *                   
    *
    * @return true                       
    */
    public boolean isValid() {
        return validTokenCount > 0;
    }
}
