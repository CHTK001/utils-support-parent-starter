package com.chua.playwright.support.engine;

import com.chua.playwright.support.PlaywrightException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 playwright-Java 的浏览器自动化引擎。
 *
 * <p>引擎维护句柄到 Playwright 对象的注册表，将浏览器操作转发到官方 playwright-Java API。
 * API 请求上下文使用 JDK HTTP 客户端执行真实网络请求，确保网络操作不会被占位异常吞掉。</p>
 *
 * @author CH
 * @since 4.0.0
 */
public class JavaEngine implements Engine {

    private Playwright playwright;
    private final Map<Long, Object> registry = new ConcurrentHashMap<>();
    private volatile long apiRequestHandle = -1;
    private long nextHandle = 1;

    /**
     * 创建 Playwright 实例。
     *
     * @return Playwright 实例
     */
    private synchronized Playwright playwright() {
        if (playwright == null) {
            playwright = Playwright.create();
        }
        return playwright;
    }

    /**
     * 分配新的句柄。
     *
     * @return 新句柄
     */
    private synchronized long alloc() {
        return nextHandle++;
    }

    /**
     * 按类型获取注册对象。
     *
     * @param handle 对象句柄
     * @param type 对象类型
     * @param <T> 对象类型
     * @return 注册对象
     */
    @SuppressWarnings("unchecked")
    private <T> T get(long handle, Class<T> type) {
        Object value = registry.get(handle);
        if (!type.isInstance(value)) {
            throw new PlaywrightException("handle " + handle + " 不是 " + type.getSimpleName());
        }
        return (T) value;
    }

