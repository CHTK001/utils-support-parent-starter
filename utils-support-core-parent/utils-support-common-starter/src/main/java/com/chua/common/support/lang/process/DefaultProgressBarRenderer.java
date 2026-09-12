package com.chua.common.support.lang.process;

import java.text.DecimalFormat;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.Function;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;
import static com.chua.common.support.lang.process.StringDisplayUtils.getStringDisplayLength;
import static com.chua.common.support.lang.process.StringDisplayUtils.trimDisplayLength;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 默认进度条渲染器，实现 {@link ProgressBarRenderer} 接口。
* <p>
* 提供标准的进度条文本渲染，支持样式、单位、速度、ETA 等配置。
*
* @author CH
* @since 2024-01-01
* @version 1.0.0
 */
public class DefaultProgressBarRenderer implements ProgressBarRenderer {

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
    *             
    *
    * @param style                
     */
    protected DefaultProgressBarRenderer(
            ProgressBarStyle style
    ) {
        this(
                style,
                ProgressUnitType.BYTE,
                SYMBOL_EMPTY,
                1,
                true,
                null,
                ChronoUnit.SECONDS,
                true,
                (progress) -> Optional.of(progress.getElapsedAfterStart())
        );
    }

    /**
    *             
    *
    * @param style                
    * @param unit             
    * @param unitName             
    * @param unitSize             
    * @param isSpeedShown                   
    * @param speedFormat                   
    * @param speedUnit             
    * @param isEtaShown                               
    * @param eta                               
     */
    protected DefaultProgressBarRenderer(
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
        this.speedFormat = isSpeedShown && speedFormat == null ? new DecimalFormat() : speedFormat;
        this.speedUnit = speedUnit;
        this.isEtaShown = isEtaShown;
        this.eta = eta;
    }

    /**
    *                                     
    *
    * @param progress             
    * @param length          
    * @return                   
     */
    protected int progressIntegralPart(ProgressState progress, int length) {
        return (int) (progress.getNormalizedProgress() * length);
    }

    /**
    *                                     
    *
    * @param progress             
    * @param length          
    * @return                                           
     */
    protected int progressFractionalPart(ProgressState progress, int length) {
        double p = progress.getNormalizedProgress() * length;
        double fraction = (p - Math.floor(p)) * style.fractionSymbols.length();
        return (int) Math.floor(fraction);
    }

    /**
    *                                  
    *
    * @param progress             
    * @return                                                           "?"
     */
    protected String etaString(ProgressState progress) {
        Optional<Duration> eta = this.eta.apply(progress);
        return eta.map(Util::formatDuration).orElse("?");
    }

    /**
    *                         
    *
    * @param progress             
    * @return                                  4                  
     */
    protected String percentage(ProgressState progress) {
        String res;
        if (progress.max <= 0 || progress.indefinite) {
            res = "? %";
        } else {
            res = (int) Math.floor(100.0 * progress.current / progress.max) + "%";
        }
        return Util.repeat(' ', 4 - res.length()) + res;
    }

    /**
    *                                  /            
    *
    * @param progress             
    * @return                                        NONE                     
     */
    protected String ratio(ProgressState progress) {
        if (unit == ProgressUnitType.NONE) {
            return SYMBOL_EMPTY;
        }
        String m = progress.indefinite ? "?" : unit.format(progress.max / unitSize);
        String c = unit.format(progress.current / unitSize);
        return Util.repeat(' ', m.length() - c.length()) + c + "/" + m + unitName;
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
        double speed = (double) (progress.current - progress.start) / elapsedInUnit;
        double speedWithUnit = speed / unitSize;
        return speedFormat.format(speedWithUnit) + unitName + suffix;
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

        String prefix = progress.getTaskName() + " " + percentage(progress) + " " + style.leftBracket;
        int prefixLength = getStringDisplayLength(prefix);

        if (prefixLength > maxLength) {
            prefix = trimDisplayLength(prefix, maxLength - 1);
            prefixLength = maxLength - 1;
        }

        //                         1
        int maxSuffixLength = Math.max(maxLength - prefixLength - 1, 0);

        String speedString = isSpeedShown ? speed(progress) : "";
        String suffix = style.rightBracket + " " + ratio(progress) + " ("
                + Util.formatDuration(progress.getTotalElapsed())
                + (isEtaShown ? " / " + etaString(progress) : "")
                + ") "
                + speedString + progress.extraMessage;
        int suffixLength = getStringDisplayLength(suffix);
        //                      
        if (suffixLength > maxSuffixLength) {
            suffix = trimDisplayLength(suffix, maxSuffixLength);
            suffixLength = maxSuffixLength;
        }

        int length = maxLength - prefixLength - suffixLength;

        StringBuilder sb = new StringBuilder(maxLength);
        sb.append(prefix);

        //                         
        if (progress.indefinite) {
            int pos = (int) (progress.current % length);
            sb.append(Util.repeat(style.space, pos));
            sb.append(style.block);
            sb.append(Util.repeat(style.space, length - pos - 1));
        }
        //                         
        else {
            sb.append(Util.repeat(style.block, progressIntegralPart(progress, length)));
            if (progress.current < progress.max) {
                int fraction = progressFractionalPart(progress, length);
                if (fraction != 0) {
                    sb.append(style.fractionSymbols.charAt(fraction));
                    sb.append(style.delimitingSequence);
                } else {
                    sb.append(style.delimitingSequence);
                    sb.append(style.rightSideFractionSymbol);
                }
                sb.append(Util.repeat(style.space, length - progressIntegralPart(progress, length) - 1));
            }
        }

        sb.append(suffix);
        return sb.toString();
    }
}
