package com.chua.common.support.network.client;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 链式 HTTP 客户端请求构建器。
 *
 * <p>本类采用 <b>Builder 模式</b>，提供流式（Fluent）API 用于构建和发送 HTTP 请求。
 * 通过链式调用依次设置请求参数（URL、请求头、请求体、超时等），最后调用
 * {@code get()/post()/put()/delete()} 等方法触发实际请求执行。
 * 底层委托给 {@link HttpClientFactory} 自动选择当前环境可用的 HTTP 执行器
 * （优先级：OkHttp3 &gt; Apache HttpClient5 &gt; JDK 内置客户端）。
 *
 * <p><b>实例化方式：</b>
 * <pre>{@code
 * HttpClientBuilder builder = HttpClientFactory.of("http://api.example.com");
 * }</pre>
 *
 * <p><b>典型用法示例：</b>
 * <pre>{@code
 * // 1. GET 请求
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .path("/users")
 *     .header("Authorization", "Bearer token")
 *     .get();
 *
 * // 2. POST JSON 请求
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .path("/users")
 *     .json()
 *     .body("{\"name\":\"test\"}")
 *     .post();
 *
 * // 3. POST 表单请求（application/x-www-form-urlencoded）
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .form()
 *     .body("username", "admin")
 *     .body("password", "123456")
 *     .post();
 *
 * // 4. POST 文本字段 multipart 请求
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .formData("field1", "value1")
 *     .formData("field2", "value2")
 *     .post();
 *
 * // 5. POST 文件上传 multipart 请求
 * byte[] fileBytes = Files.readAllBytes(Paths.get("photo.png"));
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com/upload")
 *     .formData("file", fileBytes, "image/png", "photo.png")
 *     .formData("description", "A beautiful photo")
 *     .post();
 *
 * // 6. 自定义超时 + 字节数组 body
 * ClientResponse resp = HttpClientFactory.of("http://api.example.com")
 *     .connectTimeout(5000)
 *     .readTimeout(10000)
 *     .body(new byte[]{1, 2, 3})
 *     .post();
 * }</pre>
 *
 * <p><b>注意事项：</b>
 * <ul>
 *   <li>每次调用 {@code HttpClientFactory.of(url)} 都会创建新的构建器实例，不是线程安全的，每个线程应使用自己的构建器实例。</li>
 *   <li>如果 {@code baseUrl} 以 '/' 结尾，{@code path} 开头的 '/' 会被自动去除以避免双重斜杠。</li>
 *   <li>纯文本字段 {@code formData(key, value)} 在未混入文件上传时，会序列化为 {@code key1=value1&amp;key2=value2} 格式；</li>
 *   <li>一旦调用含文件的 {@code formData(key, bytes, contentType, filename)}，会自动切换到 multipart 模式，所有已有和新加的字段均使用 RFC 1341 multipart 格式（含 boundary 分隔符），并自动设置 Content-Type 头。</li>
 * </ul>
 *
 * @author CH
 */
public class HttpClientBuilder {

    /**
     * 基础 URL，在构造时传入，最终请求 URL 由 baseUrl + path 拼接而成
     */
    private final String baseUrl;

    /**
     * 请求头容器，内部使用 {@link LinkedHashMap} 保持插入顺序
     */
    private final HttpHeader headers = HttpHeader.create();

    /**
     * 请求路径（追加到 baseUrl 后形成完整的请求 URL）
     */
    private String path;

    /**
     * 请求体，支持 String、byte[] 或任意 Java 对象
     */
    private Object body;

    /**
     * 查询参数 Map，会在执行时拼接到 URL 的 query string 中
     */
    private Map<String, String> params;

    /**
     * 代理服务器主机名或 IP 地址。
     *
     * <p>当需要通过 HTTP 代理访问目标服务器时设置此项。
     * null 或空字符串表示不使用代理。</p>
     */
    private String proxyHost;

    /**
     * 代理服务器端口号。
     *
     * <p>与 {@link #proxyHost} 配合使用，当 proxyHost 为 null 时此值无效。</p>
     */
    private int proxyPort;

    /**
     * 连接超时时间（毫秒），默认 30 秒
     */
    private long connectTimeout = 30000;

    /**
     * 读取超时时间（毫秒），默认 30 秒
     */
    private long readTimeout = 30000;

    /**
     * 连接保活超时时间（毫秒），默认 60 秒
     */
    private long keepAliveTimeout = 60000;