    /**
     * 启动 Chromium 浏览器。
     *
     * @param headless 是否无头运行
     * @param executablePath 浏览器可执行文件路径
     * @param args 浏览器启动参数
     * @return 浏览器句柄
     */
    @Override
    public long launch(boolean headless, String executablePath, List<String> args) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(args);
        if (executablePath != null && !executablePath.isBlank()) {
            options.setExecutablePath(Paths.get(executablePath));
        }
        return register(playwright().chromium().launch(options));
    }

    /**
     * 创建浏览器上下文。
     *
     * @param browserHandle 浏览器句柄
     * @param options 上下文选项
     * @return 浏览器上下文句柄
     */
    @Override
    public long newContext(long browserHandle, Map<String, Object> options) {
        Browser browser = get(browserHandle, Browser.class);
        return register(browser.newContext());
    }

    /**
     * 创建页面。
     *
     * @param targetHandle 浏览器或浏览器上下文句柄
     * @return 页面句柄
     */
    @Override
    public long newPage(long targetHandle) {
        Object target = registry.get(targetHandle);
        Page page;
        if (target instanceof BrowserContext context) {
            page = context.newPage();
        } else if (target instanceof Browser browser) {
            page = browser.newPage();
        } else {
            throw new PlaywrightException("句柄 " + targetHandle + " 不是浏览器或浏览器上下文");
        }
        return register(page);
    }

    /**
     * 导航到指定 URL。
     *
     * @param pageHandle 页面句柄
     * @param url 目标 URL
     * @param options 导航选项
     * @return 响应数据
     */
    @Override
    public ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options) {
        Page.NavigateOptions navigateOptions = options == null ? null : new Page.NavigateOptions();
        if (navigateOptions != null && options.get("timeout") != null) {
            navigateOptions.setTimeout(((Number) options.get("timeout")).doubleValue());
        }
        Response response = get(pageHandle, Page.class).navigate(url, navigateOptions);
        return response == null ? null : new ResponseData(response.status(), response.url());
    }

    /**
     * 点击页面元素或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param options 点击选项
     */
    @Override
    public void click(long handle, String selector, Map<String, Object> options) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.click();
        } else {
            get(handle, Page.class).click(selector);
        }
    }

    /**
     * 双击页面元素或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     */
    @Override
    public void dblclick(long handle, String selector) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.dblclick();
        } else {
            get(handle, Page.class).dblclick(selector);
        }
    }

    /**
     * 填充页面输入框或元素句柄。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param value 填充值
     */
    @Override
    public void fill(long handle, String selector, String value) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.fill(value);
        } else {
            get(handle, Page.class).fill(selector, value);
        }
    }

    /**
     * 输入文本。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param text 输入文本
     */
    @Override
    public void type(long handle, String selector, String text) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.type(text);
        } else {
            get(handle, Page.class).type(selector, text);
        }
    }

    /**
     * 按下键盘按键。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param key 按键
     */
    @Override
    public void press(long handle, String selector, String key) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.press(key);
        } else {
            get(handle, Page.class).press(selector, key);
        }
    }

    /**
     * 勾选或取消勾选元素。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param checked 是否勾选
     */
    @Override
    public void check(long handle, String selector, boolean checked) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            if (checked) {
                element.check();
            } else {
                element.uncheck();
            }
        } else {
            Page page = get(handle, Page.class);
            if (checked) {
                page.check(selector);
            } else {
                page.uncheck(selector);
            }
        }
    }

    /**
     * 悬停到元素。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     */
    @Override
    public void hover(long handle, String selector) {
        Object target = registry.get(handle);
        if (target instanceof ElementHandle element) {
            element.hover();
        } else {
            get(handle, Page.class).hover(selector);
        }
    }

    /**
     * 获取元素文本内容。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 文本内容
     */
    @Override
    public String textContent(long handle, String selector) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.textContent() : get(handle, Page.class).textContent(selector);
    }

    /**
     * 获取元素内部文本。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 内部文本
     */
    @Override
    public String innerText(long handle, String selector) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.innerText() : get(handle, Page.class).innerText(selector);
    }

    /**
     * 获取元素 HTML。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return HTML 内容
     */
    @Override
    public String innerHTML(long handle, String selector) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.innerHTML() : get(handle, Page.class).innerHTML(selector);
    }

    /**
     * 获取元素属性。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @param name 属性名
     * @return 属性值
     */
    @Override
    public String getAttribute(long handle, String selector, String name) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.getAttribute(name) : get(handle, Page.class).getAttribute(selector, name);
    }

    /**
     * 获取输入框值。
     *
     * @param handle 页面或元素句柄
     * @param selector 元素选择器
     * @return 输入值
     */
    @Override
    public String inputValue(long handle, String selector) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.inputValue() : get(handle, Page.class).inputValue(selector);
    }

    /**
     * 获取页面截图。
     *
     * @param handle 页面句柄
     * @param options 截图选项
     * @return PNG 或 JPEG 字节
     */
    @Override
    public byte[] screenshot(long handle, Map<String, Object> options) {
        return get(handle, Page.class).screenshot(screenshotOptions(options));
    }

    /**
     * 在页面或元素中执行 JavaScript。
     *
     * @param handle 页面或元素句柄
     * @param expression JavaScript 表达式
     * @param arg 可选参数
     * @return 执行结果
     */
    @Override
    public Object evaluate(long handle, String expression, Object arg) {
        Object target = registry.get(handle);
        return target instanceof ElementHandle element ? element.evaluate(expression) : get(handle, Page.class).evaluate(expression, arg);
    }

    /**
     * 查询单个元素。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @return 元素句柄；未找到时返回 -1
     */
    @Override
    public long querySelector(long handle, String selector) {
        ElementHandle element = get(handle, Page.class).querySelector(selector);
        return element == null ? -1 : register(element);
    }

    /**
     * 查询多个元素。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @return 元素句柄列表
     */
    @Override
    public List<Long> querySelectorAll(long handle, String selector) {
        List<ElementHandle> elements = get(handle, Page.class).querySelectorAll(selector);
        List<Long> result = new ArrayList<>();
        for (ElementHandle element : elements) {
            result.add(register(element));
        }
        return result;
    }

    /**
     * 等待选择器出现。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @param timeoutMs 超时时间
     */
    @Override
    public void waitForSelector(long handle, String selector, Long timeoutMs) {
        Page.WaitForSelectorOptions options = timeoutMs == null
                ? null : new Page.WaitForSelectorOptions().setTimeout(timeoutMs);
        get(handle, Page.class).waitForSelector(selector, options);
    }

    /**
     * 设置页面视口大小。
     *
     * @param handle 页面句柄
     * @param width 宽度
     * @param height 高度
     */
    @Override
    public void setViewportSize(long handle, int width, int height) {
        get(handle, Page.class).setViewportSize(width, height);
    }

    /**
     * 获取页面标题。
     *
     * @param handle 页面句柄
     * @return 页面标题
     */
    @Override
    public String title(long handle) {
        return get(handle, Page.class).title();
    }

    /**
     * 获取页面 URL。
     *
     * @param handle 页面句柄
     * @return 页面 URL
     */
    @Override
    public String url(long handle) {
        return get(handle, Page.class).url();
    }

    /**
     * 重新加载页面。
     *
     * @param handle 页面句柄
     */
    @Override
    public void reload(long handle) {
        get(handle, Page.class).reload();
    }

    /**
     * 导航到上一页。
     *
     * @param handle 页面句柄
     * @return 响应数据
     */
    @Override
    public ResponseData goBack(long handle) {
        Response response = get(handle, Page.class).goBack();
        return response == null ? null : new ResponseData(response.status(), response.url());
    }

    /**
     * 导航到下一页。
     *
     * @param handle 页面句柄
     * @return 响应数据
     */
    @Override
    public ResponseData goForward(long handle) {
        Response response = get(handle, Page.class).goForward();
        return response == null ? null : new ResponseData(response.status(), response.url());
    }

    /**
     * 选择下拉选项。
     *
     * @param handle 页面句柄
     * @param selector 元素选择器
     * @param values 选项值
     * @return 选中的值
     */
    @Override
    public List<String> selectOption(long handle, String selector, String[] values) {
        return get(handle, Page.class).selectOption(selector, values);
    }

    /**
     * 关闭页面、浏览器、上下文、元素或 API 请求上下文。
     *
     * @param handle 对象句柄
     */
    @Override
    public void close(long handle) {
        Object value = registry.remove(handle);
        if (value instanceof Browser browser) {
            browser.close();
        } else if (value instanceof Page page) {
            page.close();
        } else if (value instanceof BrowserContext context) {
            context.close();
        } else if (value instanceof ElementHandle element) {
            element.dispose();
        }
    }

    /**
     * 创建独立的 HTTP API 请求上下文。
     *
     * @return API 请求上下文句柄
     */
    @Override
    public long newAPIRequest() {
        apiRequestHandle = register(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build());
        return apiRequestHandle;
    }

    /**
     * 执行 API 请求。
     *
     * @param action 请求动作
     * @param url 请求 URL
     * @param body 请求体
     * @return API 响应数据
     */
    @Override
    public ApiResponseData apiRequest(String action, String url, Object body) {
        if (apiRequestHandle < 0) {
            throw new PlaywrightException("尚未创建 API 请求上下文");
        }
        HttpClient client = get(apiRequestHandle, HttpClient.class);
        String method = action.toUpperCase(Locale.ROOT);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30));
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null && !"GET".equals(method) && !"HEAD".equals(method)) {
            try {
                String requestBody = body instanceof String value ? value : new ObjectMapper().writeValueAsString(body);
                publisher = HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8);
                builder.header("Content-Type", "application/json");
            } catch (Exception e) {
                throw new PlaywrightException("API 请求体序列化失败", e);
            }
        }
        try {
            HttpResponse<String> response = client.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponseData(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaywrightException("API 请求被中断", e);
        } catch (Exception e) {
            throw new PlaywrightException("API 请求失败: " + url, e);
        }
    }

    /**
     * 批量执行浏览器命令。
     *
     * @param commands 命令列表
     * @param stopOnError 是否遇到错误停止
     * @return 命令执行结果
     */
    @Override
    public List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError) {
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < commands.size(); i++) {
            Map<String, Object> command = commands.get(i);
            try {
                results.add(executeBatchCommand(command));
            } catch (RuntimeException e) {
                if (stopOnError) {
                    throw e;
                }
                results.add(Map.of("error", String.valueOf(e.getMessage())));
            }
        }
        return results;
    }

    /**
     * 获取 Playwright 版本。
     *
     * @return Playwright 版本
     */
    @Override
    public String version() {
        return "1.48.0-java";
    }

    /**
     * 将当前页面导出为 PDF。
     *
     * @param pageHandle 页面句柄
     * @param options PDF 选项
     * @return PDF 字节的 Base64 编码
     */
    @Override
    public String printPageToPdf(long pageHandle, Map<String, Object> options) {
        Page.PdfOptions pdfOptions = new Page.PdfOptions();
        if (options != null) {
            if (options.get("format") != null) {
                pdfOptions.setFormat(String.valueOf(options.get("format")));
            }
            if (options.get("landscape") != null) {
                pdfOptions.setLandscape(Boolean.TRUE.equals(options.get("landscape")));
            }
            if (options.get("printBackground") != null) {
                pdfOptions.setPrintBackground(Boolean.TRUE.equals(options.get("printBackground")));
            }
            if (options.get("scale") != null) {
                pdfOptions.setScale(((Number) options.get("scale")).doubleValue());
            }
            if (options.get("pageRanges") != null) {
                pdfOptions.setPageRanges(String.valueOf(options.get("pageRanges")));
            }
        }
        return Base64.getEncoder().encodeToString(get(pageHandle, Page.class).pdf(pdfOptions));
    }

    /**
     * 将 HTML 内容加载到页面并导出 PNG。
     *
     * @param pageHandle 页面句柄
     * @param html HTML 内容
     * @return PNG 字节的 Base64 编码
     */
    @Override
    public String convertHtmlToPng(long pageHandle, String html) {
        Page page = get(pageHandle, Page.class);
        page.setContent(html);
        return Base64.getEncoder().encodeToString(page.screenshot());
    }

    /**
     * 注册 Playwright 对象并返回句柄。
     *
     * @param value Playwright 对象
     * @return 对象句柄
     */
    private long register(Object value) {
        long handle = alloc();
        registry.put(handle, value);
        return handle;
    }

    /**
     * 创建截图选项。
     *
     * @param options 原始选项
     * @return Playwright 截图选项
     */
    private static Page.ScreenshotOptions screenshotOptions(Map<String, Object> options) {
        Page.ScreenshotOptions screenshotOptions = new Page.ScreenshotOptions();
        if (options != null && options.get("fullPage") != null) {
            screenshotOptions.setFullPage(Boolean.TRUE.equals(options.get("fullPage")));
        }
        return screenshotOptions;
    }

    /**
     * 执行单条批量命令。
     *
     * @param command 命令
     * @return 命令结果
     */
    private Object executeBatchCommand(Map<String, Object> command) {
        String action = String.valueOf(command.get("action"));
        Number handleValue = (Number) command.get("handle");
        long handle = handleValue == null ? 0 : handleValue.longValue();
        Map<String, Object> params = (Map<String, Object>) command.get("params");
        return switch (action) {
            case "launch" -> Map.of("handle", launch(
                    params != null && Boolean.TRUE.equals(params.get("headless")),
                    params != null ? (String) params.get("executablePath") : null,
                    params != null ? castArgs(params.get("args")) : List.of()));
            case "newPage" -> Map.of("handle", newPage(handle));
            case "goto" -> {
                ResponseData response = gotoPage(handle, (String) params.get("url"), params);
                yield response == null ? Map.of() : Map.of("status", response.status, "url", response.url);
            }
            case "click" -> {
                click(handle, (String) params.get("selector"), params);
                yield Map.of();
            }
            case "fill" -> {
                fill(handle, (String) params.get("selector"), (String) params.get("value"));
                yield Map.of();
            }
            case "screenshot" -> screenshot(handle, params);
            case "evaluate" -> evaluate(handle, (String) params.get("expression"), params.get("arg"));
            case "close" -> {
                close(handle);
                yield Map.of();
            }
            default -> throw new PlaywrightException("不支持的批量命令: " + action);
        };
    }

    /**
     * 将参数转换为浏览器启动参数列表。
     *
     * @param args 原始参数
     * @return 启动参数列表
     */
    @SuppressWarnings("unchecked")
    private static List<String> castArgs(Object args) {
        return args instanceof List<?> list ? (List<String>) list : List.of();
    }
}
