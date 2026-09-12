package com.chua.common.support.datasearch.server.filter;

import com.chua.common.support.datasearch.exchange.spi.ExchangeRateProvider;
import com.chua.common.support.datasearch.express.model.ExpressTrace;
import com.chua.common.support.datasearch.express.spi.ExpressProvider;
import com.chua.common.support.datasearch.geocode.spi.GeocodeProvider;
import com.chua.common.support.datasearch.hanzi.model.HanziInfo;
import com.chua.common.support.datasearch.hanzi.spi.HanziProvider;
import com.chua.common.support.datasearch.holiday.model.HolidayInfo;
import com.chua.common.support.datasearch.holiday.spi.HolidayProvider;
import com.chua.common.support.datasearch.horoscope.model.HoroscopeInfo;
import com.chua.common.support.datasearch.horoscope.spi.HoroscopeProvider;
import com.chua.common.support.datasearch.idiom.model.IdiomInfo;
import com.chua.common.support.datasearch.idiom.spi.IdiomProvider;
import com.chua.common.support.datasearch.location.model.LocationInfo;
import com.chua.common.support.datasearch.location.spi.LocationProvider;
import com.chua.common.support.datasearch.phone.model.PhoneLocationInfo;
import com.chua.common.support.datasearch.phone.spi.PhoneLocationProvider;
import com.chua.common.support.datasearch.poetry.model.PoetryInfo;
import com.chua.common.support.datasearch.poetry.spi.PoetryProvider;
import com.chua.common.support.datasearch.region.model.RegionInfo;
import com.chua.common.support.datasearch.region.spi.RegionProvider;
import com.chua.common.support.datasearch.typhoon.model.TyphoonActivity;
import com.chua.common.support.datasearch.typhoon.model.TyphoonDetail;
import com.chua.common.support.datasearch.typhoon.spi.TyphoonProvider;
import com.chua.common.support.datasearch.weather.model.WeatherInfo;
import com.chua.common.support.datasearch.weather.spi.WeatherProvider;
import com.chua.common.support.lang.json.Json;
import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.network.server.filter.ServerFilterChain;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* 数据搜索服务过滤器。
*
* <p>将 {@code datasearch-starter} 内全部数据提供者（成语 / 古诗词 / 汉字字典 /
* 手机归属地 / 星座运势 / 天气 / 节假日 / 行政区划 / 汇率 / 快递 / 台风 / IP 定位 /
* 逆地理编码）暴露为统一 REST 接口，供任何 {@link com.chua.common.support.network.server.Server}
* 挂载使用。</p>
*
* <p>接口统一前缀为 {@code /datasearch}，路由规则如下：</p>
* <ul>
*   <li>{@code /datasearch/idiom/get?word=守株待兔} — 成语精确查询</li>
*   <li>{@code /datasearch/idiom/search?keyword=兔&limit=10} — 成语模糊搜索</li>
*   <li>{@code /datasearch/idiom/random} — 随机成语</li>
*   <li>{@code /datasearch/poetry/random} — 随机诗词</li>
*   <li>{@code /datasearch/poetry/author?author=李白&limit=10} — 按作者检索诗词</li>
*   <li>{@code /datasearch/poetry/search?keyword=明月&limit=10} — 诗词搜索</li>
*   <li>{@code /datasearch/hanzi/get?character=中} — 汉字查询</li>
*   <li>{@code /datasearch/hanzi/search?keyword=zhong&limit=10} — 汉字搜索</li>
*   <li>{@code /datasearch/hanzi/random} — 随机汉字</li>
*   <li>{@code /datasearch/phone?phone=13800138000} — 手机归属地</li>
*   <li>{@code /datasearch/horoscope?sign=白羊座&type=today} — 星座运势</li>
*   <li>{@code /datasearch/weather?city=北京} — 实时天气</li>
*   <li>{@code /datasearch/holiday/check?date=2026-01-01} — 节假日判定</li>
*   <li>{@code /datasearch/holiday/list?year=2026} — 年度节假日安排</li>
*   <li>{@code /datasearch/region?level=2} — 行政区划扁平列表</li>
*   <li>{@code /datasearch/region/children?parent=110000} — 下级区划</li>
*   <li>{@code /datasearch/region/tree?level=2} — 行政区划树</li>
*   <li>{@code /datasearch/exchange/rate?from=USD&to=CNY} — 汇率换算</li>
*   <li>{@code /datasearch/exchange/rates?base=USD} — 全量汇率表</li>
*   <li>{@code /datasearch/express?trackingNo=xxx} — 快递物流轨迹</li>
*   <li>{@code /datasearch/typhoon/active} — 活跃台风列表</li>
*   <li>{@code /datasearch/typhoon/detail?tfid=202618} — 台风详情</li>
*   <li>{@code /datasearch/location/self} — 定位自身</li>
*   <li>{@code /datasearch/location/ip?ip=8.8.8.8} — IP 定位</li>
*   <li>{@code /datasearch/geocode/reverse?lat=39.9&lon=116.4} — 逆地理编码</li>
*   <li>{@code /datasearch/geocode/ip?ip=8.8.8.8} — IP 转地址</li>
* </ul>
*
* <p>响应统一为 JSON：{@code {"code":200,"msg":"ok","data":{...}}}。
* 数据提供者均通过 SPI 惰性加载，未注册对应实现时返回 {@code code=404}。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class DataSearchServerFilter implements ServerFilter {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(DataSearchServerFilter.class);

    /** 接口前缀 */
    private static final String PREFIX = "/datasearch";

    /** 过滤器标识 */
    private static final String FILTER_ID = "DataSearchServerFilter";

    @Override
    /** 获取订单 */
    public int getOrder() {
        return Integer.MAX_VALUE - 50;
    }

    @Override
    /** 支持路径 */
    public String supportPath() {
        return PREFIX + "/**";
    }

    @Override
    /** 支持协议 */
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    /** 执行过滤 */
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        String path = request.getPath();
        if (path == null || !path.startsWith(PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            dispatch(path.substring(PREFIX.length()), request, response);
        } catch (Exception e) {
            log.warn("[datasearch] 处理失败: path={}, msg={}", path, e.getMessage());
            write(response, 500, "internal error: " + e.getMessage(), null);
        }
    }

    /**
    * 分发路由
    *
    * @param subPath sub路径
    * @param request 请求
    * @param response 响应
     */
    private void dispatch(String subPath, ServerRequest request, ServerResponse response) throws Exception {
        if (subPath.isEmpty() || "/".equals(subPath)) {
            write(response, 200, "ok", index());
            return;
        }
        if ("/page".equals(subPath) || "/index.html".equals(subPath) || "/ui".equals(subPath)) {
            servePage(response);
            return;
        }
        if (subPath.startsWith("/idiom/")) {
            handleIdiom(subPath.substring("/idiom/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/poetry/")) {
            handlePoetry(subPath.substring("/poetry/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/hanzi/")) {
            handleHanzi(subPath.substring("/hanzi/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/holiday/")) {
            handleHoliday(subPath.substring("/holiday/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/region")) {
            handleRegion(subPath, request, response);
            return;
        }
        if (subPath.startsWith("/exchange/")) {
            handleExchange(subPath.substring("/exchange/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/typhoon")) {
            handleTyphoon(subPath, request, response);
            return;
        }
        if (subPath.startsWith("/location/")) {
            handleLocation(subPath.substring("/location/".length()), request, response);
            return;
        }
        if (subPath.startsWith("/geocode/")) {
            handleGeocode(subPath.substring("/geocode/".length()), request, response);
            return;
        }
        switch (subPath) {
            case "/phone":
                handlePhone(request, response);
                return;
            case "/horoscope":
                handleHoroscope(request, response);
                return;
            case "/weather":
                handleWeather(request, response);
                return;
            case "/express":
                handleExpress(request, response);
                return;
            default:
                write(response, 404, "not found: " + subPath, null);
        }
    }

    /**
    * 成语路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleIdiom(String action, ServerRequest request, ServerResponse response) throws Exception {
        IdiomProvider provider = loadFirst(IdiomProvider.class);
        if (provider == null) {
            write(response, 404, "idiom provider not found", null);
            return;
        }
        switch (action) {
            case "get":
                write(response, 200, "ok", toJsonable(provider.get(request.getParam("word"))));
                return;
            case "search":
                write(response, 200, "ok", toJsonableList(provider.search(request.getParam("keyword"), limit(request))));
                return;
            case "random":
                write(response, 200, "ok", toJsonable(provider.random()));
                return;
            case "chain":
                write(response, 200, "ok", toJsonableList(provider.chain(request.getParam("word"), limit(request))));
                return;
            case "first-char":
                write(response, 200, "ok", toJsonableList(provider.findByFirstChar(request.getParam("char"), limit(request))));
                return;
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 诗词路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handlePoetry(String action, ServerRequest request, ServerResponse response) throws Exception {
        PoetryProvider provider = loadFirst(PoetryProvider.class);
        if (provider == null) {
            write(response, 404, "poetry provider not found", null);
            return;
        }
        switch (action) {
            case "random":
                write(response, 200, "ok", toJsonable(provider.random()));
                return;
            case "author":
                write(response, 200, "ok", toJsonableList(provider.byAuthor(request.getParam("author"), limit(request))));
                return;
            case "search":
                write(response, 200, "ok", toJsonableList(provider.search(request.getParam("keyword"), limit(request))));
                return;
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 汉字路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleHanzi(String action, ServerRequest request, ServerResponse response) throws Exception {
        HanziProvider provider = loadFirst(HanziProvider.class);
        if (provider == null) {
            write(response, 404, "hanzi provider not found", null);
            return;
        }
        switch (action) {
            case "get":
                write(response, 200, "ok", toJsonable(provider.get(request.getParam("character"))));
                return;
            case "search":
                write(response, 200, "ok", toJsonableList(provider.search(request.getParam("keyword"), limit(request))));
                return;
            case "random":
                write(response, 200, "ok", toJsonable(provider.random()));
                return;
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 节假日路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleHoliday(String action, ServerRequest request, ServerResponse response) throws Exception {
        HolidayProvider provider = loadFirst(HolidayProvider.class);
        if (provider == null) {
            write(response, 404, "holiday provider not found", null);
            return;
        }
        switch (action) {
            case "check": {
                String date = request.getParam("date");
                if (date == null || date.isBlank()) {
                    write(response, 400, "missing param: date", null);
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>(4);
                LocalDate d = LocalDate.parse(date);
                data.put("date", date);
                data.put("holiday", provider.isHoliday(d));
                data.put("workday", provider.isWorkday(d));
                HolidayInfo info = provider.getHoliday(d);
                data.put("detail", toJsonable(info));
                write(response, 200, "ok", data);
                return;
            }
            case "list": {
                String year = request.getParam("year");
                if (year == null || year.isBlank()) {
                    write(response, 400, "missing param: year", null);
                    return;
                }
                write(response, 200, "ok", toJsonableList(provider.getHolidays(Integer.parseInt(year))));
                return;
            }
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 行政区划路由
    *
    * @param subPath sub路径
    * @param request 请求
    * @param response 响应
     */
    private void handleRegion(String subPath, ServerRequest request, ServerResponse response) throws Exception {
        RegionProvider provider = loadFirst(RegionProvider.class);
        if (provider == null) {
            write(response, 404, "region provider not found", null);
            return;
        }
        if (subPath.startsWith("/region/children")) {
            write(response, 200, "ok", toJsonableList(provider.getChildren(request.getParam("parent"))));
            return;
        }
        if (subPath.startsWith("/region/tree")) {
            write(response, 200, "ok", toJsonable(provider.getTree(intParam(request, "level", 2))));
            return;
        }
        write(response, 200, "ok", toJsonableList(provider.getRegions(intParam(request, "level", 2))));
    }

    /**
    * 汇率路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleExchange(String action, ServerRequest request, ServerResponse response) throws Exception {
        ExchangeRateProvider provider = loadFirst(ExchangeRateProvider.class);
        if (provider == null) {
            write(response, 404, "exchange provider not found", null);
            return;
        }
        switch (action) {
            case "rate": {
                BigDecimal rate = provider.getRate(request.getParam("from"), request.getParam("to"));
                Map<String, Object> data = new LinkedHashMap<>(4);
                data.put("from", request.getParam("from"));
                data.put("to", request.getParam("to"));
                data.put("rate", rate);
                write(response, rate == null ? 404 : 200, rate == null ? "currency not found" : "ok", data);
                return;
            }
            case "rates": {
                Map<String, Object> data = new LinkedHashMap<>(2);
                data.put("base", request.getParam("base"));
                data.put("rates", provider.getRates(request.getParam("base")));
                write(response, 200, "ok", data);
                return;
            }
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 台风路由
    *
    * @param subPath sub路径
    * @param request 请求
    * @param response 响应
     */
    private void handleTyphoon(String subPath, ServerRequest request, ServerResponse response) throws Exception {
        TyphoonProvider provider = loadFirst(TyphoonProvider.class);
        if (provider == null) {
            write(response, 404, "typhoon provider not found", null);
            return;
        }
        if (subPath.startsWith("/typhoon/detail")) {
            write(response, 200, "ok", toJsonable(provider.getTyphoon(request.getParam("tfid"))));
            return;
        }
        write(response, 200, "ok", toJsonableList(provider.getActiveTyphoons()));
    }

    /**
    * 定位路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleLocation(String action, ServerRequest request, ServerResponse response) throws Exception {
        LocationProvider provider = loadFirst(LocationProvider.class);
        if (provider == null) {
            write(response, 404, "location provider not found", null);
            return;
        }
        switch (action) {
            case "self":
                write(response, 200, "ok", toJsonable(provider.locateSelf()));
                return;
            case "ip":
                write(response, 200, "ok", toJsonable(provider.locateIp(request.getParam("ip"))));
                return;
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 逆地理编码路由
    *
    * @param action 动作
    * @param request 请求
    * @param response 响应
     */
    private void handleGeocode(String action, ServerRequest request, ServerResponse response) throws Exception {
        GeocodeProvider provider = loadFirst(GeocodeProvider.class);
        if (provider == null) {
            write(response, 404, "geocode provider not found", null);
            return;
        }
        switch (action) {
            case "reverse": {
                String lat = request.getParam("lat");
                String lon = request.getParam("lon");
                if (lat == null || lon == null) {
                    write(response, 400, "missing param: lat/lon", null);
                    return;
                }
                Map<String, Object> data = new LinkedHashMap<>(3);
                data.put("address", provider.reverseGeocode(Double.parseDouble(lat), Double.parseDouble(lon)));
                write(response, 200, "ok", data);
                return;
            }
            case "ip": {
                Map<String, Object> data = new LinkedHashMap<>(2);
                data.put("address", provider.ipToAddress(request.getParam("ip")));
                write(response, 200, "ok", data);
                return;
            }
            default:
                write(response, 404, "not found: " + action, null);
        }
    }

    /**
    * 手机归属地路由
    *
    * @param request 请求
    * @param response 响应
     */
    private void handlePhone(ServerRequest request, ServerResponse response) throws Exception {
        PhoneLocationProvider provider = loadFirst(PhoneLocationProvider.class);
        if (provider == null) {
            write(response, 404, "phone provider not found", null);
            return;
        }
        write(response, 200, "ok", toJsonable(provider.getLocation(request.getParam("phone"))));
    }

    /**
    * 星座运势路由
    *
    * @param request 请求
    * @param response 响应
     */
    private void handleHoroscope(ServerRequest request, ServerResponse response) throws Exception {
        HoroscopeProvider provider = loadFirst(HoroscopeProvider.class);
        if (provider == null) {
            write(response, 404, "horoscope provider not found", null);
            return;
        }
        write(response, 200, "ok", toJsonable(provider.get(request.getParam("sign"), request.getParam("type"))));
    }

    /**
    * 天气路由
    *
    * @param request 请求
    * @param response 响应
     */
    private void handleWeather(ServerRequest request, ServerResponse response) throws Exception {
        WeatherProvider provider = loadFirst(WeatherProvider.class);
        if (provider == null) {
            write(response, 404, "weather provider not found", null);
            return;
        }
        write(response, 200, "ok", toJsonable(provider.getWeather(request.getParam("city"))));
    }

    /**
    * 快递路由
    *
    * @param request 请求
    * @param response 响应
     */
    private void handleExpress(ServerRequest request, ServerResponse response) throws Exception {
        ExpressProvider provider = loadFirst(ExpressProvider.class);
        if (provider == null) {
            write(response, 404, "express provider not found", null);
            return;
        }
        String trackingNo = request.getParam("trackingNo");
        String companyCode = request.getParam("companyCode");
        List<ExpressTrace> traces = (companyCode == null || companyCode.isBlank())
                ? provider.query(trackingNo)
                : provider.query(companyCode, trackingNo);
        write(response, 200, "ok", toJsonableList(traces));
    }

    /**
    * 通过 SPI 加载首个可用实现
    *
    * @param type 类型
    * @return 加载第一个的结果
     */
    private static <T> T loadFirst(Class<T> type) {
        Map<String, T> list = ServiceProvider.of(type).list();
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.values().iterator().next();
    }

    /**
    * 读取 限制 参数
    *
    * @param request 请求
    * @return 限制的结果
     */
    private static int limit(ServerRequest request) {
        String value = request.getParam("limit");
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
    * 读取 int 参数
    *
    * @param request 请求
    * @param name 名称
    * @param defaultValue 默认值
    * @return int参数的结果
     */
    private static int intParam(ServerRequest request, String name, int defaultValue) {
        String value = request.getParam(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
    * 写入 JSON 响应
    *
    * @param response 响应
    * @param code 编码
    * @param msg msg
    * @param data 数据
     */
    private static void write(ServerResponse response, int code, String msg, Object data) {
        Map<String, Object> body = new LinkedHashMap<>(3);
        body.put("code", code);
        body.put("msg", msg);
        body.put("data", data);
        response.setStatus(code);
        response.setContentType("application/json; charset=utf-8");
        response.setBody(Json.toJson(body));
        response.end();
    }

    /**
    * 单对象转可序列化结构（优先 转为映射）
    *
    * @param obj obj
    * @return 转为jsonable的结果
     */
    private static Object toJsonable(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof RegionInfo regionInfo) {
            return regionInfo.toMap();
        }
        if (obj instanceof IdiomInfo idiomInfo) {
            return idiomInfo.toMap();
        }
        if (obj instanceof PoetryInfo poetryInfo) {
            return poetryInfo.toMap();
        }
        if (obj instanceof HanziInfo hanziInfo) {
            return hanziInfo.toMap();
        }
        if (obj instanceof PhoneLocationInfo phoneLocationInfo) {
            return phoneLocationInfo.toMap();
        }
        if (obj instanceof HoroscopeInfo horoscopeInfo) {
            return horoscopeInfo.toMap();
        }
        if (obj instanceof HolidayInfo holidayInfo) {
            return holidayInfo.toMap();
        }
        if (obj instanceof ExpressTrace expressTrace) {
            return expressTrace.toMap();
        }
        return obj;
    }

    /**
    * 列表转可序列化结构
    *
    * @param list 列表
    * @return 转为jsonable列表的结果
     */
    private static List<Object> toJsonableList(List<?> list) {
        List<Object> result = new ArrayList<>();
        if (list != null) {
            for (Object item : list) {
                result.add(toJsonable(item));
            }
        }
        return result;
    }

    /**
    * 接口索引
    *
    * @return 索引的结果
     */
    private static Map<String, Object> index() {
        Map<String, Object> map = new LinkedHashMap<>(32);
        map.put("page", "/datasearch/page");
        map.put("idiom/get", "?word=守株待兔");
        map.put("idiom/search", "?keyword=兔&limit=10");
        map.put("idiom/chain", "?word=守株待兔&limit=10");
        map.put("idiom/first-char", "?char=兔&limit=10");
        map.put("idiom/random", "");
        map.put("poetry/random", "");
        map.put("poetry/author", "?author=李白&limit=10");
        map.put("poetry/search", "?keyword=明月&limit=10");
        map.put("hanzi/get", "?character=中");
        map.put("hanzi/search", "?keyword=zhong&limit=10");
        map.put("hanzi/random", "");
        map.put("phone", "?phone=13800138000");
        map.put("horoscope", "?sign=白羊座&type=today");
        map.put("weather", "?city=北京");
        map.put("holiday/check", "?date=2026-01-01");
        map.put("holiday/list", "?year=2026");
        map.put("region", "?level=2");
        map.put("region/children", "?parent=110000");
        map.put("region/tree", "?level=2");
        map.put("exchange/rate", "?from=USD&to=CNY");
        map.put("exchange/rates", "?base=USD");
        map.put("express", "?trackingNo=SF123456789");
        map.put("typhoon/active", "");
        map.put("typhoon/detail", "?tfid=202618");
        map.put("location/self", "");
        map.put("location/ip", "?ip=8.8.8.8");
        map.put("geocode/reverse", "?lat=39.9&lon=116.4");
        map.put("geocode/ip", "?ip=8.8.8.8");
        return map;
    }

    /**
    * 响应内置演示页面
    *
    * @param response 响应
     */
    private void servePage(ServerResponse response) {
        response.setStatus(200);
        response.setContentType("text/html; charset=utf-8");
        response.setBody(PAGE_HTML);
        response.end();
    }

    /** 内置演示页面 */
    private static final String PAGE_HTML = ""
            + "<!DOCTYPE html>\n"
            + "<html lang=\"zh-CN\">\n"
            + "<head>\n"
            + "<meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
            + "<title>数据搜索服务</title>\n"
            + "<style>\n"
            + "  :root { --bg:#f4f6fb; --panel:#fff; --border:#e5e7eb; --border2:#d1d5db; --text:#1f2937; --muted:#6b7280; --accent:#6366f1; --accent-bg:#eef2ff; --accent-2:#4f46e5; --code-bg:#111827; --code:#d1fae5; --ok:#059669; --err:#dc2626; --warn:#d97706; --radius:10px; }\n"
            + "  * { box-sizing: border-box; margin: 0; padding: 0; }\n"
            + "  body { font-family: 'Cascadia Code', 'Segoe UI', 'Microsoft YaHei', sans-serif; background: var(--bg); color: var(--text); }\n"
            + "  .app { display: flex; height: 100vh; }\n"
            + "  /* ===== 左侧菜单 ===== */\n"
            + "  .sidebar { width: 240px; flex-shrink: 0; background: var(--panel); border-right: 1px solid var(--border); display: flex; flex-direction: column; }\n"
            + "  .brand { padding: 16px; border-bottom: 1px solid var(--border); }\n"
            + "  .brand h1 { font-size: 16px; display: flex; align-items: center; gap: 8px; }\n"
            + "  .brand .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent); display: inline-block; }\n"
            + "  .brand .sub { font-size: 12px; color: var(--muted); margin-top: 4px; font-weight: 400; }\n"
            + "  .menu { flex: 1; overflow-y: auto; padding: 8px 0; }\n"
            + "  .group > .group-name { display: flex; align-items: center; justify-content: space-between; padding: 8px 16px; font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .4px; cursor: pointer; user-select: none; }\n"
            + "  .group > .group-name:hover { color: var(--text); }\n"
            + "  .group > .group-name .chev { transition: transform .15s ease; font-size: 10px; }\n"
            + "  .group.collapsed > .group-name .chev { transform: rotate(-90deg); }\n"
            + "  .group-items { overflow: hidden; }\n"
            + "  .group.collapsed > .group-items { display: none; }\n"
            + "  .item { display: flex; align-items: center; gap: 8px; width: 100%; text-align: left; padding: 7px 16px 7px 24px; border: none; background: transparent; font-size: 13px; color: var(--text); cursor: pointer; transition: background .15s ease, color .15s ease; font-family: inherit; }\n"
            + "  .item:hover { background: #f3f4f6; }\n"
            + "  .item.active { background: var(--accent-bg); color: var(--accent-2); font-weight: 600; }\n"
            + "  .item .method { font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 4px; background: #dbeafe; color: #1d4ed8; }\n"
            + "  .foot { padding: 12px 16px; border-top: 1px solid var(--border); font-size: 12px; color: var(--muted); }\n"
            + "  .foot a { color: var(--accent-2); text-decoration: none; }\n"
            + "  .foot a:hover { text-decoration: underline; }\n"
            + "  /* ===== 主区域 ===== */\n"
            + "  .main { flex: 1; min-width: 0; display: flex; flex-direction: column; }\n"
            + "  .request { background: var(--panel); border-bottom: 1px solid var(--border); padding: 14px 18px; }\n"
            + "  .req-row { display: flex; gap: 8px; }\n"
            + "  .method { flex: 0 0 auto; }\n"
            + "  .url-bar { display: flex; flex: 1; border: 1px solid var(--border2); border-radius: 8px; overflow: hidden; }\n"
            + "  .url-bar:focus-within { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(99,102,241,.15); }\n"
            + "  .url-prefix { display: flex; align-items: center; padding: 0 10px; background: #f3f4f6; color: var(--muted); font-size: 12px; border-right: 1px solid var(--border); }\n"
            + "  .url-bar input { flex: 1; border: none; outline: none; padding: 9px 12px; font-size: 13px; font-family: inherit; color: var(--text); background: transparent; }\n"
            + "  .send { padding: 9px 20px; border: none; border-radius: 8px; background: var(--accent); color: #fff; font-size: 13px; font-weight: 600; cursor: pointer; transition: background .15s ease; font-family: inherit; }\n"
            + "  .send:hover { background: var(--accent-2); }\n"
            + "  .send:disabled { opacity: .6; cursor: not-allowed; }\n"
            + "  .req-meta { display: flex; align-items: center; gap: 8px; margin-top: 8px; font-size: 12px; color: var(--muted); }\n"
            + "  .req-meta .tag { padding: 2px 8px; border-radius: 999px; background: #f3f4f6; }\n"
            + "  /* 参数编辑 */\n"
            + "  .params { background: var(--panel); border-bottom: 1px solid var(--border); padding: 12px 18px; }\n"
            + "  .params .p-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }\n"
            + "  .params .p-title { font-size: 13px; font-weight: 600; }\n"
            + "  .p-table { width: 100%; border-collapse: collapse; }\n"
            + "  .p-table th { text-align: left; font-size: 12px; color: var(--muted); font-weight: 500; padding: 4px 8px; border-bottom: 1px solid var(--border); }\n"
            + "  .p-table td { padding: 4px 8px; }\n"
            + "  .p-table input { width: 100%; padding: 6px 10px; border: 1px solid var(--border2); border-radius: 6px; font-size: 13px; outline: none; font-family: inherit; }\n"
            + "  .p-table input:focus { border-color: var(--accent); }\n"
            + "  .p-del { border: none; background: transparent; color: var(--muted); cursor: pointer; font-size: 13px; }\n"
            + "  .p-del:hover { color: var(--err); }\n"
            + "  .add-param { border: 1px dashed var(--border2); background: transparent; color: var(--muted); padding: 5px 12px; border-radius: 6px; font-size: 12px; cursor: pointer; margin-top: 6px; }\n"
            + "  .add-param:hover { border-color: var(--accent); color: var(--accent-2); }\n"
            + "  /* 响应区 */\n"
            + "  .response { flex: 1; min-height: 0; display: flex; flex-direction: column; padding: 0 18px 18px; }\n"
            + "  .resp-head { display: flex; align-items: center; gap: 12px; padding: 10px 0; }\n"
            + "  .resp-status { font-size: 13px; font-weight: 600; }\n"
            + "  .resp-status.ok { color: var(--ok); }\n"
            + "  .resp-status.err { color: var(--err); }\n"
            + "  .resp-time { font-size: 12px; color: var(--muted); }\n"
            + "  .tabs { display: flex; gap: 2px; margin-left: auto; }\n"
            + "  .tab { padding: 6px 14px; border: 1px solid var(--border); background: transparent; color: var(--muted); font-size: 12px; cursor: pointer; border-radius: 6px; transition: all .15s ease; font-family: inherit; }\n"
            + "  .tab:first-child { border-top-right-radius: 0; border-bottom-right-radius: 0; }\n"
            + "  .tab + .tab { border-left: none; border-radius: 0; }\n"
            + "  .tab:last-child { border-top-left-radius: 0; border-bottom-left-radius: 0; border-top-right-radius: 6px; border-bottom-right-radius: 6px; }\n"
            + "  .tab.active { background: var(--accent-bg); color: var(--accent-2); border-color: var(--accent); font-weight: 600; }\n"
            + "  .resp-body { flex: 1; min-height: 0; background: var(--code-bg); border-radius: var(--radius); overflow: auto; }\n"
            + "  pre.json { color: var(--code); font-size: 12.5px; line-height: 1.6; padding: 16px; white-space: pre-wrap; word-break: break-all; font-family: 'Cascadia Code', Consolas, monospace; }\n"
            + "  .placeholder { display: flex; align-items: center; justify-content: center; height: 100%; color: #6b7280; font-size: 13px; }\n"
            + "  /* 渲染视图 */\n"
            + "  .render { padding: 16px; color: #e5e7eb; }\n"
            + "  .r-card { background: #1f2937; border: 1px solid #374151; border-radius: 8px; padding: 12px 14px; margin-bottom: 10px; }\n"
            + "  .r-card .r-title { font-size: 13px; font-weight: 700; color: #93c5fd; margin-bottom: 8px; }\n"
            + "  .r-kv { width: 100%; border-collapse: collapse; font-size: 12.5px; }\n"
            + "  .r-kv td { padding: 3px 6px; vertical-align: top; }\n"
            + "  .r-kv td.k { color: #9ca3af; width: 30%; max-width: 200px; }\n"
            + "  .r-kv td.v { color: #e5e7eb; word-break: break-all; }\n"
            + "  .r-array { padding-left: 12px; border-left: 2px solid #374151; margin-left: 4px; }\n"
            + "  .r-empty { color: #6b7280; font-style: italic; }\n"
            + "</style>\n"
            + "</head>\n"
            + "<body>\n"
            + "<div class=\"app\">\n"
            + "  <!-- 左侧接口菜单 -->\n"
            + "  <aside class=\"sidebar\">\n"
            + "    <div class=\"brand\"><h1><span class=\"dot\"></span>数据搜索服务</h1><div class=\"sub\">datasearch-starter · REST 接口</div></div>\n"
            + "    <nav class=\"menu\" id=\"menu\"></nav>\n"
            + "    <div class=\"foot\"><a href=\"/datasearch\">查看接口索引 JSON</a></div>\n"
            + "  </aside>\n"
            + "  <!-- 请求配置 + 响应 -->\n"
            + "  <section class=\"main\">\n"
            + "    <div class=\"request\">\n"
            + "      <div class=\"req-row\">\n"
            + "        <div class=\"url-bar\">\n"
            + "          <span class=\"url-prefix\">GET</span>\n"
            + "          <input id=\"url\" placeholder=\"/datasearch/...\" spellcheck=\"false\">\n"
            + "        </div>\n"
            + "        <button class=\"send\" id=\"send\">发送</button>\n"
            + "      </div>\n"
            + "      <div class=\"req-meta\">\n"
            + "        <span class=\"tag\" id=\"ep-name\">成语 · 精确查询</span>\n"
            + "        <span class=\"tag\" id=\"ep-path\">/datasearch/idiom/get</span>\n"
            + "      </div>\n"
            + "    </div>\n"
            + "    <div class=\"params\">\n"
            + "      <div class=\"p-head\"><span class=\"p-title\">查询参数</span><span style=\"font-size:12px;color:var(--muted)\">仅 GET</span></div>\n"
            + "      <table class=\"p-table\" id=\"params\"><thead><tr><th style=\"width:34%\">名称</th><th>值</th><th style=\"width:44px\"></th></tr></thead><tbody></tbody></table>\n"
            + "      <button class=\"add-param\" id=\"addParam\">+ 添加参数</button>\n"
            + "    </div>\n"
            + "    <div class=\"response\">\n"
            + "      <div class=\"resp-head\">\n"
            + "        <span class=\"resp-status\" id=\"status\">未发送</span>\n"
            + "        <span class=\"resp-time\" id=\"time\"></span>\n"
            + "        <div class=\"tabs\">\n"
            + "          <button class=\"tab active\" data-tab=\"pretty\">格式化</button>\n"
            + "          <button class=\"tab\" data-tab=\"raw\">原始</button>\n"
            + "          <button class=\"tab\" data-tab=\"render\">渲染</button>\n"
            + "        </div>\n"
            + "      </div>\n"
            + "      <div class=\"resp-body\"><div class=\"placeholder\">从左侧选择接口，或编辑 URL / 参数后点击「发送」</div></div>\n"
            + "    </div>\n"
            + "  </section>\n"
            + "</div>\n"
            + "<script>\n"
            + "var API = 'http://' + location.host + '/datasearch';\n"
            + "var ENDPOINTS = [\n"
            + "  { group: '成语', items: [\n"
            + "    { name: '成语接龙', path: '/idiom/chain', params: [{ k: 'word', v: '守株待兔' }, { k: 'limit', v: '10' }] },\n"
            + "    { name: '精确查询', path: '/idiom/get', params: [{ k: 'word', v: '守株待兔' }] },\n"
            + "    { name: '模糊搜索', path: '/idiom/search', params: [{ k: 'keyword', v: '兔' }, { k: 'limit', v: '10' }] },\n"
            + "    { name: '随机成语', path: '/idiom/random', params: [] }\n"
            + "  ]},\n"
            + "  { group: '古诗词', items: [\n"
            + "    { name: '随机诗词', path: '/poetry/random', params: [] },\n"
            + "    { name: '按作者', path: '/poetry/author', params: [{ k: 'author', v: '李白' }, { k: 'limit', v: '5' }] },\n"
            + "    { name: '搜索', path: '/poetry/search', params: [{ k: 'keyword', v: '明月' }, { k: 'limit', v: '10' }] }\n"
            + "  ]},\n"
            + "  { group: '汉字字典', items: [\n"
            + "    { name: '查询', path: '/hanzi/get', params: [{ k: 'character', v: '中' }] },\n"
            + "    { name: '搜索', path: '/hanzi/search', params: [{ k: 'keyword', v: 'zhong' }, { k: 'limit', v: '10' }] },\n"
            + "    { name: '随机', path: '/hanzi/random', params: [] }\n"
            + "  ]},\n"
            + "  { group: '手机归属地', items: [\n"
            + "    { name: '查询', path: '/phone', params: [{ k: 'phone', v: '13800138000' }] }\n"
            + "  ]},\n"
            + "  { group: '星座运势', items: [\n"
            + "    { name: '查询', path: '/horoscope', params: [{ k: 'sign', v: '白羊座' }, { k: 'type', v: 'today' }] }\n"
            + "  ]},\n"
            + "  { group: '天气', items: [\n"
            + "    { name: '查询', path: '/weather', params: [{ k: 'city', v: '北京' }] }\n"
            + "  ]},\n"
            + "  { group: '节假日', items: [\n"
            + "    { name: '判定', path: '/holiday/check', params: [{ k: 'date', v: '2026-01-01' }] },\n"
            + "    { name: '年度安排', path: '/holiday/list', params: [{ k: 'year', v: '2026' }] }\n"
            + "  ]},\n"
            + "  { group: '行政区划', items: [\n"
            + "    { name: '列表', path: '/region', params: [{ k: 'level', v: '2' }] },\n"
            + "    { name: '下级', path: '/region/children', params: [{ k: 'parent', v: '110000' }] },\n"
            + "    { name: '树', path: '/region/tree', params: [{ k: 'level', v: '2' }] }\n"
            + "  ]},\n"
            + "  { group: '汇率', items: [\n"
            + "    { name: '换算', path: '/exchange/rate', params: [{ k: 'from', v: 'USD' }, { k: 'to', v: 'CNY' }] },\n"
            + "    { name: '汇率表', path: '/exchange/rates', params: [{ k: 'base', v: 'USD' }] }\n"
            + "  ]},\n"
            + "  { group: '快递', items: [\n"
            + "    { name: '物流轨迹', path: '/express', params: [{ k: 'trackingNo', v: 'SF123456789' }] }\n"
            + "  ]},\n"
            + "  { group: '台风', items: [\n"
            + "    { name: '活跃台风', path: '/typhoon/active', params: [] },\n"
            + "    { name: '详情', path: '/typhoon/detail', params: [{ k: 'tfid', v: '202618' }] }\n"
            + "  ]},\n"
            + "  { group: 'IP 定位 / 地址', items: [\n"
            + "    { name: '定位自身', path: '/location/self', params: [] },\n"
            + "    { name: 'IP 定位', path: '/location/ip', params: [{ k: 'ip', v: '8.8.8.8' }] },\n"
            + "    { name: '逆地理编码', path: '/geocode/reverse', params: [{ k: 'lat', v: '39.9' }, { k: 'lon', v: '116.4' }] },\n"
            + "    { name: 'IP 转地址', path: '/geocode/ip', params: [{ k: 'ip', v: '8.8.8.8' }] }\n"
            + "  ]}\n"
            + "];\n"
            + "var activePath = '/idiom/get';\n"
            + "var lastRaw = '';\n"
            + "var lastJson = null;\n"
            + "var lastStatus = 0;\n"
            + "var currentTab = 'pretty';\n"
            + "\n"
            + "/* 渲染左侧菜单 */\n"
            + "function buildMenu() {\n"
            + "  var menu = document.getElementById('menu');\n"
            + "  menu.innerHTML = '';\n"
            + "  ENDPOINTS.forEach(function (g, gi) {\n"
            + "    var div = document.createElement('div');\n"
            + "    div.className = 'group' + (gi > 0 ? ' collapsed' : '');\n"
            + "    var head = document.createElement('div');\n"
            + "    head.className = 'group-name';\n"
            + "    head.innerHTML = '<span>' + esc(g.group) + '</span><span class=\"chev\">\\u25BC</span>';\n"
            + "    head.addEventListener('click', function () { div.classList.toggle('collapsed'); });\n"
            + "    var items = document.createElement('div');\n"
            + "    items.className = 'group-items';\n"
            + "    g.items.forEach(function (it) {\n"
            + "      var btn = document.createElement('button');\n"
            + "      btn.className = 'item';\n"
            + "      btn.innerHTML = '<span class=\"method\">GET</span><span>' + esc(it.name) + '</span>';\n"
            + "      btn.addEventListener('click', function () { selectEndpoint(g, it, btn); });\n"
            + "      items.appendChild(btn);\n"
            + "      it._btn = btn;\n"
            + "    });\n"
            + "    div.appendChild(head);\n"
            + "    div.appendChild(items);\n"
            + "    menu.appendChild(div);\n"
            + "  });\n"
            + "}\n"
            + "\n"
            + "function selectEndpoint(g, it, btn) {\n"
            + "  document.querySelectorAll('.item.active').forEach(function (b) { b.classList.remove('active'); });\n"
            + "  btn.classList.add('active');\n"
            + "  activePath = it.path;\n"
            + "  document.getElementById('ep-name').textContent = g.group + ' · ' + it.name;\n"
            + "  document.getElementById('ep-path').textContent = '/datasearch' + it.path;\n"
            + "  renderParams(it.params);\n"
            + "  syncUrl();\n"
            + "  send();\n"
            + "}\n"
            + "\n"
            + "/* 参数表 */\n"
            + "function renderParams(params) {\n"
            + "  var tbody = document.querySelector('#params tbody');\n"
            + "  tbody.innerHTML = '';\n"
            + "  if (!params || !params.length) { params = [{ k: '', v: '' }]; }\n"
            + "  params.forEach(function (p) { addParamRow(p.k, p.v); });\n"
            + "}\n"
            + "\n"
            + "function addParamRow(k, v) {\n"
            + "  var tbody = document.querySelector('#params tbody');\n"
            + "  var tr = document.createElement('tr');\n"
            + "  tr.innerHTML = '<td><input class=\"p-k\" placeholder=\"参数名\" value=\"' + escAttr(k || '') + '\"></td>' +\n"
            + "                '<td><input class=\"p-v\" placeholder=\"值\" value=\"' + escAttr(v || '') + '\"></td>' +\n"
            + "                '<td><button class=\"p-del\" title=\"删除\">x</button></td>';\n"
            + "  tr.querySelector('.p-del').addEventListener('click', function () { tr.remove(); syncUrl(); });\n"
            + "  tr.querySelectorAll('input').forEach(function (inp) { inp.addEventListener('input', syncUrl); });\n"
            + "  tbody.appendChild(tr);\n"
            + "}\n"
            + "\n"
            + "/* URL 同步 */\n"
            + "function syncUrl() {\n"
            + "  var qs = [];\n"
            + "  document.querySelectorAll('#params tbody tr').forEach(function (tr) {\n"
            + "    var k = tr.querySelector('.p-k').value.trim();\n"
            + "    var v = tr.querySelector('.p-v').value.trim();\n"
            + "    if (k) { qs.push(encodeURIComponent(k) + '=' + encodeURIComponent(v)); }\n"
            + "  });\n"
            + "  var url = '/datasearch' + activePath;\n"
            + "  if (qs.length) { url += '?' + qs.join('&'); }\n"
            + "  document.getElementById('url').value = url;\n"
            + "}\n"
            + "\n"
            + "/* 发送请求 */\n"
            + "async function send() {\n"
            + "  var url = document.getElementById('url').value.trim();\n"
            + "  if (!url) { url = '/datasearch' + activePath; document.getElementById('url').value = url; }\n"
            + "  var btn = document.getElementById('send');\n"
            + "  var body = document.querySelector('.resp-body');\n"
            + "  var status = document.getElementById('status');\n"
            + "  var time = document.getElementById('time');\n"
            + "  btn.disabled = true; btn.textContent = '发送中...';\n"
            + "  status.className = 'resp-status'; status.textContent = '请求中...'; time.textContent = '';\n"
            + "  var t0 = performance.now();\n"
            + "  try {\n"
            + "    var resp = await fetch(url);\n"
            + "    var raw = await resp.text();\n"
            + "    var ms = Math.round(performance.now() - t0);\n"
            + "    lastRaw = raw; lastStatus = resp.status;\n"
            + "    try { lastJson = JSON.parse(raw); } catch (e) { lastJson = null; }\n"
            + "    status.className = 'resp-status ' + (resp.status >= 400 ? 'err' : 'ok');\n"
            + "    status.textContent = resp.status + (resp.status === 200 ? ' OK' : ' ERROR');\n"
            + "    time.textContent = ms + ' ms · ' + (raw.length / 1024).toFixed(2) + ' KB';\n"
            + "    renderBody();\n"
            + "  } catch (e) {\n"
            + "    lastJson = null; lastRaw = ''; lastStatus = 0;\n"
            + "    status.className = 'resp-status err'; status.textContent = '请求失败';\n"
            + "    time.textContent = e.message;\n"
            + "    body.innerHTML = '<div class=\"placeholder\">网络错误: ' + esc(e.message) + '</div>';\n"
            + "  } finally {\n"
            + "    btn.disabled = false; btn.textContent = '发送';\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "/* 响应体渲染 */\n"
            + "function renderBody() {\n"
            + "  var body = document.querySelector('.resp-body');\n"
            + "  if (!lastStatus) { body.innerHTML = '<div class=\"placeholder\">未发送</div>'; return; }\n"
            + "  if (currentTab === 'pretty') {\n"
            + "    body.innerHTML = '<pre class=\"json\">' + esc(lastJson ? JSON.stringify(lastJson, null, 2) : lastRaw) + '</pre>';\n"
            + "  } else if (currentTab === 'raw') {\n"
            + "    body.innerHTML = '<pre class=\"json\">' + esc(lastRaw) + '</pre>';\n"
            + "  } else {\n"
            + "    body.innerHTML = '<div class=\"render\">' + renderTree(lastJson) + '</div>';\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "/* 渲染视图：JSON -> 卡片/表格 */\n"
            + "function renderTree(node) {\n"
            + "  if (node === null || node === undefined) { return '<span class=\"r-empty\">null</span>'; }\n"
            + "  if (Array.isArray(node)) {\n"
            + "    if (!node.length) { return '<span class=\"r-empty\">空数组</span>'; }\n"
            + "    if (node.every(function (x) { return x && typeof x === 'object' && !Array.isArray(x); })) {\n"
            + "      return node.map(function (it, i) {\n"
            + "        return '<div class=\"r-card\"><div class=\"r-title\">#' + (i + 1) + '</div>' + kvTable(it) + '</div>';\n"
            + "      }).join('');\n"
            + "    }\n"
            + "    return '<div class=\"r-array\">' + node.map(function (x) { return renderTree(x); }).join('') + '</div>';\n"
            + "  }\n"
            + "  if (typeof node === 'object') { return kvTable(node); }\n"
            + "  return '<span>' + esc(String(node)) + '</span>';\n"
            + "}\n"
            + "\n"
            + "function kvTable(obj) {\n"
            + "  var rows = Object.keys(obj).map(function (k) {\n"
            + "    var v = obj[k];\n"
            + "    var html;\n"
            + "    if (v && typeof v === 'object') { html = renderTree(v); }\n"
            + "    else if (v === null || v === undefined) { html = '<span class=\"r-empty\">null</span>'; }\n"
            + "    else if (typeof v === 'boolean') { html = '<span>' + (v ? 'true' : 'false') + '</span>'; }\n"
            + "    else { html = '<span>' + esc(String(v)) + '</span>'; }\n"
            + "    return '<tr><td class=\"k\">' + esc(k) + '</td><td class=\"v\">' + html + '</td></tr>';\n"
            + "  }).join('');\n"
            + "  return '<table class=\"r-kv\">' + rows + '</table>';\n"
            + "}\n"
            + "\n"
            + "/* 转义 */\n"
            + "function esc(s) {\n"
            + "  return String(s == null ? '' : s).replace(/[&<>\"']/g, function (c) {\n"
            + "    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '\"': '&quot;', \"'\": '&#39;' }[c];\n"
            + "  });\n"
            + "}\n"
            + "function escAttr(s) { return esc(s).replace(/&quot;/g, '\"'); }\n"
            + "\n"
            + "/* 事件绑定 */\n"
            + "document.getElementById('send').addEventListener('click', send);\n"
            + "document.getElementById('url').addEventListener('keydown', function (e) { if (e.key === 'Enter') { send(); } });\n"
            + "document.getElementById('addParam').addEventListener('click', function () { addParamRow('', ''); syncUrl(); });\n"
            + "document.querySelectorAll('.tab').forEach(function (t) {\n"
            + "  t.addEventListener('click', function () {\n"
            + "    document.querySelectorAll('.tab').forEach(function (x) { x.classList.remove('active'); });\n"
            + "    t.classList.add('active');\n"
            + "    currentTab = t.dataset.tab;\n"
            + "    renderBody();\n"
            + "  });\n"
            + "});\n"
            + "\n"
            + "/* 初始化 */\n"
            + "buildMenu();\n"
            + "var first = ENDPOINTS[0].items[0];\n"
            + "selectEndpoint(ENDPOINTS[0], first, first._btn);\n"
            + "</script>\n"
            + "</body>\n"
            + "</html>";
}