    /**
     * 是否跟随重定向，默认 true
     */
    private boolean followRedirects = true;

    /**
     * 自定义重定向处理器
     */
    private java.util.function.Consumer<ClientResponse> redirectHandler;

    /**
     * 请求方法，默认为 GET
     */
    private HttpMethod method = HttpMethod.GET;

    /**
     * 纯文本表单字段存储（非 multipart 模式）。
     *
     * <p>当仅通过 {@link #formData(String, String)} 或 {@link #body(String, String)} 添加纯文本字段时，
     * 数据存储在此 Map 中。如果未进入 multipart 模式且 {@link #body} 为 null，
     * 执行时会序列化为 {@code key1=value1&key2=value2} 格式的请求体字符串
     * （key 和 value 会通过 {@link URLEncoder#encode(String, String)} 进行 URL 编码）。
     * 使用 {@link LinkedHashMap} 确保参数顺序与添加顺序一致。
     *
     * <p><b>注意：</b>一旦调用了带有文件参数的 {@link #formData(String, byte[], String, String)}，
     * 此 Map 中的数据会被迁移到 {@link #multipartBody} 中（转为 multipart 模式），
     * 此字段会被置为 null。
     */
    private Map<String, String> formData;

    /**
     * Multipart 请求体（multipart 模式下使用）。
     *
     * <p>当通过 {@link #formData(String, byte[], String, String)} 添加文件上传字段时，
     * 会创建此对象并进入 multipart 模式。之后无论通过哪个 {@code formData} 重载添加的字段，
     * 都会被添加到 {@link MultipartBody} 中，最终序列化为 RFC 1341 格式的 multipart 字节数组，
     * 并自动设置 Content-Type 头为 {@code multipart/form-data; boundary=...}。
     *
     * <p>null 表示当前处于非 multipart 模式（纯文本或直接 body 模式）。
     */
    private MultipartBody multipartBody;

