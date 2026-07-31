package com.chua.filesystem.support.filesearch.spi;

import com.chua.filesystem.support.filesearch.model.FileInfo;
import com.chua.filesystem.support.filesearch.model.FileSearchCriteria;

import java.util.List;

/**
 * 文件系统资源检索提供器 SPI 接口
 *
 * @author CH
 * @since 4.0.0
 */
public interface FileSearchProvider {

    /**
     * 搜索文件
     *
     * @param criteria 搜索条件
     * @return 文件列表
     */
    List<FileInfo> searchFiles(FileSearchCriteria criteria);
}
