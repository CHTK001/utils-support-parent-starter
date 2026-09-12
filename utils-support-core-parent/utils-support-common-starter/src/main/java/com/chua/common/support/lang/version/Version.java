package com.chua.common.support.lang.version;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
* 版本号解析与比较工具类。
*
* <p>支持语义化版本号解析、预发布版本识别、版本号比较等功能。
*
* <pre>
* Version 1.7.3-rc2.xyz
* +-------+   +-------+   +-------+   +-------+
* String  |   1   | . |   7   | . | 3-rc2 | . |  xyz  |
* +-------+   +-------+   +-------+   +-------+
* |           |         |  |          |
* major  [1] <--            |         |   ----      |
* minor  [7] <--------------          |       | ----
* patch  [3] <------------------------        ||
* ...                            +------------+
* suffix  |  -rc2.xyz  |
* +------------+
* -------------------------------------------------------------------------
* suffix compare logic                          ||
* -----  -----
* |            |
* +-------+    +-------+
* detected pre-release  |  rc2  |    | .xyz  |  ignored part
* +-------+    +-------+
* ||
* ---  ---
* |        |
* +----+    +---+
* | rc |    | 2 |  pre-release build
* +----+    +---+
*
* </pre>
*
* @author CH
* @since 2024/5/23
 */
@Slf4j
public class Version implements Comparable<Version> {

    /** Ver1_0_0 */
    public static final Version VER1_0_0 = new Version("1.0.0");
    /** Ver0_0_1 */
    public static final Version VER0_0_1 = new Version("0.0.1");

    /**
    * 原始版本字符串。
    * 保存用户传入的原始版本字符串，用于后续解析和比较。
     */
    @Getter
    /** Original字符串 */
    private final String originalString;

    /**
    * 子版本号列表。
    * 存储版本字符串中所有数值部分的列表，例如 "1.7.3" 对应 [1, 7, 3]。
     */
    @Getter
    /** Subversionnumbers */
    private final List<Long> subversionNumbers = new ArrayList<>();

    /**
    * 修剪后的子版本号列表。
    * 移除尾部零后的子版本号列表，用于版本比较。
    * 例如 "1.7.0" 对应 [1, 7]。
     */
    @Getter
    /** Trimmedsubversionnumbers */
    private final List<Long> trimmedSubversionNumbers = new ArrayList<>();

    /**
    * 后缀字符串。
    * 存储版本号中的后缀部分，例如 "-rc2.xyz" 中的 "rc2.xyz"。
     */
    @Getter
    /**
    * 后缀
     */
    private final String suffix;

    /**
    * 发布类型。
    * 根据后缀判断的版本发布类型，如 STABLE、BETA、RC 等。
     */
    private final VersionComparator.ReleaseType releaseType;

    /**
    * 预发布版本号。
    * 从后缀中提取的预发布版本数字，用于预发布版本之间的比较。
     */
    private final long preReleaseVersion;

    /**
    * 构造一个 Version 对象。
    * 如果版本字符串无法解析，将使用默认值。
    *
    * @param versionString 代表版本的字符串
     */
    public Version(String versionString) {
        this(versionString, false);
    }

    /**
    * 根据字符串创建 Version 实例。
    *
    * @param ver 版本字符串
    * @return Version 实例
     */
    public static Version of(String ver) {
        return new Version(ver);
    }

    /**
    * 根据主版本号、次版本号和补丁版本号创建 Version 实例。
    *
    * @param major 主版本号
    * @param minor 次版本号
    * @param patch 补丁版本号
    * @return Version 实例
     */
    public static Version of(int major, int minor, int patch) {
        return of(major + "." + minor + "." + patch);
    }

    /**
    * 创建 Beta 版本的 Version 实例。
    *
    * @param major 主版本号
    * @param minor 次版本号
    * @param patch 补丁版本号
    * @return Beta 版本的 Version 实例
     */
    public static Version beta(int major, int minor, int patch) {
        return of(major + "." + minor + "." + patch + "-beta");
    }

