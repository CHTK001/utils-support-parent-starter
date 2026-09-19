package com.chua.onlyoffice.support.converter;

import com.chua.common.support.file.converter.ConvertSetting;
import com.chua.common.support.file.converter.FileConvertSystem;
import com.chua.common.support.file.converter.FileSource;
import com.chua.common.support.network.client.HttpClient;
import com.chua.common.support.network.client.HttpClientFactory;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * only办公室 文档转换器。
 *
 * <p>通过 OnlyOffice Document Server API 实现 Office 文档的在线格式转换。
 * 支持 doc/docx/xls/xlsx/ppt/pptx 等多种格式转换为 PDF。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("onlyoffice")
public class OnlyOfficeFileConvertSystem implements FileConvertSystem {

    /**
     * 支持的源文件格式列表
     */
    private static final List<String> SOURCES = List.of("doc", "docx", "xls", "xlsx", "ppt", "pptx");

    @Override
    /** 是否支持 */
    public boolean isSupported(String source, String target) {
        if (!"pdf".equals(target)) {
            return false;
        }
        return SOURCES.contains(source);
    }

    @Override
    /** 转换 */
    public void convert(FileSource source, FileSource target, ConvertSetting setting) {
        String serverUrl = System.getProperty("onlyoffice.url", "http://localhost:8088");
        try {
            byte[] fileData;
            if (source.isInputStream()) {
                fileData = source.getInputStream().readAllBytes();
            } else {
                try (FileInputStream fis = new FileInputStream(source.getPath())) {
                    fileData = fis.readAllBytes();
                }
            }

            try (HttpClient client = HttpClientFactory.getClient()) {
                var resp = client.post(serverUrl + "/ConvertService.ashx", fileData);
                if (target.isOutputStream()) {
                    target.getOutputStream().write(resp.getBody());
                } else {
                    try (OutputStream os = new FileOutputStream(target.getPath())) {
                        os.write(resp.getBody());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("OnlyOffice 转换失败", e);
        }
    }
}
