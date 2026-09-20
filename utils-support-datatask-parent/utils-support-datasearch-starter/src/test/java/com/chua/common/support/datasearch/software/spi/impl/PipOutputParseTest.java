package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;

import java.util.List;

/**
 * pip 输出解析测试（样本取自 pip 24.2 真实输出）。
 *
 * <p>回归点：{@code PipSoftwareProvider.parsePipOutput} 从 {@code pip install --dry-run}
 * 的文本输出里只截取包名，{@code version} 恒为传进去的空串，{@code description} 恒为空串，
 * 而版本其实在 "Requirement already satisfied: xxx in &lt;路径&gt; (1.2.3)" 的末括号里，
 * 也在必装时的 {@code --report} JSON（{@code install[].metadata.version/summary}）里。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class PipOutputParseTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 依赖全部已满足：report 的 install 为空数组，只有 already satisfied 行
     */
    private static final String ALREADY_SATISFIED = String.join("\n",
            "Defaulting to user installation because normal site-packages is not writeable",
            "Requirement already satisfied: flask in c:\\users\\yemen\\appdata\\roaming\\python\\python312\\site-packages (3.1.3)",
            "Requirement already satisfied: blinker>=1.9.0 in c:\\users\\yemen\\appdata\\roaming\\python\\python312\\site-packages (from flask) (1.9.0)",
            "Requirement already satisfied: urllib3<1.27,>=1.21.1 in c:\\users\\yemen\\appdata\\roaming\\python\\python312\\site-packages (from requests) (1.26.20)",
            "{",
            "  \"version\": \"1\",",
            "  \"pip_version\": \"24.2\",",
            "  \"install\": [],",
            "  \"environment\": {",
            "    \"implementation_name\": \"cpython\",",
            "    \"python_full_version\": \"3.12.6\"",
            "  }",
            "}",
            "",
            "[notice] A new release of pip is available: 24.2 -> 26.2.1",
            "[notice] To update, run: python.exe -m pip install --upgrade pip");

    /**
     * 需要新装：report 的 install 带完整核心元数据（此处省略未被解析的 description 大段正文）
     */
    private static final String RESOLVED_REPORT = String.join("\n",
            "Defaulting to user installation because normal site-packages is not writeable",
            "Collecting ruff",
            "  Downloading ruff-0.16.8-py3-none-win_amd64.whl.metadata (5.1 kB)",
            "{",
            "  \"version\": \"1\",",
            "  \"pip_version\": \"24.2\",",
            "  \"install\": [",
            "    {",
            "      \"download_info\": {\"url\": \"https://files.pythonhosted.org/packages/ruff-0.16.8-py3-none-win_amd64.whl\"},",
            "      \"is_direct\": false,",
            "      \"requested\": true,",
            "      \"metadata\": {",
            "        \"metadata_version\": \"2.4\",",
            "        \"name\": \"ruff\",",
            "        \"version\": \"0.16.8\",",
            "        \"summary\": \"An extremely fast Python linter and code formatter, written in Rust.\",",
            "        \"requires_python\": \">=3.7\"",
            "      }",
            "    }",
            "  ],",
            "  \"environment\": {\"sys_platform\": \"win32\"}",
            "}",
            "",
            "[notice] A new release of pip is available: 24.2 -> 26.2.1");

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        List<SoftwareInfo> installed = PipSoftwareProvider.parsePipOutput(ALREADY_SATISFIED);
        System.out.println(dump(installed));
        check("已满足依赖可解析出条目", installed.size() == 3);
        check("版本来自末括号（修复前恒为空串）", installed.stream().allMatch(s -> !s.getVersion().isEmpty()));
        check("flask 版本正确", "3.1.3".equals(versionOf(installed, "flask")));
        check("带约束的包名已剥离约束", "blinker".equals(nameOf(installed, "blinker")));
        check("urllib3<1.27,>=1.21.1 → urllib3", "urllib3".equals(nameOf(installed, "urllib3")));
        check("传递依赖版本取安装版本而非约束", "1.9.0".equals(versionOf(installed, "blinker")));
        check("(from xxx) 括号不会被当成版本", "1.26.20".equals(versionOf(installed, "urllib3")));
        check("报告 JSON 不会被误当成软件条目", installed.stream().noneMatch(s -> "cpython".equals(s.getName())));

        List<SoftwareInfo> resolved = PipSoftwareProvider.parsePipOutput(RESOLVED_REPORT);
        System.out.println(dump(resolved));
        check("必装场景可解析出条目", resolved.size() == 1);
        check("必装版本来自报告（修复前恒为空串）", "0.16.8".equals(versionOf(resolved, "ruff")));
        check("必装描述来自 summary（修复前恒为空串）", resolved.stream()
                .anyMatch(s -> s.getDescription().startsWith("An extremely fast Python linter")));
        check("packageId 已填充", resolved.stream().allMatch(s -> !s.getPackageId().isEmpty()));

        List<SoftwareInfo> failed = PipSoftwareProvider.parsePipOutput(
                "ERROR: No matching distribution found for nosuchpkg-xyz");
        check("pip 失败时返回空列表而非脏数据", failed.isEmpty());

        System.out.println("[PipOutputParseTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String versionOf(List<SoftwareInfo> list, String name) {
        return list.stream().filter(s -> name.equals(s.getName())).map(SoftwareInfo::getVersion)
                .findFirst().orElse("<missing>");
    }

    private static String nameOf(List<SoftwareInfo> list, String prefix) {
        return list.stream().map(SoftwareInfo::getName)
                .filter(n -> n.startsWith(prefix)).findFirst().orElse("<missing>");
    }

    private static String dump(List<SoftwareInfo> list) {
        StringBuilder sb = new StringBuilder();
        for (SoftwareInfo s : list) {
            sb.append("      ").append(s.getName()).append(' ').append(s.getVersion())
                    .append(" src=").append(s.getSource())
                    .append(" desc=").append(s.getDescription()).append('\n');
        }
        return sb.toString();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