    /**
    * 创建指定类型的预发布版本的 Version 实例。
    *
    * @param major 主版本号
    * @param minor 次版本号
    * @param patch 补丁版本号
    * @param type 预发布版本类型
    * @return 指定类型的预发布版本的 Version 实例
     */
    public static Version beta(int major, int minor, int patch, Type type) {
        return of(major + "." + minor + "." + patch + "-" + type.name().toLowerCase());
    }

    /**
    * 从 Maven jar 文件名中解析版本号。
    *
    * <p>Maven jar 文件命名规范通常为：{@code artifactId-version.jar}
    * <p>例如：
    * <ul>
    *   <li>spring-core-5.3.21.jar → 5.3.21</li>
    *   <li>commons-lang3-3.12.0.jar → 3.12.0</li>
    *   <li>junit-4.13.2-SNAPSHOT.jar → 4.13.2-SNAPSHOT</li>
    *   <li>jackson-core-2.13.3-rc1.jar → 2.13.3-rc1</li>
    * </ul>
    *
    * <p>解析规则：
    * <ol>
    *   <li>移除文件扩展名（.jar）</li>
    *   <li>从右向左查找最后一个连字符（-）</li>
    *   <li>连字符后的部分作为版本号</li>
    *   <li>如果版本号不以数字开头，则继续向左查找</li>
    * </ol>
    *
    * @param jarFileName Maven jar 文件名，可以包含或不包含 .jar 扩展名
    * @return 解析出的 Version 对象，如果无法解析则返回 null
    * @throws IllegalArgumentException 如果 jarFileName 为 null 或空字符串
     */
    public static Version parseFromMaven(String jarFileName) {
        if (jarFileName == null || jarFileName.trim().isEmpty()) {
            throw new IllegalArgumentException("jar文件名不能为null或空字符串");
        }

        String fileName = jarFileName.trim();

        // 移除 .jar 扩展名
        if (fileName.toLowerCase().endsWith(".jar")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        }

        // 如果文件名为空，返回 null
        if (fileName.isEmpty()) {
            return null;
        }

        // 从右向左查找版本号
        String versionString = extractVersionFromFileName(fileName);

        // 如果找到有效的版本字符串，创建 Version 对象
        if (versionString != null && !versionString.isEmpty()) {
            try {
                return new Version(versionString);
            } catch (Exception e) {
                // 如果版本字符串无法解析，返回 null
                return null;
            }
        }

        return null;
    }

    /**
    * 从文件名中提取版本字符串。
    *
    * @param fileName 不包含扩展名的文件名
    * @return 版本字符串，如果无法提取则返回 null
     */
    private static String extractVersionFromFileName(String fileName) {
        // 从右向左查找连字符
        int lastDashIndex = fileName.lastIndexOf('-');

        while (lastDashIndex > 0) {
            String potentialVersion = fileName.substring(lastDashIndex + 1);

            // 检查是否以数字开头（有效的版本号应该以数字开头）
            if (!potentialVersion.isEmpty() && Character.isDigit(potentialVersion.charAt(0))) {
                return potentialVersion;
            }

            // 如果不是有效版本，继续向左查找
            fileName = fileName.substring(0, lastDashIndex);
            lastDashIndex = fileName.lastIndexOf('-');
        }

        return null;
    }

    /**
    * 获取主版本号字符串。
    *
    * @return 主版本号字符串
     */
    public String getMajorVersion() {
        return getOriginalString().replace(getSuffix(), "");
    }

