package com.chua.deeplearning.support.onnx.yolo.obb;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;


/**
 * YOLO          
 * <p>
 *                                  OBB - Oriented Bounding Box            
 *                                                       
 *
 * @author CH
 * @version 4.0.0.32
 * @since 2025-01-22
 */
@Data
public class YoloRotatedBox {

    /**
     *           X       
     */
    private float cx;

    /**
     *           Y       
     */
    private float cy;

    /**
     *       
     */
    private float w;

    /**
     *       
     */
    private float h;

    /**
     *                         
     */
    private float angle;

    /**
     *             
     */
    private String className;

    /**
     *                
     */
    private float score;

    /**
     *             
     *
     * @param cx                  X       
     * @param cy                  Y       
     * @param w               
     * @param h               
     * @param angle                             
     * @param className             
     * @param score                    
     */
    public YoloRotatedBox(float cx, float cy, float w, float h, float angle, String className, float score) {
        this.cx = cx;
        this.cy = cy;
        this.w = w;
        this.h = h;
        this.angle = angle;
        this.className = className;
        this.score = score;
    }

    /**
     *                          ProbIoU          IoU   
     * <p>
     * ProbIoU                             IoU                                     
     *
     * @param b1             1
     * @param b2             2
     * @param eps                          
     * @return ProbIoU    
     */
    public static double probiou(YoloRotatedBox b1, YoloRotatedBox b2, double eps) {
        var c1 = covarianceMatrix(b1.w, b1.h, b1.angle);
        var c2 = covarianceMatrix(b2.w, b2.h, b2.angle);

        var a1 = c1[0];
        var b1v = c1[1];
        var c1v = c1[2];
        var a2 = c2[0];
        var b2v = c2[1];
        var c2v = c2[2];

        var x1 = b1.cx;
        var y1 = b1.cy;
        var x2 = b2.cx;
        var y2 = b2.cy;

        var t1 = ((a1 + a2) * Math.pow(y1 - y2, 2) + (b1v + b2v) * Math.pow(x1 - x2, 2))
                / ((a1 + a2) * (b1v + b2v) - Math.pow(c1v + c2v, 2) + eps);
        var t2 = ((c1v + c2v) * (x2 - x1) * (y1 - y2))
                / ((a1 + a2) * (b1v + b2v) - Math.pow(c1v + c2v, 2) + eps);
        var t3 = Math.log(((a1 + a2) * (b1v + b2v) - Math.pow(c1v + c2v, 2))
                / (4 * Math.sqrt(a1 * b1v - Math.pow(c1v, 2)) * Math.sqrt(a2 * b2v - Math.pow(c2v, 2)) + eps) + eps);

        // ProbIoU       
        var bd = 0.25 * t1 + 0.5 * t2 + 0.5 * t3;
        bd = Math.max(Math.min(bd, 100.0), eps);
        var hd = Math.sqrt(1.0 - Math.exp(-bd) + eps);
        return 1 - hd;
    }

    /**
     *                      
     *
     * @param w       
     * @param h       
     * @param r                         
     * @return                       [a, b, c]
     */
    private static double[] covarianceMatrix(double w, double h, double r) {
        var a = Math.pow(w, 2) / 12.0;
        var b = Math.pow(h, 2) / 12.0;
        var cos = Math.cos(r);
        var sin = Math.sin(r);

        var aVal = a * cos * cos + b * sin * sin;
        var bVal = a * sin * sin + b * cos * cos;
        var cVal = (a - b) * sin * cos;
        return new double[]{aVal, bVal, cVal};
    }

    /**
     *           4                
     *
     * @return                                                                      
     */
    public List<Point2D> toPoints() {
        var cos = Math.cos(angle);
        var sin = Math.sin(angle);

        // vec1 = [w/2*cos, w/2*sin]
        var vec1x = w / 2.0 * cos;
        var vec1y = w / 2.0 * sin;

        // vec2 = [-h/2*sin, h/2*cos]
        var vec2x = -h / 2.0 * sin;
        var vec2y = h / 2.0 * cos;

        var points = new ArrayList<Point2D>(4);

        // pt1 = ctr + vec1 + vec2
        points.add(new Point2D((float) (cx + vec1x + vec2x), (float) (cy + vec1y + vec2y)));

        // pt2 = ctr + vec1 - vec2
        points.add(new Point2D((float) (cx + vec1x - vec2x), (float) (cy + vec1y - vec2y)));

        // pt3 = ctr - vec1 - vec2
        points.add(new Point2D((float) (cx - vec1x - vec2x), (float) (cy - vec1y - vec2y)));

        // pt4 = ctr - vec1 + vec2
        points.add(new Point2D((float) (cx - vec1x + vec2x), (float) (cy - vec1y + vec2y)));

        return points;
    }

    /**
     * 2D       
     */
    @Data
    public static class Point2D {
        /** X 坐标 */
        /** X坐标 */
        private float x;
        /** Y 坐标 */
        /** Y坐标 */
        private float y;

        public Point2D(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }
}

