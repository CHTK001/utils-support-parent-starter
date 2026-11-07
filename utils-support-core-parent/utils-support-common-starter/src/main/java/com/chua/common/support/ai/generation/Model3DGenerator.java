package com.chua.common.support.ai.generation;

/**
 * 3D 模型生成能力面。
 *
 * <p>为什么单独成一个接口，而不是挂在 {@code ChatClient} 上：3D 生成与对话/图像/视频
 * 不是同一类能力——它的产出是模型文件 URL、耗时常以分钟计、并且普遍是异步任务轮询。
 * 把它塞进对话客户端会让每个实现都被迫背一个大概率用不到的方法，也让
 * 「这个服务商到底支不支持 3D」变得不可判定。</p>
 *
 * <p>支持 3D 的实现直接实现本接口；调用方按能力取，而不是按客户端类型取：</p>
 *
 * <pre>{@code
 * if (client instanceof Model3DGenerator generator) {
 *     Model3DGenerationResult result = generator.generate3D()
 *         .prompt("一把木椅")
 *         .format("glb")
 *         .generate();
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.52
 */
public interface Model3DGenerator {

    /**
     * 生成 3D 模型。
     *
     * @param prompt         生成提示词（纯图生 3D 时可为 null）
     * @param refImageUrl    参考图 URL（文生 3D 时可为 null）
     * @param format         目标模型格式（glb / gltf / obj / usdz），null 时由实现取默认值
     * @param timeoutSeconds 超时秒数（用于异步轮询场景），≤0 时由实现取默认值
     * @return 3D 模型生成结果
     * @throws UnsupportedOperationException 当前实现不支持该格式或该生成方式
     */
    Model3DGenerationResult generate3D(String prompt, String refImageUrl, String format, int timeoutSeconds);

    /**
     * 创建 3D 生成参数构建器（链式调用）。
     *
     * <pre>{@code
     * Model3DGenerationResult result = generator.generate3D()
     *     .prompt("一把木椅")
     *     .refImageUrl("https://example.com/chair.png")
     *     .format("glb")
     *     .generate();
     * }</pre>
     *
     * @return 3D 生成参数构建器
     */
    default Model3DGenerationSpec generate3D() {
        return new Model3DGenerationSpec(this);
    }
}
