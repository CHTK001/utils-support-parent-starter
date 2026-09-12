package com.chua.deeplearning.support.arcsoft;

import com.arcsoft.face.FaceEngine;

/**
* arcsoft 人脸引擎工厂。
*
* <p>封装 {@link FaceEngine} 的创建逻辑，统一处理 SDK 库加载与异常，
* 供 {@link ArcFaceModelProvider} 等模块复用。</p>
*
* @author CH
* @since 4.0.0.42
 */
public final class ArcFaceEngineFactory {

    /** 创建 arcfaceengine工厂 实例 */
    private ArcFaceEngineFactory() {
    }

    /**
    * 创建一个新的 arcsoft 人脸引擎实例。
    *
    * <p>调用无参构造函数创建底层 {@link FaceEngine}。
    * 若 SDK 动态库加载失败或环境异常，将抛出运行时异常。</p>
    *
    * @return 新创建的 {@link FaceEngine} 实例
     */
    public static FaceEngine create() {
        return new FaceEngine();
    }
}