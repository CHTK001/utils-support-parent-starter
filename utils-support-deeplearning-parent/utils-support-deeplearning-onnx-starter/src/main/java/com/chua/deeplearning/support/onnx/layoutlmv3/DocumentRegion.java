package com.chua.deeplearning.support.onnx.layoutlmv3;

/**
 * LayoutLMv3                
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DocumentRegion {

    private String type;
    private float confidence;
    private int x0;
    private int y0;
    private int x1;
    private int y1;

    public DocumentRegion() {
    }

    public DocumentRegion(String type, float confidence, int x0, int y0, int x1, int y1) {
        this.type = type;
        this.confidence = confidence;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getType() {
        return type;
    }

    public float getConfidence() {
        return confidence;
    }

    public int getX0() {
        return x0;
    }

    public int getY0() {
        return y0;
    }

    public int getX1() {
        return x1;
    }

    public int getY1() {
        return y1;
    }

    public static class Builder {
        private String type;
        private float confidence;
        private int x0;
        private int y0;
        private int x1;
        private int y1;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder confidence(float confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder x0(int x0) {
            this.x0 = x0;
            return this;
        }

        public Builder y0(int y0) {
            this.y0 = y0;
            return this;
        }

        public Builder x1(int x1) {
            this.x1 = x1;
            return this;
        }

        public Builder y1(int y1) {
            this.y1 = y1;
            return this;
        }

        public DocumentRegion build() {
            return new DocumentRegion(type, confidence, x0, y0, x1, y1);
        }
    }
}