    /**
    * 构造 Version 对象。
    * 如果 throwExceptions 为 true 且版本字符串无法解析，则抛出异常。
    *
    * @param versionString 代表版本的字符串
    * @param throwExceptions 是否在解析失败时抛出异常
     */
    public Version(String versionString, boolean throwExceptions) {
        if (throwExceptions) {
            if (versionString == null) {
                throw new IllegalArgumentException("Argument versionString is null");
            }
            if (!VersionComparator.startsNumeric(versionString)) {
                throw new IllegalArgumentException("Argument versionString is no valid version");
            }
        }

        originalString = versionString;
        if (originalString == null || !VersionComparator.startsNumeric(originalString)) {
            if (!throwExceptions) {
                log.warn("无法解析版本字符串: {}", versionString);
            }
            suffix = "";
        } else {
            String[] versionTokens = originalString.replaceAll("\\s", "").split("\\.");
            boolean suffixFound = false;
            StringBuilder suffixSb = null;

            for (String versionToken : versionTokens) {
                if (suffixFound) {
                    suffixSb.append(".");
                    suffixSb.append(versionToken);
                } else if (VersionComparator.isNumeric(versionToken)) {
                    subversionNumbers.add(VersionComparator.safeParseLong(versionToken));
                } else {
                    for (int i = 0; i < versionToken.length(); i++) {
                        if (!Character.isDigit(versionToken.charAt(i))) {
                            suffixSb = new StringBuilder();
                            if (i > 0) {
                                subversionNumbers.add(VersionComparator.safeParseLong(versionToken.substring(0, i)));
                                suffixSb.append(versionToken.substring(i));
                            } else {
                                suffixSb.append(versionToken);
                            }
                            suffixFound = true;
                            break;
                        }
                    }
                }
            }
            suffix = (suffixSb != null) ? suffixSb.toString() : "";
            trimmedSubversionNumbers.addAll(subversionNumbers);
            while (!trimmedSubversionNumbers.isEmpty() &&
                    trimmedSubversionNumbers.lastIndexOf(0L) == trimmedSubversionNumbers.size() - 1) {
                trimmedSubversionNumbers.remove(trimmedSubversionNumbers.lastIndexOf(0L));
            }
        }
        releaseType = VersionComparator.qualifierToReleaseType(suffix);
        preReleaseVersion = VersionComparator.preReleaseVersion(suffix, releaseType);
    }

    /**
    * 返回主版本号。
    *
    * @return 主版本号，默认为 0
     */
    public long getMajor() {
        // 返回第一个子版本号，如果没有则返回 0
        if (!trimmedSubversionNumbers.isEmpty()) {
            return trimmedSubversionNumbers.get(0);
        }
        return 0L;
    }

    /**
    * 返回次版本号。
    *
    * @return 次版本号，默认为 0
     */
    public long getMinor() {
        // 返回第二个子版本号，如果没有则返回 0
        if (trimmedSubversionNumbers.size() > 1) {
            return trimmedSubversionNumbers.get(1);
        }
        return 0L;
    }

    /**
    * 返回补丁版本号。
    *
    * @return 补丁版本号，默认为 0
     */
    public long getPatch() {
        // 返回第三个子版本号，如果没有则返回 0
        if (trimmedSubversionNumbers.size() > 2) {
            return trimmedSubversionNumbers.get(2);
        }
        return 0L;
    }

    /**
    * 将版本号转换为 long 值，用于优先级比较。
    *
    * <p>转换规则：
    * <ul>
    *   <li>主版本号占高 20 位（最大 1048575）</li>
    *   <li>次版本号占中 20 位（最大 1048575）</li>
    *   <li>补丁版本号占低 20 位（最大 1048575）</li>
    *   <li>预发布版本号影响最终值（降低优先级）</li>
    * </ul>
    *
    * <p>示例：
    * <ul>
    *   <li>1.0.0 → 1099511627776L</li>
    *   <li>1.0.1 → 1099511627777L</li>
    *   <li>1.1.0 → 1099512676352L</li>
    *   <li>2.0.0 → 2199023255552L</li>
    * </ul>
    *
    * @return 版本号对应的 long 值，可用于优先级比较
     */
    public long toLong() {
        long major = getMajor();
        long minor = getMinor();
        long patch = getPatch();

        // 将版本号转换为 long 值
        // major 占高 20 位，minor 占中 20 位，patch 占低 20 位
        // 每个部分最大值为 1048575（2^20 - 1）
        // 使用位移操作：major 左移 40 位，minor 左移 20 位，patch 不变
        long result = (major << 40) | (minor << 20) | patch;

        // 如果有预发布版本，降低优先级（减去预发布版本号）
        if (releaseType != VersionComparator.ReleaseType.STABLE) {
            result -= preReleaseVersion;
        }

        return result;
    }

