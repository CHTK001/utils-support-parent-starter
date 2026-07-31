package com.chua.deeplearning.support._3d.model;

/**
 * 3D 模型格式
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum Model3DFormat {

    /**
     * GLB 格式（glTF 二进制）
     */
    GLB("glb", "model/gltf-binary"),

    /**
     * GLTF 格式（glTF JSON + 资源文件）
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
     * PLY 格式（Polygon File Format）
     */
    PLY("ply", "model/ply"),

    /**
     * STL 格式（Stereolithography）
     */
    STL("stl", "model/stl");

    private final String extension;
    private final String mimeType;

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