package com.chua.common.support.lang.process;

import com.chua.common.support.utils.StringUtils;

import java.io.InputStreamReader;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 终端工具类，提供跨平台的终端宽度检测和光标移动支持。
 *
 * @author Martin Vehovsky
 * @author CH
 * @since 0.9.0
 */
public class TerminalUtils {

    static final char CARRIAGE_RETURN = '\r';
    static final char ESCAPE_CHAR = '\u001b';
    static final int DEFAULT_TERMINAL_WIDTH = 120;

    private static final boolean cursorMovementSupported = false;

    static Queue<ProgressBarConsumer> activeConsumers = new ConcurrentLinkedQueue<>();

    /**
     * 获取终端宽度（使用 JLine3 库）
     *
     * @return 终端宽度
     */
    synchronized static int getTerminalWidth() {
        return getTerminalWidthCrossPlatform();
    }


    /**
     * 检测是否在 IDEA 中运行
     *
     * @return true 表示在 IDEA 中运行，false 表示不是
     */
    public static boolean isIdea() {
        return System.getenv("IDEA_INITIAL_DIRECTORY") != null ||
            System.getProperty("java.class.path", "").contains("idea_rt.jar");
    }

    /**
     * 跨平台获取终端宽度
     * <p>
     * 检测策略：
     * - Windows（优先使用 COLUMNS 环境变量，其次使用 CMD 命令）
     * - Linux（使用 stty 命令或 COLUMNS 环境变量）
     * - macOS（使用 stty 命令或 COLUMNS 环境变量）
     *
     * @return 终端宽度，无法检测时返回默认值
     */
    private static int getTerminalWidthCrossPlatform() {
        // 优先从环境变量获取
        int widthFromEnv = getTerminalWidthFromEnv();
        if (widthFromEnv > 0) {
            return widthFromEnv;
        }

        // 根据操作系统选择检测方式
        if (isWindows()) {
            return getTerminalWidthWindows();
        } else if (isUnix()) {
            return getTerminalWidthUnix();
        }

        // 默认宽度
        return DEFAULT_TERMINAL_WIDTH;
    }

    private static String osName;

    private static boolean isWindows() {
        if (osName == null) {
            osName = System.getProperty("os.name").toLowerCase();
        }
        return osName.contains("win");
    }

    private static boolean isUnix() {
        if (osName == null) {
            osName = System.getProperty("os.name").toLowerCase();
        }
        return osName.contains("nix") || osName.contains("nux") || osName.contains("mac");
    }

    /**
     * @return 终端宽度，无法获取时返回 -1
     */
    private static int getTerminalWidthFromEnv() {
        try {
            String columns = System.getenv("COLUMNS");
            if (columns != null && !columns.isEmpty()) {
                return Integer.parseInt(columns);
            }
        } catch (Exception e) {
            //                                        
        }
        return -1;
    }

    /**
     *     Windows                            
     *
     * @return             
     */
    private static int getTerminalWidthWindows() {
        try {
            //              Windows CMD                            
            Process process = Runtime.getRuntime().exec("cmd /c mode con");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new InputStreamReader(process.getInputStream(), java.nio.charset.Charset.defaultCharset()));

            String line;
            while ((line = reader.readLine()) != null) {
                //        "Columns:"    
                if (line.contains("Columns:") || line.contains("   :")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        String width = StringUtils.trimAllWhitespace(parts[1]);
                        return Integer.parseInt(width);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            //                               
        }
        return DEFAULT_TERMINAL_WIDTH;
    }

    /**
     *     Unix/Linux/Mac                            
     *
     * @return             
     */
    private static int getTerminalWidthUnix() {
        try {
            //        stty                         
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "stty size < /dev/tty"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new InputStreamReader(process.getInputStream()));

            String line = reader.readLine();
            if (line != null && !line.isEmpty()) {
                // stty size                rows columns
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    return Integer.parseInt(parts[1]);
                }
            }
            reader.close();
        } catch (Exception e) {
            //                               
        }
        return DEFAULT_TERMINAL_WIDTH;
    }

    static boolean hasCursorMovementSupport() {
        return cursorMovementSupported;
    }

    synchronized static void closeTerminal() {
    }

    static <T extends ProgressBarConsumer> Stream<T> filterActiveConsumers(Class<T> clazz) {
        return activeConsumers.stream()
            .filter(clazz::isInstance)
            .map(clazz::cast);
    }

    static String moveCursorUp(int count) {
        return ESCAPE_CHAR + "[" + count + "A" + CARRIAGE_RETURN;
    }

    static String moveCursorDown(int count) {
        return ESCAPE_CHAR + "[" + count + "B" + CARRIAGE_RETURN;
    }
}
