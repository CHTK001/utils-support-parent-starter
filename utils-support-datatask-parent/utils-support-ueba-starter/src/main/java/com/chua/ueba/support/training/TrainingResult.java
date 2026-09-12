package com.chua.ueba.support.training;

import java.nio.file.Path;

/**
* 训练结果。
*
* @param configPath       训练使用（含回写 scaler/vocab）的配置文件路径
* @param outputDir        训练产物输出目录
* @param autoEncoderModel auto编码器 模型文件名
* @param lstmModel        LSTM/GRU 模型文件名
* @param command          可执行训练命令（dry-运行 或已执行）
* @author CH
* @since 4.0.0.42
 */
public record TrainingResult(Path configPath,
                             Path outputDir,
                             String autoEncoderModel,
                             String lstmModel,
                             String command) {

}