    /**
     * 构造一个指定基础 URL 的请求构建器。
     * <p>包级访问权限，外部通过 {@link HttpClientFactory#of(String)} 工厂方法创建。</p>
     *
     * @param baseUrl 基础 URL，例如 {@code "http://localhost:8080"} 或 {@code "http://localhost:8080/"}
     */
    HttpClientBuilder(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 设置请求路径，会追加到构造时传入的 {@code baseUrl} 之后。
     *
     * <p><b>URL 拼接规则：</b></p>
     * <ul>
     *   <li>如果 baseUrl 以 '/' 结尾（如 {@code "http://host/"}），path 开头的 '/' 会被自动去除</li>
     *   <li>示例：baseUrl={@code "http://host/"}, path={@code "/api/users"} → {@code "http://host/api/users"}</li>
     *   <li>示例：baseUrl={@code "http://host"}, path={@code "/api/users"} → {@code "http://host/api/users"}</li>
     * </ul>
     *
     * @param path 请求路径，如 {@code "/api/users"} 或 {@code "api/users"}
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder path(String path) {
        this.path = path;
        return this;
    }

    /**
     * 添加单个请求头。
     *
     * <p>如果同名请求头已存在，新值会覆盖旧值（基于 {@link LinkedHashMap#put} 的语义）。</p>
     *
     * @param name  请求头名称，如 {@code "Content-Type"}、{@code "Authorization"}
     * @param value 请求头值，如 {@code "application/json"}、{@code "Bearer xxx"}
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder header(String name, String value) {
        this.headers.add(name, value);
        return this;
    }

    /**
     * 批量添加多个请求头。
     *
     * <p>遍历传入的 Map，逐条调用 {@link #header(String, String)} 添加。
     * 如果 Map 中包含 null 的键或值，对应条目会被跳过。</p>
     *
     * @param headers 请求头 Map，键为请求头名称，值为请求头值
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder headers(Map<String, String> headers) {
        headers.forEach(this.headers::add);
        return this;
    }

    /**
     * 快捷设置 Content-Type 请求头为 <code>application/json</code>。
     *
     * <p><b>适用场景：</b>发送 JSON 格式的 RESTful API 请求，通常与 {@link #body(Object)} 配合使用，
     * body 传入 JSON 字符串或可序列化为 JSON 的对象。</p>
     *
     * <p><b>等效写法：</b></p>
     * <pre>{@code
     * .header("Content-Type", "application/json")
     * }</pre>
     *
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder json() {
        this.headers.add("Content-Type", "application/json");
        return this;
    }

    /**
     * 快捷设置 Content-Type 请求头为 <code>application/x-www-form-urlencoded</code>。
     *
     * <p><b>适用场景：</b>发送传统的 HTML 表单提交请求，通常与 {@link #body(String, String)} 配合使用，
     * 多个键值对会自动拼接为 {@code key1=value1&key2=value2} 格式。</p>
     *
     * <p><b>等效写法：</b></p>
     * <pre>{@code
     * .header("Content-Type", "application/x-www-form-urlencoded")
     * }</pre>
     *
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder form() {
        this.headers.add("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    /**
     * 快捷设置 Accept 请求头，告知服务器期望的响应内容类型。
     *
     * <p><b>适用场景：</b>明确告知服务端客户端期望接收的响应格式，
     * 常用于内容协商（Content Negotiation），让服务端返回指定的 MIME 类型。
     * 常见的取值包括：</p>
     * <ul>
     *   <li>{@code "application/json"} — 期望 JSON 响应</li>
     *   <li>{@code "application/xml"} — 期望 XML 响应</li>
     *   <li>{@code "text/plain"} — 期望纯文本响应</li>
     *   <li>{@code "text/html"} — 期望 HTML 响应</li>
     *   <li>{@code "*&#47;*"} — 接受任意类型（默认行为）</li>
     * </ul>
     *
     * <p><b>等效写法：</b></p>
     * <pre>{@code
     * .header("Accept", "application/json")
     * }</pre>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 请求 JSON 响应
     * HttpClientFactory.of("http://api.example.com/users")
     *     .accept("application/json")
     *     .get();
     *
     * // 链式设置多个期望类型
     * HttpClientFactory.of("http://api.example.com/users")
     *     .accept("application/json, application/xml")
     *     .get();
     * }</pre>
     *
     * @param mimeType 期望的 MIME 类型，如 {@code "application/json"}、{@code "text/plain"}
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder accept(String mimeType) {
        this.headers.add("Accept", mimeType);
        return this;
    }

    /**
     * 快捷设置 Authorization 请求头为 <code>Bearer &lt;token&gt;</code>。
     *
     * <p><b>适用场景：</b>使用 Bearer Token 进行 API 鉴权，
     * 常见于 JWT（JSON Web Token）或 OAuth2 认证场景。
     * 服务端通过验证此 Token 来识别请求方身份。
     *
     * <p><b>等效写法：</b></p>
     * <pre>{@code
     * .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiIs...")
     * }</pre>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpClientFactory.of("http://api.example.com/users")
     *     .auth("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...")
     *     .get();
     * }</pre>
     *
     * @param token Bearer Token 字符串，如 JWT 或 OAuth2 Access Token
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder auth(String token) {
        this.headers.add("Authorization", "Bearer " + token);
        return this;
    }

    /**
     * 快捷设置 Authorization 请求头为 <code>Basic &lt;base64&gt;</code>（HTTP Basic 认证）。
     *
     * <p>将用户名和密码拼接为 {@code username:password} 格式，
     * 然后进行 Base64 编码，最终设置为 {@code Authorization: Basic base64encoded}。
     *
     * <p><b>等效写法：</b></p>
     * <pre>{@code
     * String encoded = Base64.getEncoder().encodeToString("user:pass".getBytes());
     * .header("Authorization", "Basic " + encoded)
     * }</pre>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpClientFactory.of("http://api.example.com/protected")
     *     .authBasic("admin", "123456")
     *     .get();
     * }</pre>
     *
     * @param username 认证用户名
     * @param password 认证密码
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder authBasic(String username, String password) {
        String encoded = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.headers.add("Authorization", "Basic " + encoded);
        return this;
    }

    /**
     * 添加一个纯文本表单字段。
     *
     * <p><b>两种模式：</b></p>
     * <ul>
     *   <li><b>非 multipart 模式（默认）：</b>如果未通过含文件参数的 {@code formData} 进入 multipart 模式，
     *   字段存储在内部 Map 中，执行时序列化为 {@code key1=value1&amp;key2=value2} 格式。</li>
     *   <li><b>multipart 模式：</b>如果已经通过 {@link #formData(String, byte[], String, String)} 添加了文件，
     *   此字段会被添加到 {@link MultipartBody} 中，最终序列化为 RFC 1341 multipart 格式。</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 纯文本表单（非 multipart）
     * HttpClientFactory.of("http://example.com/api")
     *     .formData("username", "admin")
     *     .formData("role", "superuser")
     *     .post();
     * // 实际发送的 body 为: "username=admin&role=superuser"
     * }</pre>
     *
     * @param key   表单字段名，不能为 null
     * @param value 表单字段值，为 null 时序列化为空字符串
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder formData(String key, String value) {
        if (multipartBody != null) {
            // 已处于 multipart 模式，添加到 MultipartBody
            multipartBody.addField(key, value);
        } else {
            // 非 multipart 模式，保持向后兼容的 key=value 序列化
            if (this.formData == null) {
                this.formData = new LinkedHashMap<>();
            }
            this.formData.put(key, value);
        }
        return this;
    }

    /**
     * 添加一个文件上传字段（自动切换为 multipart/form-data 模式）。
     *
     * <p>调用此方法会触发以下行为：</p>
     * <ul>
     *   <li>创建 {@link MultipartBody} 实例（如果尚未创建）</li>
     *   <li>将之前通过 {@link #formData(String, String)} 添加的所有纯文本字段迁移到 MultipartBody 中</li>
     *   <li>将本次的文件字段添加到 MultipartBody</li>
     *   <li>执行时自动设置 Content-Type 头为 {@code multipart/form-data; boundary=...}</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 文件上传 + 附带字段
     * byte[] fileBytes = Files.readAllBytes(Paths.get("photo.png"));
     * HttpClientFactory.of("http://example.com/upload")
     *     .formData("file", fileBytes, "image/png", "photo.png")
     *     .formData("description", "A beautiful photo")
     *     .post();
     * }</pre>
     *
     * @param name        表单字段名
     * @param content     文件内容的字节数组
     * @param contentType 文件的 MIME 类型，如 {@code "image/png"}、{@code "application/pdf"}、
     *                    {@code "text/plain"}；传入 null 则使用 {@code "application/octet-stream"}
     * @param filename    上传的文件名，如 {@code "photo.png"}、{@code "report.pdf"}；
     *                    该名称会出现在服务端的 Content-Disposition 头中
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder formData(String name, byte[] content, String contentType, String filename) {
        ensureMultipartMode();
        multipartBody.addFile(name, content, contentType, filename);
        return this;
    }

    /**
     * 确保当前处于 multipart 模式。
     *
     * <p>如果尚未进入 multipart 模式，则：</p>
     * <ol>
     *   <li>创建新的 {@link MultipartBody} 实例</li>
     *   <li>将 {@link #formData} Map 中已有的纯文本字段全部迁移到 MultipartBody 中</li>
     *   <li>将 {@link #formData} 置为 null（后续纯文本字段通过 {@link #formData(String, String)} 直接进入 MultipartBody）</li>
     * </ol>
     */
    private void ensureMultipartMode() {
        if (multipartBody == null) {
            multipartBody = new MultipartBody();
            // 迁移已有的纯文本字段到 multipart
            if (formData != null && !formData.isEmpty()) {
                for (Map.Entry<String, String> entry : formData.entrySet()) {
                    multipartBody.addField(entry.getKey(), entry.getValue());
                }
                this.formData = null;
            }
        }
    }

    /**
     * 设置请求体为字符串类型。
     *
     * <p><b>与 {@link #body(Object)} 的区别：</b></p>
     * <ul>
     *   <li>此方法明确接收 String 类型，编译期类型安全</li>
     *   <li>{@link #body(Object)} 可以接收任意类型，内部可能进行类型转换</li>
     * </ul>
     *
     * <p>常见的字符串 body 包括：JSON 字符串、XML 字符串、纯文本等。</p>
     *
     * @param body 请求体字符串，如 {@code "{\"name\":\"test\"}"}
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder body(String body) {
        this.body = body;
        return this;
    }

    /**
     * 设置请求体为字节数组类型。
     *
     * <p><b>适用场景：</b>发送二进制数据，如文件内容、序列化后的 Protobuf 消息、加密数据等。
     * 底层 {@code HttpClientExecutor} 会直接将字节数组写入请求输出流。</p>
     *
     * @param body 请求体字节数组
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder body(byte[] body) {
        this.body = body;
        return this;
    }

    /**
     * 添加一个键值对作为请求体参数。
     *
     * <p><b>行为说明：</b>此方法委托给 {@link #formData(String, String)}，效果完全等同。
     * 提供此方法是为了更自然的语义表达：</p>
     * <ul>
     *   <li>与 {@link #form()} 配合使用时，表示"表单字段"</li>
     *   <li>单独使用时，表示"键值对参数"</li>
     * </ul>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 表单提交
     * HttpClientFactory.of("http://example.com/login")
     *     .form()
     *     .body("username", "admin")
     *     .body("password", "123456")
     *     .post();
     * }</pre>
     *
     * @param key   参数键名
     * @param value 参数键值
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder body(String key, String value) {
        return formData(key, value);
    }

    /**
     * 设置请求体（通用类型）。
     *
     * <p>接收任意 Java 对象作为请求体。底层会根据对象实际类型进行处理：</p>
     * <ul>
     *   <li>{@link String} — 直接作为字符串写入</li>
     *   <li>{@code byte[]} — 直接作为二进制数据写入</li>
     *   <li>其他对象 — 调用 {@link Object#toString()} 转为字符串后写入</li>
     * </ul>
     *
     * <p><b>优先级说明：</b>如果同时调用了 {@link #formData(String, String)} 或
     * {@link #body(String, String)}，此方法设置的 body 具有更高优先级，
     * 执行时会直接使用此 body 而不会序列化 formData 或 multipartBody。</p>
     *
     * @param body 请求体对象，支持 String、byte[] 及任意对象
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder body(Object body) {
        this.body = body;
        return this;
    }

    /**
     * 添加单个查询参数。
     *
     * <p>用于链式添加单个 URL 查询参数，多个参数会自动拼接为 {@code ?key1=value1&key2=value2} 格式。
     * 如果之前通过 {@link #params(Map)} 设置过整个参数 Map，此方法会在其基础上追加。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpClientFactory.of("http://api.example.com/users")
     *     .query("page", "1")
     *     .query("size", "20")
     *     .query("sort", "name")
     *     .get();
     * // 实际请求: http://api.example.com/users?page=1&size=20&sort=name
     * }</pre>
     *
     * @param key   参数名，不能为 null
     * @param value 参数值，为 null 时参数名仍会被添加但值为空
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder query(String key, String value) {
        if (this.params == null) {
            this.params = new LinkedHashMap<>();
        }
        this.params.put(key, value);
        return this;
    }

    /**
     * 设置 URL 查询参数。
     *
     * <p>设置的参数会封装到 {@link ClientRequest} 中，由底层执行器
     * {@link com.chua.common.support.network.client.spi.HttpClientExecutor} 拼接到请求 URL 的 query string 上。
     * 底层执行器通常会以 '?' 开头将参数拼接为 {@code ?key1=value1&key2=value2} 格式。
     * 具体拼接行为取决于选用的执行器实现（OkHttp / HttpClient5 / JDK）。</p>
     *
     * <p><b>注意：</b>此方法会<b>替换</b>之前通过 {@link #query(String, String)} 添加的所有参数。</p>
     *
     * @param params 查询参数 Map，键为参数名，值为参数值
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder params(Map<String, String> params) {
        this.params = params;
        return this;
    }

    /**
     * 设置连接超时时间。
     *
     * <p><b>连接超时</b>指从客户端发起 TCP 连接到与服务器建立连接的最大等待时间。
     * 超过此时间仍未建立连接的，会抛出超时异常。
     * 默认值为 30 秒（{@code 30000ms}）。</p>
     *
     * <p><b>常见场景推荐值：</b></p>
     * <ul>
     *   <li>内网调用：3000 ~ 5000ms</li>
     *   <li>外网调用：5000 ~ 15000ms</li>
     *   <li>弱网环境：30000ms 或更长</li>
     * </ul>
     *
     * @param timeout 连接超时时间（毫秒），必须为正数
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder connectTimeout(long timeout) {
        this.connectTimeout = timeout;
        return this;
    }

    /**
     * 设置读取超时时间（Socket 超时）。
     *
     * <p><b>读取超时</b>指从服务器建立连接后，等待服务器返回数据的最大时间间隔。
     * 如果超过此时间仍未收到任何数据，会抛出超时异常。
     * 默认值为 30 秒（{@code 30000ms}）。</p>
     *
     * <p><b>常见场景推荐值：</b></p>
     * <ul>
     *   <li>快速接口：5000 ~ 10000ms</li>
     *   <li>大文件下载/慢查询：60000ms 或更长</li>
     *   <li>流式接口（SSE）：可尝试设置 0（部分执行器将其视为无限等待，具体行为取决于底层 HTTP 执行器实现）</li>
     * </ul>
     *
     * @param timeout 读取超时时间（毫秒），0 表示无限等待（具体行为取决于底层执行器）
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder readTimeout(long timeout) {
        this.readTimeout = timeout;
        return this;
    }

    /**
     * 设置连接保活超时时间。
     *
     * <p>空闲连接在连接池中的最大存活时间，超过此时间未使用的连接将被关闭。
     * 默认值 60 秒（{@code 60000ms}）。
     *
     * @param timeout 保活超时时间（毫秒），0 表示不限制
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder keepAliveTimeout(long timeout) {
        this.keepAliveTimeout = timeout;
        return this;
    }

    /**
     * 设置自定义重定向处理器。
     *
     * <p>当服务器返回 3xx 重定向响应时，此处理器会被调用，开发者可在其中检查
     * {@code Location} 响应头、记录重定向日志或手动处理重定向逻辑。</p>
     *
     * <p><b>注意：</b>设置此处理器会自动将 {@link #followRedirects} 设为 false，
     * 因为自定义重定向与自动跟随是互斥的。执行器会将 3xx 响应原样返回给调用方，
     * 同时在返回前触发此处理器。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpClientFactory.of("http://api.example.com")
     *     .onRedirect(resp -> {
     *         String location = resp.getHeader("Location");
     *         System.out.println("Redirect to: " + location);
     *     })
     *     .get();
     * }</pre>
     *
     * @param handler 重定向处理器，接收 {@link ClientResponse} 参数；null 表示清除
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder onRedirect(java.util.function.Consumer<ClientResponse> handler) {
        this.redirectHandler = handler;
        if (handler != null) {
            this.followRedirects = false;
        } else {
            this.followRedirects = true;
        }
        return this;
    }

    /**
     * 设置 HTTP 代理服务器。
     *
     * <p>当需要通过 HTTP 代理访问目标服务器时使用此方法配置代理地址。
     * 底层执行器（如 JDK HttpClient）会通过代理服务器转发所有请求。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 通过代理访问外部 API
     * HttpClientFactory.of("http://api.example.com/users")
     *     .proxy("proxy.company.com", 8080)
     *     .get();
     *
     * // 认证代理（需手动设置 Proxy-Authorization 头）
     * HttpClientFactory.of("http://api.example.com/users")
     *     .proxy("proxy.company.com", 3128)
     *     .header("Proxy-Authorization", "Basic " + encodedCreds)
     *     .get();
     * }</pre>
     *
     * @param host 代理服务器主机名或 IP 地址，如 {@code "proxy.company.com"}
     * @param port 代理服务器端口号，如 {@code 8080}、{@code 3128}
     * @return 当前构建器实例，支持链式调用
     */
    public HttpClientBuilder proxy(String host, int port) {
        this.proxyHost = host;
        this.proxyPort = port;
        return this;
    }

    /**
     * 执行 HTTP 请求并获取响应。
     *
     * <p>此方法会完成以下步骤：</p>
     * <ol>
     *   <li><b>URL 拼接</b> — 将 baseUrl 与 path 按规则拼接为完整 URL</li>
     *   <li><b>请求体解析</b> — 调用 {@link #resolveBody()} 确定最终的请求体内容</li>
     *   <li><b>创建请求对象</b> — 将当前构建器的所有参数封装为 {@link ClientRequest}</li>
     *   <li><b>委托执行</b> — 通过 {@link HttpClientFactory#getClient()} 获取全局 HTTP 客户端并执行</li>
     * </ol>
     *
     * <p>通常不直接调用此方法，而是通过 {@link #get()}、{@link #post()} 等快捷方法
     * 自动设置请求方法后触发执行。</p>
     *
     * @return 响应对象 {@link ClientResponse}，包含状态码、响应头和响应体
     * @throws RuntimeException 如果请求执行过程中发生异常
     */
    public ClientResponse execute() {
        String url = baseUrl;
        if (path != null && !path.isEmpty()) {
            url = baseUrl.endsWith("/") ? baseUrl + path.substring(1) : baseUrl + path;
        }
        ClientRequest request = new ClientRequest();
        request.setUrl(url);
        request.setMethod(method);
        request.setHeaders(headers);
        request.setBody(resolveBody());
        request.setParams(params);
        request.setFollowRedirects(followRedirects);
        request.setRedirectHandler(redirectHandler);
        request.setConnectTimeout(connectTimeout);
        request.setReadTimeout(readTimeout);
        request.setKeepAliveTimeout(keepAliveTimeout);
        request.setProxy(proxyHost, proxyPort);
        return HttpClientFactory.getClient().execute(request);
    }

    /**
     * 解析最终请求体。
     *
     * <p><b>解析规则（按优先级）：</b></p>
     * <ol>
     *   <li>如果通过 {@link #body(Object)}、{@link #body(String)} 或 {@link #body(byte[])} 显式设置了 body，直接返回该值</li>
     *   <li>如果处于 multipart 模式（有文件上传字段），则将 {@link MultipartBody} 序列化为字节数组，并自动在 Content-Type 头中注入 boundary 分隔符</li>
     *   <li>如果存有纯文本字段（非 multipart 模式），则序列化为 {@code key1=value1&key2=value2} 格式的字符串</li>
     *   <li>如果以上均未设置，返回 null（对于 GET/DELETE 等无 body 的请求是正常的）</li>
     * </ol>
     *
     * @return 解析后的请求体对象，可能为 null、String 或 byte[]
     */
    private Object resolveBody() {
        if (body != null) {
            return body;
        }

        // multipart 模式：序列化为字节数组并自动设置 Content-Type
        if (multipartBody != null && !multipartBody.isEmpty()) {
            headers.add("Content-Type", multipartBody.getContentType());
            return multipartBody.toBytes();
        }

        // 纯文本字段模式（向后兼容），使用 URL 编码处理特殊字符
        if (formData != null && !formData.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : formData.entrySet()) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)).append('=');
                if (entry.getValue() != null) {
                    sb.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }

