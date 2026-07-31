package com.chua.common.support.lang.script.marker.listener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件脚本源码监听器。
 *
 * <p>监听本地脚本文件的变化，通过比较文件最后修改时间判断源码是否变更。
 * 首次调用 {@link #isChange()} 时强制读取文件内容，确保初始加载。</p>
 *
 * <p>工作原理：
 * <ol>
 *   <li>构造时传入脚本文件路径</li>
 *   <li>首次调用 isChange() 返回 true，触发 {@link #getSource()} 读取内容</li>
 *   <li>后续调用通过 {@link Files#getLastModifiedTime} 比较时间戳</li>
 * </ol></p>
 *
 * @author CH
 * @since 4.0.0.42
 * @see Listener
 */
public class FileScriptListener implements Listener {

    /**
     * 脚本文件路径
     */
    private final Path scriptPath;

    /**
     * 上次记录的文件最后修改时间戳（毫秒）
     */
    private long lastModified;

    /**
     * 上次读取的脚本源码内容
     */
    private String lastContent;

    /**
     * 构造文件脚本监听器。
     *
     * @param scriptPath 脚本文件路径
     */
    public FileScriptListener(Path scriptPath) {
        this.scriptPath = scriptPath;
    }

    @Override
    public boolean isChange() {
        if (!Files.exists(scriptPath)) {
            return false;
        }
        try {
            long current = Files.getLastModifiedTime(scriptPath).toMillis();
            if (lastContent == null || current != lastModified) {
                lastModified = current;
                lastContent = Files.readString(scriptPath, StandardCharsets.UTF_8);
                return true;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getSource() {
        if (lastContent == null) {
            isChange();
        }
        return lastContent;
    }
}
