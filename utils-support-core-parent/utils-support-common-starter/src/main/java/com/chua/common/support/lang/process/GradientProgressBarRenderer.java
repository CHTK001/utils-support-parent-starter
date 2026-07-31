package com.chua.common.support.lang.process;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 渐变进度条渲染器，支持多种颜色渐变效果。
 * <p>
 * 继承默认渲染器，提供 Python 风格的颜色渐变进度条显示。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public class GradientProgressBarRenderer extends DefaultProgressBarRenderer {

    /**
     * 渐变类型枚举，定义不同的颜色渐变方案
     * <p>
     * 每种类型对应一种颜色渐变效果。
     *
     * @author CH
     * @since 2024-01-01
     */
    public enum GradientType {
        /**
         * 灰色到橙色渐变（Python 默认风格）
         */
        GRAY_TO_ORANGE,
        /**
         * 灰色到绿色渐变
         */
        GRAY_TO_GREEN,
        /**
         * 蓝色到青色渐变
         */
        BLUE_TO_CYAN,
        /**
         * 红色到黄色渐变
         */
        RED_TO_YELLOW,
        /**
         * 彩虹渐变
         */
        RAINBOW,
        /**
         * 矩阵风格渐变
         */
        MATRIX,
        /**
         * 火焰风格渐变
         */
        FIRE,
        /**
         * 海洋风格渐变
         */
        OCEAN,
        /**
         * 霓虹风格渐变
         */
        NEON,
        /**
         * 日落风格渐变
         */
        SUNSET
    }

    /**
     * 渐变类型
     */
    private final GradientType gradientType;

    /**
     * 构造函数
     *
     * @param style         进度条样式
     * @param unit          进度单位
     * @param unitName      单位名称
     * @param unitSize      单位大小
     * @param isSpeedShown  是否显示速度
     * @param speedFormat   速度格式
     * @param speedUnit     速度单位
     * @param isEtaShown    是否显示预计剩余时间
     * @param eta           预计剩余时间计算函数
     * @param gradientType  渐变类型
     */
    public GradientProgressBarRenderer(
            ProgressBarStyle style,
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean isSpeedShown,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean isEtaShown,
            Function<ProgressState, Optional<Duration>> eta,
            GradientType gradientType
    ) {
        super(style, unit, unitName, unitSize, isSpeedShown, speedFormat, speedUnit, isEtaShown, eta);
        this.gradientType = gradientType;
    }

    /**
     *                
     *
     * @param progress             
     * @param length                
     * @return                               
     */
    @Override
    public String render(ProgressState progress, int length) {
        StringBuilder sb = new StringBuilder();

        //                
        sb.append("[");

        if (progress.indefinite) {
            //                      
            int pos = (int) (progress.current % length);
            sb.append(getColorCode(gradientType, 0.5)); //                   
            sb.append(Util.repeat(' ', pos));
            sb.append("\u001b[90m"); //       
            sb.append(Util.repeat(' ', length - pos));
            sb.append("\u001b[0m"); //             
        } else {
            //                      
            double progressRatio = (double) progress.current / progress.max;
            int filledLength = (int) (length * progressRatio);

            //                                     
            for (int i = 0; i < filledLength; i++) {
                double ratio = (double) i / length;
                sb.append(getColorCode(gradientType, ratio));
                sb.append(' ');
            }

            //                                  
            if (filledLength < length) {
                sb.append("\u001b[90m"); //       
                sb.append(Util.repeat(' ', length - filledLength));
            }

            sb.append("\u001b[0m"); //             
        }

        //                
        sb.append("]");

        return sb.toString();
    }

    /**
     *                                                 ANSI            
     *
     * @param type              
     * @param ratio              (0.0 - 1.0)
     * @return ANSI            
     */
    private String getColorCode(GradientType type, double ratio) {
        switch (type) {
            case GRAY_TO_ORANGE:
                return getGrayToOrangeColor(ratio);
            case GRAY_TO_GREEN:
                return getGrayToGreenColor(ratio);
            case BLUE_TO_CYAN:
                return getBlueToCyanColor(ratio);
            case RED_TO_YELLOW:
                return getRedToYellowColor(ratio);
            case RAINBOW:
                return getRainbowColor(ratio);
            case MATRIX:
                return getMatrixColor(ratio);
            case FIRE:
                return getFireColor(ratio);
            case OCEAN:
                return getOceanColor(ratio);
            case NEON:
                return getNeonColor(ratio);
            case SUNSET:
                return getSunsetColor(ratio);
            default:
                return "\u001b[90m"; //             
        }
    }

    /**
     *                      
     */
    private String getGrayToOrangeColor(double ratio) {
        if (ratio < 0.3) {
            return "\u001b[90m"; //          
        } else if (ratio < 0.6) {
            return "\u001b[37m"; //          
        } else if (ratio < 0.8) {
            return "\u001b[33m"; //       
        } else {
            return "\u001b[38;5;208m"; //       
        }
    }

    /**
     *                      
     */
    private String getGrayToGreenColor(double ratio) {
        if (ratio < 0.3) {
            return "\u001b[90m"; //          
        } else if (ratio < 0.6) {
            return "\u001b[37m"; //          
        } else if (ratio < 0.8) {
            return "\u001b[92m"; //          
        } else {
            return "\u001b[32m"; //       
        }
    }

    /**
     *                      
     */
    private String getBlueToCyanColor(double ratio) {
        if (ratio < 0.5) {
            return "\u001b[34m"; //       
        } else {
            return "\u001b[36m"; //       
        }
    }

    /**
     *                      
     */
    private String getRedToYellowColor(double ratio) {
        if (ratio < 0.5) {
            return "\u001b[31m"; //       
        } else {
            return "\u001b[33m"; //       
        }
    }

    /**
     *             
     */
    private String getRainbowColor(double ratio) {
        if (ratio < 0.16) {
            return "\u001b[31m"; //       
        } else if (ratio < 0.33) {
            return "\u001b[33m"; //       
        } else if (ratio < 0.5) {
            return "\u001b[32m"; //       
        } else if (ratio < 0.66) {
            return "\u001b[36m"; //       
        } else if (ratio < 0.83) {
            return "\u001b[34m"; //       
        } else {
            return "\u001b[35m"; //       
        }
    }

    /**
     *                                           
     */
    private String getMatrixColor(double ratio) {
        if (ratio < 0.2) {
            return "\u001b[30m"; //       
        } else if (ratio < 0.4) {
            return "\u001b[90m"; //          
        } else if (ratio < 0.6) {
            return "\u001b[32m"; //       
        } else if (ratio < 0.8) {
            return "\u001b[92m"; //          
        } else {
            return "\u001b[97;42m"; //                   
        }
    }

    /**
     *                                        
     */
    private String getFireColor(double ratio) {
        if (ratio < 0.25) {
            return "\u001b[31m"; //       
        } else if (ratio < 0.5) {
            return "\u001b[91m"; //          
        } else if (ratio < 0.75) {
            return "\u001b[38;5;208m"; //       
        } else {
            return "\u001b[93m"; //          
        }
    }

    /**
     *                                        
     */
    private String getOceanColor(double ratio) {
        if (ratio < 0.25) {
            return "\u001b[34m"; //       
        } else if (ratio < 0.5) {
            return "\u001b[94m"; //          
        } else if (ratio < 0.75) {
            return "\u001b[36m"; //       
        } else {
            return "\u001b[96m"; //          
        }
    }

    /**
     *                                        
     */
    private String getNeonColor(double ratio) {
        if (ratio < 0.33) {
            return "\u001b[35m"; //       
        } else if (ratio < 0.66) {
            return "\u001b[95m"; //          
        } else {
            return "\u001b[96m"; //          
        }
    }

    /**
     *                                        
     */
    private String getSunsetColor(double ratio) {
        if (ratio < 0.33) {
            return "\u001b[38;5;208m"; //       
        } else if (ratio < 0.66) {
            return "\u001b[38;5;203m"; //          
        } else {
            return "\u001b[95m"; //                      
        }
    }

    /**
     *       Python                                       
     *
     * @param unit             
     * @param unitName             
     * @param unitSize             
     * @param showSpeed                   
     * @param speedFormat                   
     * @param speedUnit             
     * @param showEta                               
     * @param eta                               
     * @return Python                                       
     */
    public static GradientProgressBarRenderer createPythonDownloadStyle(
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean showSpeed,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean showEta,
            Function<ProgressState, Optional<Duration>> eta
    ) {
        return new GradientProgressBarRenderer(
                ProgressBarStyle.PYTHON_DOWNLOAD,
                unit,
                unitName,
                unitSize,
                showSpeed,
                speedFormat,
                speedUnit,
                showEta,
                eta,
                GradientType.GRAY_TO_ORANGE
        );
    }

    /**
     *                                              
     *
     * @param unit             
     * @param unitName             
     * @param unitSize             
     * @param showSpeed                   
     * @param speedFormat                   
     * @param speedUnit             
     * @param showEta                               
     * @param eta                               
     * @return                                        
     */
    public static GradientProgressBarRenderer createRainbowStyle(
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean showSpeed,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean showEta,
            Function<ProgressState, Optional<Duration>> eta
    ) {
        return new GradientProgressBarRenderer(
                ProgressBarStyle.RAINBOW,
                unit,
                unitName,
                unitSize,
                showSpeed,
                speedFormat,
                speedUnit,
                showEta,
                eta,
                GradientType.RAINBOW
        );
    }
}