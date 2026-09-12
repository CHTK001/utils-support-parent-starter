package com.chua.common.support.document.result;


/**
* 文档生成结果
*
* <p>封装文档生成后的结果信息，如文件路径、字节内容等。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class GenerateResult {

    /**
    * 文件字节内容
     */
    private byte[] data;

    /**
    * 获取文件字节内容
    *
    * @return 字节数组
     */
    public byte[] getData() {
        return data;
    }

    /**
    * 设置文件字节内容
    *
    * @param data 字节数组
     */
    public void setData(byte[] data) {
        this.data = data;
    }
}