    /**
    * 检查当前版本是否高于指定版本的字符串表示。
    *
    * @param otherVersion 另一个版本的字符串表示
    * @return 如果当前版本高于参数版本，返回 true
     */
    public boolean isHigherThan(String otherVersion) {
        // 通过创建一个新版本对象来比较
        return isHigherThan(new Version(otherVersion));
    }

    /**
    * 检查当前版本是否高于指定版本。
    *
    * @param otherVersion 另一个版本对象
    * @return 如果当前版本高于参数版本，返回 true
     */
    public boolean isHigherThan(Version otherVersion) {
        return compareTo(otherVersion) > 0;
    }

    /**
    * 检查当前版本是否低于指定版本的字符串表示。
    *
    * @param otherVersion 另一个版本的字符串表示
    * @return 如果当前版本低于参数版本，返回 true
     */
    public boolean isLowerThan(String otherVersion) {
        return isLowerThan(new Version(otherVersion));
    }

    /**
    * 检查当前版本是否低于指定版本。
    *
    * @param otherVersion 另一个版本对象
    * @return 如果当前版本低于参数版本，返回 true
     */
    public boolean isLowerThan(Version otherVersion) {
        return compareTo(otherVersion) < 0;
    }

    /**
    * 检查当前版本是否等于指定版本的字符串表示。
    *
    * @param otherVersion 另一个版本的字符串表示
    * @return 如果当前版本等于参数版本，返回 true
     */
    public boolean isEqual(String otherVersion) {
        return isEqual(new Version(otherVersion));
    }

    /**
    * 检查当前版本是否等于指定版本。
    *
    * @param otherVersion 另一个版本对象
    * @return 如果当前版本等于参数版本，返回 true
     */
    public boolean isEqual(Version otherVersion) {
        return compareTo(otherVersion) == 0;
    }

    /**
    * 检查当前版本是否大于等于指定版本的字符串表示。
    *
    * @param otherVersion 另一个版本的字符串表示
    * @return 如果当前版本大于等于参数版本，返回 true
     */
    public boolean isAtLeast(String otherVersion) {
        return isAtLeast(new Version(otherVersion));
    }

    /**
    * 检查当前版本是否大于等于指定版本。
    *
    * @param otherVersion 另一个版本对象
    * @return 如果当前版本大于等于参数版本，返回 true
     */
    public boolean isAtLeast(Version otherVersion) {
        return compareTo(otherVersion) >= 0;
    }

    /**
    * 检查当前版本是否大于等于指定版本的字符串表示。
    *
    * @param otherVersion 另一个版本的字符串表示
    * @param ignoreSuffix 是否忽略后缀进行比较
    * @return 如果当前版本大于等于参数版本，返回 true
     */
    public boolean isAtLeast(String otherVersion, boolean ignoreSuffix) {
        return isAtLeast(new Version(otherVersion), ignoreSuffix);
    }

    /**
    * 检查当前版本是否大于等于指定版本。
    *
    * @param otherVersion 另一个版本对象
    * @param ignoreSuffix 是否忽略后缀进行比较
    * @return 如果当前版本大于等于参数版本，返回 true
     */
    public boolean isAtLeast(Version otherVersion, boolean ignoreSuffix) {
        return compareTo(otherVersion, ignoreSuffix) >= 0;
    }

    @Override
    /** 比较To */
    public final int compareTo(Version version) {
        return compareTo(version, false);
    }

    /**
    * 比较两个版本号。
    *
    * @param version 要比较的版本对象
    * @param ignoreSuffix 是否忽略后缀
    * @return 比较结果：大于 0 表示当前版本较大，小于 0 表示当前版本较小，0 表示相等
     */
    private int compareTo(Version version, boolean ignoreSuffix) {
        int versionNumberResult = VersionComparator.compareSubversionNumbers(
                trimmedSubversionNumbers,
                version.trimmedSubversionNumbers);
        if (versionNumberResult != 0 || ignoreSuffix) {
            return versionNumberResult;
        }
        int releaseTypeResult = releaseType.compareTo(version.releaseType);
        if (releaseTypeResult != 0) {
            return releaseTypeResult;
        } else {
            return Long.compare(preReleaseVersion, version.preReleaseVersion);
        }
    }

    @Override
    /** 判断相等 */
    public final boolean equals(Object o) {
        if (o instanceof Version && isEqual((Version) o)) {
            return true;
        }
        return false;
    }

