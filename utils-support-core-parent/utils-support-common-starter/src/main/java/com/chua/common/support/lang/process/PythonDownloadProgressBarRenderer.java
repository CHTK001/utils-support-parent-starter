package com.chua.common.support.lang.process;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* Python 下载风格进度条渲染器。
* 模仿 Python tqdm 下载进度条的显示风格。
*
* @author CH
* @since 1.0.0
* @version 1.0.0
 */
public class PythonDownloadProgressBarRenderer implements ProgressBarRenderer {

    /**
    * 进度条样式
     */
    private final ProgressBarStyle style;

    /**
    * 进度单位
     */
    private final ProgressUnit unit;

    /**
    * 单位名称
     */
    private final String unitName;

    /**
    * 单位大小
     */
    private final long unitSize;

    /**
    * 是否显示速度
     */
    private final boolean isSpeedShown;

    /**
    * 速度格式
     */
    private final DecimalFormat speedFormat;

    /**
    * 速度单位
     */
    private final ChronoUnit speedUnit;

    /**
    * 是否显示预计剩余时间
     */
    private final boolean isEtaShown;

    /**
    * 预计剩余时间计算函数
     */
    private final Function<ProgressState, Optional<Duration>> eta;

    /**
    * Python 下载风格渲染器构造函数
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
     */
    public PythonDownloadProgressBarRenderer(
            ProgressBarStyle style,
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean isSpeedShown,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean isEtaShown,
            Function<ProgressState, Optional<Duration>> eta
    ) {
        this.style = style;
        this.unit = unit;
        this.unitName = unitName;
        this.unitSize = unitSize;
        this.isSpeedShown = isSpeedShown;
        this.speedFormat = isSpeedShown && speedFormat == null ? new DecimalFormat("#.0") : speedFormat;
        this.speedUnit = speedUnit;
        this.isEtaShown = isEtaShown;
        this.eta = eta;
    }

    /**
    *                
    *
    * @param progress             
    * @param maxLength             
    * @return                               
     */
    @Override
    public String render(ProgressState progress, int maxLength) {
        if (maxLength <= 0) {
            return "";
        }

        //                                                 
        String taskName = progress.getTaskName();
        String downloadInfo = formatDownloadInfo(progress);
        String percentage = String.format("%.1f%%", progress.getNormalizedProgress() * 100);
        
        //                      
        String prefix = taskName + " " + downloadInfo + " ";
        String suffix = " " + percentage;
        
        //                                                             
        if (isSpeedShown) {
            String speedString = getDownloadSpeedString(progress);
            suffix += " " + speedString;
        }
        
        //                   
        if (isEtaShown) {
            String etaString = getEtaString(progress);
            String elapsedString = Util.formatDuration(progress.getTotalElapsed());
            suffix += "     " + elapsedString + "     " + etaString;
        }

        int prefixLength = getStringDisplayLength(prefix);
        int suffixLength = getStringDisplayLength(suffix);
        int progressBarLength = Math.max(20, maxLength - prefixLength - suffixLength);

        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        
        //                      
        sb.append(renderProgressBar(progress, progressBarLength));
        
        sb.append(suffix);
        
        return sb.toString();
    }

    /**
    *                                  /            
    *
    * @param progress             
    * @return                         
     */
    private String formatDownloadInfo(ProgressState progress) {
        if (progress.max <= 0) {
            return formatFileSize(progress.current) + "/      ";
        }
        return formatFileSize(progress.current) + "/" + formatFileSize(progress.max);
    }

    /**
    *                      
    *
    * @param bytes          
    * @return                                  
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1fKB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
    *                            
    *
    * @param progress             
    * @param length                
    * @return                   
     */
    private String renderProgressBar(ProgressState progress, int length) {
        StringBuilder sb = new StringBuilder();
        
        if (progress.indefinite) {
            //                                                                
            double pos = (progress.current * 0.5) % length;
            for (int i = 0; i < length; i++) {
                double distance = Math.abs(i - pos);
                if (distance <= 1.0) {
                    //                            
                    sb.append("\u001b[95m   \u001b[0m");
                } else if (distance <= 2.5) {
                    //                            
                    sb.append("\u001b[35m   \u001b[0m");
                } else if (distance <= 4.0) {
                    //                         
                    sb.append("\u001b[90m   \u001b[0m");
                } else {
                    //                   
                    sb.append("\u001b[90m   \u001b[0m");
                }
            }
        } else {
            //                                           
            double progressRatio = progress.getNormalizedProgress();
            int filledLength = (int) (length * progressRatio);
            
            //                                     
            //          
            sb.append("\u001b[95m");
            for (int i = 0; i < filledLength; i++) {
                if (i < filledLength - 1) {
                    //          
                    sb.append("   ");
                } else {
                    //                                     
                    double fraction = (length * progressRatio) - filledLength;
                    if (fraction > 0.75) {
                        //             
                        sb.append("   ");
                    } else if (fraction > 0.5) {
                        //          
                        sb.append("   ");
                    } else if (fraction > 0.25) {
                        //          
                        sb.append("   ");
                    } else {
                        //             
                        sb.append("   ");
                    }
                }
            }
            
            //                               
            //          
            sb.append("\u001b[90m");
            for (int i = filledLength; i < length; i++) {
                //                
                sb.append("   ");
            }
            
            //             
            sb.append("\u001b[0m");
        }
        
        return sb.toString();
    }

    /**
    *                            
    *
    * @param progress             
    * @return                      
     */
    private String getDownloadSpeedString(ProgressState progress) {
        Duration elapsed = progress.getTotalElapsed();
        if (elapsed.isZero() || elapsed.toMillis() < 100) {
            return "0B/s";
        }
        
        //                            /      
        double bytesPerSecond = (double) progress.current / elapsed.toMillis() * 1000;
        return formatFileSize((long) bytesPerSecond) + "/s";
    }

    /**
    *                                  
    *
    * @param progress             
    * @return                            
     */
    private String getEtaString(ProgressState progress) {
        if (eta == null) {
            return "--:--:--";
        }
        
        Optional<Duration> etaOpt = eta.apply(progress);
        if (etaOpt.isPresent()) {
            return Util.formatDuration(etaOpt.get());
        } else {
            return "--:--:--";
        }
    }

    /**
    *                                     ANSI               
    *
    * @param str          
    * @return             
     */
    private int getStringDisplayLength(String str) {
        if (str == null) {
            return 0;
        }
        //       ANSI                           
        String cleaned = str.replaceAll("\\u001b\\[[0-9;]*m", "");
        return cleaned.length();
    }

    /**
    *       Python Download                           
    *
    * @param unit             
    * @param unitName             
    * @param unitSize             
    * @param showSpeed                   
    * @param speedFormat             
    * @param speedUnit             
    * @param showEta                               
    * @param eta                               
    * @return Python Download                     
     */
    public static PythonDownloadProgressBarRenderer create(
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean showSpeed,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean showEta,
            Function<ProgressState, Optional<Duration>> eta
    ) {
        return new PythonDownloadProgressBarRenderer(
                ProgressBarStyle.PYTHON_DOWNLOAD,
                unit,
                unitName,
                unitSize,
                showSpeed,
                speedFormat,
                speedUnit,
                showEta,
                eta
        );
    }
}