package com.chua.deeplearning.support.onnx.text;

/**
 * tokenizer                       
 * <p>
 * token IDs         attention mask              
 * <p>
 * inputIds: token IDs              
 * attentionMask:                     mask              
 * validTokenCount:                 token                
 * sequenceLength: inputIds                      
 * isValid:                   
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
     * inputIds                      
     *
     * @return inputIds              
     */
    public long[] getInputIds() {
        return inputIds;
    }

    /**
     * attentionMask                     mask              
     *
     * @return attentionMask          
     */
    public long[] getAttentionMask() {
        return attentionMask;
    }

    /**
     *                 token                
     *
     * @return validTokenCount         
     */
    public int getValidTokenCount() {
        return validTokenCount;
    }

    /**
     * inputIds                       
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