    @Override
    /** HashCode */
    public final int hashCode() {
        int result = trimmedSubversionNumbers.hashCode();
        result = 31 * result + releaseType.hashCode();
        result = 31 * result + Long.hashCode(preReleaseVersion);
        return result;
    }

    @Override
    /** ToString */
    public String toString() {
        return String.valueOf(originalString);
    }

    /**
    * 版本比较器内部类。
    * 提供版本号比较、发布类型判断等核心逻辑。
     */
    static final class VersionComparator {

        /** Snapshot_string */
        private static final String SNAPSHOT_STRING = "snapshot";
        /** Pre_string */
        private static final String PRE_STRING = "pre";
        /** Alpha_string */
        private static final String ALPHA_STRING = "alpha";
        /** Beta_string */
        private static final String BETA_STRING = "beta";
        /** Rc_string */
        private static final String RC_STRING = "rc";

        /**
        * 发布类型枚举。
        *
        * <p>版本类型优先级从高到低：
        * <pre>
        * ------------------------
        * order  suffix
        * ------------------------
        *   5     empty or unknown
        *   4     rc
        *   3     beta
        *   2     alpha
        *   1     pre + alpha
        *   0     snapshot
        * ------------------------
        * </pre>
         */
        enum ReleaseType {
            SNAPSHOT,
            PRE_ALPHA,
            ALPHA,
            BETA,
            RC,
            STABLE
        }

        /**
        * 比较两个版本号列表。
        *
        * @param versionNumbersA 第一个版本号列表
        * @param versionNumbersB 第二个版本号列表
        * @return 比较结果：大于 0 表示 A 版本较大，小于 0 表示 B 版本较大，0 表示相等
         */
        static int compareSubversionNumbers(final List<Long> versionNumbersA,
                final List<Long> versionNumbersB) {
            final int numbersSizeA = versionNumbersA.size();
            final int numbersSizeB = versionNumbersB.size();
            final int maxSize = Math.max(numbersSizeA, numbersSizeB);

            for (int i = 0; i < maxSize; i++) {
                long numberA = i < numbersSizeA ? versionNumbersA.get(i) : 0;
                long numberB = i < numbersSizeB ? versionNumbersB.get(i) : 0;
                if (numberA > numberB) {
                    return 1;
                } else if (numberA < numberB) {
                    return -1;
                }
            }
            return 0;
        }

        /**
        * 根据后缀判断发布类型。
        *
        * @param suffix 版本后缀
        * @return 发布类型
         */
        static ReleaseType qualifierToReleaseType(String suffix) {
            if (!suffix.isEmpty()) {
                suffix = suffix.toLowerCase();
                if (suffix.contains(RC_STRING)) {
                    return ReleaseType.RC;
                }
                if (suffix.contains(BETA_STRING)) {
                    return ReleaseType.BETA;
                }
                if (suffix.contains(ALPHA_STRING)) {
                    if (suffix.substring(0, suffix.indexOf(ALPHA_STRING)).contains(PRE_STRING)) {
                        return ReleaseType.PRE_ALPHA;
                    } else {
                        return ReleaseType.ALPHA;
                    }
                }
                if (suffix.contains(SNAPSHOT_STRING)) {
                    return ReleaseType.SNAPSHOT;
                }
            }
            return ReleaseType.STABLE;
        }

        /**
        * 计算预发布版本号。
        *
        * @param suffix 版本后缀
        * @param releaseType 发布类型
        * @return 预发布版本号
         */
        static long preReleaseVersion(final String suffix, final ReleaseType releaseType) {
            if (releaseType == ReleaseType.STABLE || releaseType == ReleaseType.SNAPSHOT) {
                return 0;
            }

            final int startIndex = indexOfQualifier(suffix, releaseType);
            if (startIndex < suffix.length()) {
                final int maxStartIndex = Math.min(startIndex + 2, suffix.length());
                if (containsNumeric(suffix.substring(startIndex, maxStartIndex))) {
                    final StringBuilder versionNumber = new StringBuilder();
                    for (int i = startIndex; i < suffix.length(); i++) {
                        final char c = suffix.charAt(i);
                        if (Character.isDigit(c)) {
                            versionNumber.append(c);
                        } else if (i != startIndex) {
                            break;
                        }
                    }
                    return safeParseLong(versionNumber.toString());
                }
            }
            return 0;
        }

