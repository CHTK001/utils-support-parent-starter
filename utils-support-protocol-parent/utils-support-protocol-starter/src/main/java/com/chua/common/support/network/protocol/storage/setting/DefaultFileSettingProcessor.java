package com.chua.common.support.network.protocol.storage.setting;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.network.protocol.storage.FileStorageProcessorContext;
import lombok.extern.slf4j.Slf4j;

import static com.chua.common.support.core.constant.NameConstant.DEFAULT;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认文件设置处理器
 * 
 * 处理文件格式转换功能
 * 
 * @author CH
 * @since 2024/12/17
 */
@Slf4j
@Spi(DEFAULT)
public class DefaultFileSettingProcessor implements FileSettingProcessor {

    private final FileSettingManager fileSettingManager = new FileSettingManager();

    @Override
    public void process(FileStorageProcessorContext context) throws Exception {
        fileSettingManager.process(context);
    }
}