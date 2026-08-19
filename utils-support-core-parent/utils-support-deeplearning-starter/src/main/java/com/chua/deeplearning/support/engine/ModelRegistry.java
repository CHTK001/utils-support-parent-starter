/**
     * 模型注册条目。
     *
     * @param modelId             模型标识
     * @param translatorClassName DJL Translator 全限定类名
     * @param inputType           输入类型
     * @param outputType          输出类型
     * @param capabilityInterface 能力接口
     * @param relativePath        相对模型根目录 / classpath 路径，可为 null
     * @param downloadUrl         远程下载地址，空表示不下载
     * @param downloadMirrors     备用下载地址列表（主地址失败时依次尝试），可为 null
     * @param compress            下载文件是否为压缩包
     * @param downloadFileName    压缩包内目标文件名（compress=true 时生效）
 * @author CH
 */
    public record Entry