        /**
        * 获取限定符在后缀中的起始索引。
        *
        * @param suffix 版本后缀
        * @param releaseType 发布类型
        * @return 限定符的起始索引
         */
        private static int indexOfQualifier(String suffix, final ReleaseType releaseType) {
            suffix = suffix.toLowerCase();
            return switch (releaseType) {
                case RC -> suffix.indexOf(RC_STRING) + RC_STRING.length();
                case BETA -> suffix.indexOf(BETA_STRING) + BETA_STRING.length();
                case ALPHA, PRE_ALPHA -> suffix.indexOf(ALPHA_STRING) + ALPHA_STRING.length();
                default -> 0;
            };
        }

        /**
        * 检查字符串是否以数字开头。
        *
        * @param str 要检查的字符串
        * @return 如果以数字开头返回 true，否则返回 false
         */
        static boolean startsNumeric(String str) {
            str = str.trim();
            return !str.isEmpty() && Character.isDigit(str.charAt(0));
        }

        /**
        * 安全地将字符串解析为 long 值。
        *
        * @param numbers 数字字符串
        * @return 解析后的 long 值
         */
        static long safeParseLong(String numbers) {
            final int MAX_LENGTH = 19;
            if (numbers.length() > MAX_LENGTH) {
                numbers = numbers.substring(0, MAX_LENGTH);
            }
            return Long.parseLong(numbers);
        }

        /**
        * 检查字符序列是否全部由数字组成。
        *
        * @param cs 要检查的字符序列
        * @return 如果全部由数字组成返回 true，否则返回 false
         */
        static boolean isNumeric(final CharSequence cs) {
            final int sz = cs.length();
            if (sz > 0) {
                for (int i = 0; i < sz; i++) {
                    if (!Character.isDigit(cs.charAt(i))) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        /**
        * 检查字符序列是否包含数字。
        *
        * @param cs 要检查的字符序列
        * @return 如果包含数字返回 true，否则返回 false
         */
        private static boolean containsNumeric(final CharSequence cs) {
            final int sz = cs.length();
            if (sz > 0) {
                for (int i = 0; i < sz; i++) {
                    if (Character.isDigit(cs.charAt(i))) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
    * 版本类型枚举。
    *
    * <p>版本类型优先级从高到低：
    * <pre>
    * ------------------------
    * order  suffix
    * ------------------------
    *   5     empty or unknown
    *   4     rc
    *   3     beta
    *   2     alpha
    *   1     pre + alpha
    *   0     snapshot
    * ------------------------
    * </pre>
    *
    * <p>该枚举定义了不同版本的类型，包括默认版本、Release Candidate（RC）、Beta、Alpha和Snapshot版本。
    * 每种版本类型都对应一个特定的后缀，标识版本的类型。
     */
    @AllArgsConstructor
    @Getter
    public enum Type {

        /**
        * 默认版本类型，没有后缀。
         */
        DEFAULT(""),

        /**
        * Release Candidate 版本类型，后缀为 "rc"。
        * 表示一个接近最终版本的测试版本，通常在正式发布前推出。
         */
        RC("rc"),

        /**
        * Beta 版本类型，后缀为 "beta"。
        * 表示软件的测试版本，通常对公众开放，收集反馈和发现错误。
         */
        BETA("beta"),

        /**
        * Alpha 版本类型，后缀为 "alpha"。
        * 表示软件的内部测试版本，通常只对开发团队成员或有限的测试人员可用。
         */
        ALPHA("alpha"),

        /**
        * Snapshot 版本类型，后缀为 "snapshot"。
        * 表示软件的快照版本，通常用于持续集成和交付过程中，表示一个时刻的不稳定版本。
         */
        SNAPSHOT("snapshot");

        /**
        * 版本类型的后缀。
        * 各版本类型的后缀字符串，标识版本的类型。
         */
        private final String suffix;
    }
}
