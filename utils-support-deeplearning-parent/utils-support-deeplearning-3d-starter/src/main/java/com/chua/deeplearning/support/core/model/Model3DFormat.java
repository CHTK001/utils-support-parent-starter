package com.chua.deeplearning.support.core.model;

/**
 * 3D 模型格式
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Model3DFormat {

    /**
     * GLB 格式（gltf 二进制）
     */
    GLB("glb", "model/gltf-binary"),

    /**
     * GLTF 格式（gltf JSON + 资源文件）
     */
    GLTF("gltf", "model/gltf+json"),

    /**
     * OBJ 格式（Wavefront）
     */
    OBJ("obj", "model/obj"),

    /**
     * FBX 格式（Autodesk FBX）
     */
    FBX("fbx", "model/fbx"),

    /**
     * PLY 格式（Polygon 文件 格式化）
     */
    PLY("ply", "model/ply"),

    /**
     * STL 格式（Stereolithography）
     */
    STL("stl", "model/stl");

    /**
     * 扩展名
    */
    private final String extension;
    /**
     * MIME 类型
    */
    private final String mimeType;

    /**
     * 构造方法，创建 Model3D格式化 实例。
     *
     * @param extension 方法入参 extension
     * @param mimeType mime类型，不允许为 null
     */
    Model3DFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    /**
     * 获取文件扩展名
     *
     * @return 扩展名
     */
    public String getExtension() {
        return extension;
    }

    /**
     * 获取 MIME 类型
     *
     * @return MIME 类型
     */
    public String getMimeType() {
        return mimeType;
    }
}
