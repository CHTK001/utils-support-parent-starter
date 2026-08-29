package com.chua.common.support.datasearch.typhoon.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 单个机构台风预报实体。
 *
 * <p>对应路径点 {@code forecast[]} 元素，{@code tm} 为机构名
 * （中国/日本/美国等），{@code forecastpoints} 为预报路径点列表。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TyphoonForecast {

    /** 预报机构（中国、日本、美国等） */
    private String tm;

    /** 预报路径点 */
    private List<TyphoonForecastPoint> forecastpoints;
}
