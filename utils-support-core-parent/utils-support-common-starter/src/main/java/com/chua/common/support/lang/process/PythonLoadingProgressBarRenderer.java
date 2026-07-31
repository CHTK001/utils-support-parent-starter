package com.chua.common.support.lang.process;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * Python 加载风格进度条渲染器。
 * 模仿 Python alive_progress 库的加载动画风格。
 *
 * @author CH
 * @since 1.0.0
 * @version 1.0.0
 */
public class PythonLoadingProgressBarRenderer implements ProgressBarRenderer {

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
     * Python 加载风格渲染器构造函数
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
    public PythonLoadingProgressBarRenderer(
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
        String progressInfo = String.format("(%s/%s)", unit.format(progress.current), unit.format(progress.max));
        String percentage = String.format("%.1f%%", progress.getNormalizedProgress() * 100);
        
        //                                  
        int maxTaskNameLength = Math.max(10, maxLength / 3); //                                  1/3         10         
        if (taskName != null && getStringDisplayLength(taskName) > maxTaskNameLength) {
            //                                     
            taskName = truncateString(taskName, maxTaskNameLength - 3) + "...";
        }
        
        //                      
        String prefix = taskName + progressInfo + " ";
        String suffix = " " + percentage;
        
        //                   
        if (isEtaShown) {
            String etaString = getEtaString(progress);
            String elapsedString = Util.formatDuration(progress.getTotalElapsed());
            suffix += "     " + elapsedString + "     " + etaString;
        }
        
        //                   
        if (isSpeedShown) {
            String speedString = speed(progress);
            suffix += "      " + speedString;
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
     *                      
     *
     * @param progress             
     * @return                
     */
    protected String speed(ProgressState progress) {
        String suffix = "/s";
        double elapsedSeconds = progress.getElapsedAfterStart().getSeconds();
        double elapsedInUnit = elapsedSeconds;
        
        //                   
        if (null != speedUnit) {
            switch (speedUnit) {
                case MINUTES:
                    suffix = "/min";
                    elapsedInUnit /= 60;
                    break;
                case HOURS:
                    suffix = "/h";
                    elapsedInUnit /= (60 * 60);
                    break;
                case DAYS:
                    suffix = "/d";
                    elapsedInUnit /= (60 * 60 * 24);
                    break;
                default:
            }
        }

        if (elapsedSeconds == 0) {
            return "?" + unitName + suffix;
        }
        
        //                            /      
        double bytesPerSecond = (double) (progress.current - progress.start) / elapsedInUnit;
        
        //                                                          
        if ("B".equals(unitName) || "bytes".equals(unitName)) {
            return formatDownloadSpeed(bytesPerSecond, suffix);
        }
        
        //                               
        double speedWithUnit = bytesPerSecond / unitSize;
        return speedFormat.format(speedWithUnit) + unitName + suffix;
    }
    
    /**
     *                                                    
     * 
     * @param bytesPerSecond                
     * @param timeSuffix                    /s, /min, /h   
     * @return                               
     */
    private String formatDownloadSpeed(double bytesPerSecond, String timeSuffix) {
        if (bytesPerSecond < 0) {
            return "0 B" + timeSuffix;
        }
        
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int unitIndex = 0;
        double speed = bytesPerSecond;
        
        //                            
        while (speed >= 1024 && unitIndex < units.length - 1) {
            speed /= 1024;
            unitIndex++;
        }
        
        //                                              
        DecimalFormat format;
        if (speed >= 100) {
            format = new DecimalFormat("#");  //             
        } else if (speed >= 10) {
            format = new DecimalFormat("#.#");  //             
        } else {
            format = new DecimalFormat("#.##");  //             
        }
        
        return format.format(speed) + " " + units[unitIndex] + timeSuffix;
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
            int pos = (int) (progress.current % length);
            sb.append("\u001b[95m"); //          
            for (int i = 0; i < length; i++) {
                if (i == pos) {
                    sb.append("   ");
                } else if (Math.abs(i - pos) <= 2) {
                    sb.append("   ");
                } else {
                    sb.append("\u001b[90m   \u001b[95m"); //             
                }
            }
            sb.append("\u001b[0m"); //             
        } else {
            //                                           
            double progressRatio = progress.getNormalizedProgress();
            int filledLength = (int) (length * progressRatio);
            
            //                               
            sb.append("\u001b[95m"); //          
            for (int i = 0; i < filledLength; i++) {
                sb.append("   ");
            }
            
            //                            
            sb.append("\u001b[90m"); //       
            for (int i = filledLength; i < length; i++) {
                sb.append("   ");
            }
            
            sb.append("\u001b[0m"); //             
        }
        
        return sb.toString();
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
     *                      
     *
     * @param progress             
     * @return                
     */
    private String getSpeedString(ProgressState progress) {
        if (speedFormat == null) {
            return "";
        }
        
        Duration elapsed = progress.getTotalElapsed();
        if (elapsed.isZero()) {
            return "0.0/s";
        }
        
        double speed = (double) progress.current / elapsed.toMillis() * 1000;
        return speedFormat.format(speed) + "/s";
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
     *                               
     *
     * @param str                
     * @param maxLength             
     * @return                      
     */
    private String truncateString(String str, int maxLength) {
        if (str == null || maxLength <= 0) {
            return "";
        }
        
        //       ANSI            
        String cleaned = str.replaceAll("\\u001b\\[[0-9;]*m", "");
        
        if (cleaned.length() <= maxLength) {
            return str; //                               ANSI         
        }
        
        //                
        StringBuilder result = new StringBuilder();
        int displayLength = 0;
        boolean inAnsiSequence = false;
        
        for (int i = 0; i < str.length() && displayLength < maxLength; i++) {
            char c = str.charAt(i);
            
            if (c == '\u001b' && i + 1 < str.length() && str.charAt(i + 1) == '[') {
                inAnsiSequence = true;
            }
            
            result.append(c);
            
            if (!inAnsiSequence) {
                displayLength++;
            }
            
            if (inAnsiSequence && c == 'm') {
                inAnsiSequence = false;
            }
        }
        
        return result.toString();
    }

    /**
     *       Python Loading                           
     *
     * @param unit             
     * @param unitName             
     * @param unitSize             
     * @param showSpeed                   
     * @param speedFormat             
     * @param speedUnit             
     * @param showEta                               
     * @param eta                               
     * @return Python Loading                     
     */
    public static PythonLoadingProgressBarRenderer create(
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean showSpeed,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            boolean showEta,
            Function<ProgressState, Optional<Duration>> eta
    ) {
        return new PythonLoadingProgressBarRenderer(
                ProgressBarStyle.PYTHON_LOADING,
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