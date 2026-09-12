package com.chua.deeplearning.support.recognition;

import com.chua.deeplearning.support.model.PredictRectangle;
import com.chua.deeplearning.support.plate.PlateResult;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
* 车牌号识别管线磁盘回调：自动将各阶段中间数据落盘，供人工/脚本查看真实流程效果。
*
* <p>各阶段数据输出到日志：检测框、识别结果。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class PlateNumberPipelineDiskCallback implements PlateNumberPipelineCallback {

    @Override
    public void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
        log.info("[plate-callback] 检测到车牌 {} 个", boxes.size());
    }

    @Override
    public void onRecognize(PredictRectangle box, PlateResult result, int index, int total) {
        log.info("[plate-callback] 识别 {}/{}: {} ({})", index + 1, total,
                result.plateNo(), result.plateColor());
    }
}
