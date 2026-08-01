package com.chua.filesearch.support.spi.impl;

import com.chua.filesearch.support.bridge.RustFileSearchHelper;
import com.chua.filesystem.support.filesearch.model.FileInfo;
import com.chua.filesystem.support.filesearch.model.FileSearchCriteria;
import com.chua.filesystem.support.filesearch.spi.FileSearchProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 基于 Rust 原生库的文件搜索提供器
 *
 * @author CH
 * @since 4.0.0
 */
@Slf4j
public class NativeFileSearchProvider implements FileSearchProvider {

    public NativeFileSearchProvider() {
    }

    @Override
    public List<FileInfo> searchFiles(FileSearchCriteria criteria) {
        return RustFileSearchHelper.search(criteria);
    }
}