        return null;
    }

    /**
     * 异步执行 HTTP 请求并返回 {@link CompletableFuture}。
     *
     * <p>与 {@link #execute()} 功能相同，但异步执行，不阻塞当前线程。
     * 返回的 {@link CompletableFuture} 在请求完成后完成，
     * 可通过 thenApply/whenComplete 等链式方法组合异步逻辑。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * HttpClientFactory.of("http://api.example.com/users")
     *     .header("Authorization", "Bearer token")
     *     .getAsync()
     *     .thenApply(ClientResponse::getBodyString)
     *     .thenAccept(System.out::println)
     *     .exceptionally(err -> {
     *         System.err.println("请求失败: " + err.getMessage());
     *         return null;
     *     });
     * }</pre>
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> executeAsync() {
        String url = baseUrl;
        if (path != null && !path.isEmpty()) {
            url = baseUrl.endsWith("/") ? baseUrl + path.substring(1) : baseUrl + path;
        }
        ClientRequest request = new ClientRequest();
        request.setUrl(url);
        request.setMethod(method);
        request.setHeaders(headers);
        request.setBody(resolveBody());
        request.setParams(params);
        request.setFollowRedirects(followRedirects);
        request.setRedirectHandler(redirectHandler);
        request.setConnectTimeout(connectTimeout);
        request.setReadTimeout(readTimeout);
        request.setKeepAliveTimeout(keepAliveTimeout);
        request.setProxy(proxyHost, proxyPort);
        return HttpClientFactory.getClient().executeAsync(request);
    }

    /**
     * 异步执行 HTTP 请求，通过回调通知结果。
     *
     * <p>以回调风格发送异步 HTTP 请求，无需手动管理 {@link CompletableFuture}。
     * 成功时调用 {@link Callback#onSuccess(Object)}，失败时调用
     * {@link Callback#onError(Throwable)}。</p>
     *
     * <p><b>使用示例：</b></p>
     * <pre>{@code
     * // 传统回调风格
     * HttpClientFactory.of("http://api.example.com/users")
     *     .getAsync(new Callback<ClientResponse>() {
     *         public void onSuccess(ClientResponse resp) {
     *             System.out.println("响应: " + resp.getBodyString());
     *         }
     *         public void onError(Throwable err) {
     *             System.err.println("错误: " + err.getMessage());
     *         }
     *     });
     *
     * // Lambda 回调风格（仅关注成功）
     * HttpClientFactory.of("http://api.example.com/users")
     *     .getAsync(resp -> System.out.println(resp.getBodyString()));
     *
     * // Lambda 回调风格（同时关注成功和失败）
     * HttpClientFactory.of("http://api.example.com/users")
     *     .getAsync(
     *         resp -> System.out.println(resp.getBodyString()),
     *         err  -> System.err.println(err.getMessage())
     *     );
     * }</pre>
     *
     * @param callback 异步回调，成功时回调 {@link Callback#onSuccess(Object)}，
     *                 失败时回调 {@link Callback#onError(Throwable)}
     */
    public void executeAsync(Callback<ClientResponse> callback) {
        executeAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    // ==================== 同步快捷方法 ====================

    /**
     * 执行 GET 请求。
     * <p>将请求方法设为 GET 后立即执行请求。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse get() {
        method = HttpMethod.GET;
        return execute();
    }

    /**
     * 执行 POST 请求。
     * <p>将请求方法设为 POST 后立即执行请求。用于创建资源，通常配合 body 使用。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse post() {
        method = HttpMethod.POST;
        return execute();
    }

    /**
     * 执行 PUT 请求。
     * <p>将请求方法设为 PUT 后立即执行请求。用于全量更新资源，通常配合 body 使用。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse put() {
        method = HttpMethod.PUT;
        return execute();
    }

    /**
     * 执行 DELETE 请求。
     * <p>将请求方法设为 DELETE 后立即执行请求。用于删除资源。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse delete() {
        method = HttpMethod.DELETE;
        return execute();
    }

    /**
     * 执行 PATCH 请求。
     * <p>将请求方法设为 PATCH 后立即执行请求。用于部分更新资源，通常配合 body 使用。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse patch() {
        method = HttpMethod.PATCH;
        return execute();
    }

    /**
     * 执行 HEAD 请求。
     * <p>将请求方法设为 HEAD 后立即执行请求。仅获取响应头，不返回响应体，常用于检查资源是否存在。</p>
     *
     * @return 响应对象 {@link ClientResponse}（body 为空）
     */
    public ClientResponse head() {
        method = HttpMethod.HEAD;
        return execute();
    }

    /**
     * 执行 OPTIONS 请求。
     * <p>将请求方法设为 OPTIONS 后立即执行请求。用于获取目标资源支持的 HTTP 方法列表。</p>
     *
     * @return 响应对象 {@link ClientResponse}
     */
    public ClientResponse options() {
        method = HttpMethod.OPTIONS;
        return execute();
    }

    // ==================== 异步快捷方法 ====================

    /**
     * 异步执行 GET 请求，返回 {@link CompletableFuture}。
     *
     * <p>将请求方法设为 GET 后异步执行。<b>不阻塞当前线程</b>。</p>
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> getAsync() {
        method = HttpMethod.GET;
        return executeAsync();
    }

    /**
     * 异步执行 GET 请求，通过回调通知结果。
     *
     * <p>将请求方法设为 GET 后异步执行。成功或失败时通过回调通知。</p>
     *
     * @param callback 异步回调
     */
    public void getAsync(Callback<ClientResponse> callback) {
        getAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 POST 请求，返回 {@link CompletableFuture}。
     *
     * <p>将请求方法设为 POST 后异步执行。<b>不阻塞当前线程</b>。</p>
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> postAsync() {
        method = HttpMethod.POST;
        return executeAsync();
    }

    /**
     * 异步执行 POST 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void postAsync(Callback<ClientResponse> callback) {
        postAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 PUT 请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> putAsync() {
        method = HttpMethod.PUT;
        return executeAsync();
    }

    /**
     * 异步执行 PUT 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void putAsync(Callback<ClientResponse> callback) {
        putAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 DELETE 请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> deleteAsync() {
        method = HttpMethod.DELETE;
        return executeAsync();
    }

    /**
     * 异步执行 DELETE 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void deleteAsync(Callback<ClientResponse> callback) {
        deleteAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 PATCH 请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> patchAsync() {
        method = HttpMethod.PATCH;
        return executeAsync();
    }

    /**
     * 异步执行 PATCH 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void patchAsync(Callback<ClientResponse> callback) {
        patchAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 HEAD 请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> headAsync() {
        method = HttpMethod.HEAD;
        return executeAsync();
    }

    /**
     * 异步执行 HEAD 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void headAsync(Callback<ClientResponse> callback) {
        headAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }

    /**
     * 异步执行 OPTIONS 请求，返回 {@link CompletableFuture}。
     *
     * @return 异步任务，完成时包含 {@link ClientResponse} 响应对象
     */
    public CompletableFuture<ClientResponse> optionsAsync() {
        method = HttpMethod.OPTIONS;
        return executeAsync();
    }

    /**
     * 异步执行 OPTIONS 请求，通过回调通知结果。
     *
     * @param callback 异步回调
     */
    public void optionsAsync(Callback<ClientResponse> callback) {
        optionsAsync().whenComplete((resp, err) -> {
            if (err != null) {
                callback.onError(err);
            } else {
                callback.onSuccess(resp);
            }
        });
    }
}
