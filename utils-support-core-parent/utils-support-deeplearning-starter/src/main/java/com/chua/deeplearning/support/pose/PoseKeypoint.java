package com.chua.deeplearning.support.pose;

/**
* 姿态关键点。
* <p>描述单个人体关键点的名称、坐标和置信度。</p>
*
* @param name       关键点名称（如 "nose", "left_eye"）
* @param x          x 坐标
* @param y          y 坐标
* @param confidence 置信度
* @author CH
* @since 4.0.0.42
 */
public record PoseKeypoint(
        String name,
        float x,
        float y,
        float confidence) {
}
