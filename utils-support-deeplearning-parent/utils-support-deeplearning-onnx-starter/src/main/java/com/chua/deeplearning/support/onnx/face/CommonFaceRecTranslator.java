package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.common.support.spi.annotations.Spi;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;


/**
 *                            
 * <p>
 *                                                 
 *                                                                
 * <p>
 *                
 * -                             
 * -            Pipeline                            
 * -                             
 * -     L2                      
 * -                                
 * <p>
 *                
 * ```java
 * //                      112x112               
 * var config = FaceRecPreprocessConfig.builder()
 *     .inputSize(112, 112)
 *     .normalize(true)
 *     .mean(0.5f, 0.5f, 0.5f)
 *     .std(0.5f, 0.5f, 0.5f)
 *     .build();
 * CommonFaceRecTranslator translator = new CommonFaceRecTranslator(config);
 * ```
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025-01-22
 */
@Slf4j
@Spi("common_face_rec")
public class CommonFaceRecTranslator implements Translator<Image, float[]> {

    /**
     *                
     */
    private final FaceRecPreprocessConfig preprocessConfig;

    /**
     *             
     *
     * @param preprocessConfig                
     */
    public CommonFaceRecTranslator() {
        this(FaceRecPreprocessConfig.builder().build());
    }

    public CommonFaceRecTranslator(FaceRecPreprocessConfig preprocessConfig) {
        this.preprocessConfig = preprocessConfig;
        log.info("                                     -             : {}x{},          : {},        Pipeline: {}",
                 preprocessConfig.getInputWidth(), preprocessConfig.getInputHeight(),
                 preprocessConfig.isNormalize(), preprocessConfig.isUsePipeline());
    }

    /**
     *                   
     *
     * @param ctx                     
     * @param input             
     * @return              NDList
     */
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        var array = input.toNDArray(ctx.getNDManager(), preprocessConfig.getImageFlag());
        NDList ndList;

        if (preprocessConfig.isUsePipeline()) {
            //        Pipeline       
            var pipeline = new Pipeline();
            if (input.getWidth() != preprocessConfig.getInputWidth() ||
                input.getHeight() != preprocessConfig.getInputHeight()) {
                pipeline.add(new Resize(preprocessConfig.getInputWidth(), preprocessConfig.getInputHeight()));
            }
            pipeline.add(new ToTensor());
            if (preprocessConfig.isNormalize()) {
                pipeline.add(new Normalize(preprocessConfig.getMean(), preprocessConfig.getStd()));
            }
            ndList = pipeline.transform(new NDList(array));
        } else {
            //                   
            if (input.getWidth() != preprocessConfig.getInputWidth() ||
                input.getHeight() != preprocessConfig.getInputHeight()) {
                array = NDImageUtils.resize(array, preprocessConfig.getInputWidth(),
                                            preprocessConfig.getInputHeight());
            }
            array = array.toType(DataType.FLOAT32, false);
            if (preprocessConfig.isNormalize()) {
                array = array.sub(preprocessConfig.getMean()[0]).div(preprocessConfig.getStd()[0]);
            }
            array = array.transpose(2, 0, 1);
            ndList = new NDList(array);
        }

        if (log.isDebugEnabled()) {
            log.debug("                  : shape={}, dtype={}", ndList.get(0).getShape(), ndList.get(0).getDataType());
        }

        return ndList;
    }

    /**
     *                   
     *
     * @param ctx                    
     * @param list              NDList
     * @return                      L2             
     */
    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        var embedding = list.get(preprocessConfig.getOutputIndex());

        // L2          
        embedding = embedding.div(embedding.norm());

        if (log.isDebugEnabled()) {
            log.debug("                  : {}", embedding.getShape());
        }

        return embedding.toFloatArray();
    }

    /**
     *                   
     *
     * @return STACK             
     */
    @Override
    public Batchifier getBatchifier() {
        return Batchifier.STACK;
    }

    /**
     *                            
     */
    @Data
    public static class FaceRecPreprocessConfig {

        /**
         *                   
         */
        private int inputWidth;

        /**
         *                   
         */
        private int inputHeight;

        /**
         *                COLOR/GRAYSCALE   
         */
        private Image.Flag imageFlag;

        /**
         *              Pipeline
         */
        private boolean usePipeline;

        /**
         *                
         */
        private boolean normalize;

        /**
         *                
         */
        private float[] mean;

        /**
         *                   
         */
        private float[] std;

        /**
         *                             0       
         */
        private int outputIndex;

        /**
         *                   
         */
        private FaceRecPreprocessConfig(Builder builder) {
            this.inputWidth = builder.inputWidth;
            this.inputHeight = builder.inputHeight;
            this.imageFlag = builder.imageFlag;
            this.usePipeline = builder.usePipeline;
            this.normalize = builder.normalize;
            this.mean = builder.mean;
            this.std = builder.std;
            this.outputIndex = builder.outputIndex;
        }

        /**
         *        Builder
         *
         * @return Builder       
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builder    
         */
        public static class Builder {

            private int inputWidth = 112;
            private int inputHeight = 112;
            private Image.Flag imageFlag = Image.Flag.COLOR;
            private boolean usePipeline = true;
            private boolean normalize = true;
            private float[] mean = new float[]{0.5f, 0.5f, 0.5f};
            private float[] std = new float[]{0.5f, 0.5f, 0.5f};
            private int outputIndex = 0;

            public Builder inputSize(int width, int height) {
                this.inputWidth = width;
                this.inputHeight = height;
                return this;
            }

            public Builder imageFlag(Image.Flag flag) {
                this.imageFlag = flag;
                return this;
            }

            public Builder usePipeline(boolean usePipeline) {
                this.usePipeline = usePipeline;
                return this;
            }

            public Builder normalize(boolean normalize) {
                this.normalize = normalize;
                return this;
            }

            public Builder mean(float... mean) {
                this.mean = mean;
                return this;
            }

            public Builder std(float... std) {
                this.std = std;
                return this;
            }

            public Builder outputIndex(int outputIndex) {
                this.outputIndex = outputIndex;
                return this;
            }

            public FaceRecPreprocessConfig build() {
                return new FaceRecPreprocessConfig(this);
            }
        }
    }
}


