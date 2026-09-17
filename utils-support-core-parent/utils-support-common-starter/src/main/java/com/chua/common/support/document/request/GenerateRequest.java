package com.chua.common.support.document.request;


/**
* 文档生成请求参数
*
* <p>封装文档生成所需的文件名、内容等参数。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class GenerateRequest {

    /**
    * 文件名（含扩展名）
    */
    private String fileName;

    /**
    * 获取文件名
    *
    * @return 文件名
    */
    public String getFileName() {
        return fileName;
    }

    /**
    * 设置文件名
    *
    * @param fileName 文件名
    */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
}
