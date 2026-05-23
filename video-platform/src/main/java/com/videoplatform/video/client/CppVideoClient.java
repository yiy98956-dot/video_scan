package com.videoplatform.video.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoplatform.video.dto.CppMovieDTO;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * C++ PlayerServer HTTP 客户端
 * <p>
 * 对接 C++ 的真实 REST API，获取电影/电视剧/直播数据。
 * C++ 不连数据库，所有数据采自 CMS JSON 源。
 */
@Slf4j
@Component
public class CppVideoClient {

    private final String baseUrl;
    private WebClient webClient;
    private final ObjectMapper objectMapper;

    // Go 的 page/list API 返回 {items:[], total:N} 格式
    private static class GoPageResult {
        public List<CppMovieDTO> items;
        public int total;
    }

    private List<CppMovieDTO> parseMovieList(String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            GoPageResult result = objectMapper.readValue(json, GoPageResult.class);
            return result.items != null ? result.items : Collections.emptyList();
        } catch (Exception e) {
            log.warn("parseMovieList failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // 返回 items + total 的页面结果
    public static class PageData {
        public List<CppMovieDTO> items;
        public int total;
    }

    public PageData getMoviePageWithTotal(int page, int size, String genre, String sort, int year, String area, String type) {
        try {
            String path;
            if ((genre != null && !genre.isBlank()) || (type != null && !type.isBlank())) {
                path = "/api/movies/category?g=" + (genre != null ? genre : "")
                        + "&pg=" + page + "&size=" + size
                        + (sort != null && !sort.isEmpty() && !sort.equals("hot") ? "&sort=" + sort : "")
                        + (year > 0 ? "&year=" + year : "")
                        + (area != null && !area.isEmpty() ? "&area=" + area : "")
                        + (type != null && !type.isEmpty() ? "&type=" + type : "");
            } else {
                path = "/api/movies/page?pg=" + page + "&size=" + size
                        + (sort != null && !sort.isEmpty() && !sort.equals("hot") ? "&sort=" + sort : "");
            }
            String json = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            if (json == null || json.isEmpty()) return new PageData();
            GoPageResult result = objectMapper.readValue(json, GoPageResult.class);
            PageData pd = new PageData();
            pd.items = result.items != null ? result.items : Collections.emptyList();
            pd.total = result.total;
            return pd;
        } catch (Exception e) {
            log.warn("getMoviePageWithTotal failed: {}", e.getMessage());
            return new PageData();
        }
    }

    public CppVideoClient(
            @Value("${cpp.service.base-url:http://localhost:54567}") String baseUrl,
            ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        HttpClient httpClient = HttpClient.create()
                .secure(t -> {
                    try {
                        t.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
                         .handshakeTimeout(Duration.ofSeconds(30));
                    }
                    catch (SSLException e) { log.warn("SSL init failed", e); }
                })
                .responseTimeout(Duration.ofSeconds(60))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .followRedirect(true);

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                .codecs(config -> config.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();

        log.info("CppVideoClient initialized, baseUrl={}", baseUrl);
    }

    // ══════════════════════════════════════════════
    // 通用原始请求（用于 proxy 场景）
    // ══════════════════════════════════════════════

    public String fetchRaw(String path) {
        try {
            return webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.warn("fetchRaw failed path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 通用原始请求（返回字节数组，用于视频流代理）
     */
    public byte[] fetchRawBytes(String path) {
        try {
            return webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("fetchRawBytes failed path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 带完整查询参数的 GET 请求（避免 URI 模板解析冲突）
     * 将 path 和 query 分开构造，WebClient 不会二次编码
     */
    public byte[] fetchGetBytes(String path, String... queryParams) {
        try {
            return webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        for (int i = 0; i < queryParams.length; i += 2) {
                            if (i + 1 < queryParams.length) {
                                uriBuilder.queryParam(queryParams[i], queryParams[i + 1]);
                            }
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("fetchGetBytes failed path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * 直接从绝对 URL 获取字节流（自动选择 Referer）
     */
    public byte[] fetchDirectBytes(String absoluteUrl) {
        String referer = urlBase(absoluteUrl);
        if (referer.isEmpty() || referer.contains("bdzybf")) {
            referer = "https://player.bdzybf11.com/";
        }
        return fetchDirectBytes(absoluteUrl, referer);
    }

    /**
     * 直接从绝对 URL 获取字节流（自定义 Referer）
     */
    public byte[] fetchDirectBytes(String absoluteUrl, String referer) {
        return fetchDirectBytes(absoluteUrl, referer, 30);
    }

    /**
     * 直接从绝对 URL 获取字节流（自定义 Referer + 超时秒数）
     * @param timeoutSeconds 响应超时（秒），fallback 建议 8s，常规 30s
     */
    public byte[] fetchDirectBytes(String absoluteUrl, String referer, long timeoutSeconds) {
        try {
            String ref = (referer != null && !referer.isEmpty()) ? referer : urlBase(absoluteUrl);
            if (ref.isEmpty()) ref = "https://player.bdzybf11.com/";

            long handshakeTimeoutSec = Math.max(timeoutSeconds, 10); // SSL 握手至少 10 秒

            return WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(
                            HttpClient.create()
                                    .secure(t -> {
                                        try {
                                            t.sslContext(SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build())
                                             .handshakeTimeout(Duration.ofSeconds(handshakeTimeoutSec));
                                        }
                                        catch (SSLException e) { log.warn("SSL init failed", e); }
                                    })
                                    .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) (timeoutSeconds * 1000))
                                    .followRedirect(true)))
                    .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                    .defaultHeader("Referer", ref)
                    .defaultHeader("Origin", ref)
                    .defaultHeader("Accept", "*/*")
                    .defaultHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .exchangeStrategies(ExchangeStrategies.builder()
                            .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                            .build())
                    .build()
                    .get()
                    .uri(absoluteUrl)
                    .exchangeToMono(response -> {
                        if (response.statusCode().is2xxSuccessful()) {
                            return response.bodyToMono(byte[].class);
                        }
                        log.warn("fetchDirectBytes non-2xx status={} for url={}", response.statusCode(), absoluteUrl);
                        // 非 2xx 也尝试读 body（某些 CDN 即使 403 也会返回内容）
                        return response.bodyToMono(byte[].class)
                                .onErrorReturn(new byte[0])
                                .defaultIfEmpty(new byte[0]);
                    })
                    .block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("fetchDirectBytes failed url={}: {}: {}", absoluteUrl, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * 从 URL 中提取协议 + 主机 + 路径前缀作为 Referer
     */
    private String urlBase(String url) {
        int p = url.indexOf("://");
        if (p == -1) return "";
        int hs = p + 3;
        int sl = url.indexOf('/', hs);
        if (sl == -1) return url + "/";
        return url.substring(0, sl + 1);
    }

    public String postRaw(String path) {
        try {
            return webClient.post()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("postRaw failed path={}: {}", path, e.getMessage());
            return null;
        }
    }

    /** 带 JSON body 的 POST 请求 */
    public String postRaw(String path, String jsonBody) {
        try {
            return webClient.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    .bodyValue(jsonBody != null ? jsonBody : "")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));
        } catch (Exception e) {
            log.warn("postRaw(body) failed path={}: {}", path, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════
    // 电影列表 — /api/movies/page?pg=&size=
    // ══════════════════════════════════════════════

    public List<CppMovieDTO> getMoviePage(int page, int size) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/movies/page")
                            .queryParam("pg", page)
                            .queryParam("size", size)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return parseMovieList(json);
        } catch (Exception e) {
            log.warn("getMoviePage failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════
    // 分类筛选 — /api/movies/category?g=&pg=&size=
    // ══════════════════════════════════════════════

    public List<CppMovieDTO> getMovieCategory(String genre, int page, int size,
                                                String sort, int year, String area, String type) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> {
                        var ub = uriBuilder.path("/api/movies/category")
                                .queryParam("g", genre != null ? genre : "")
                                .queryParam("pg", page)
                                .queryParam("size", size);
                        if (sort != null && !sort.isEmpty() && !sort.equals("hot"))
                            ub.queryParam("sort", sort);
                        if (year > 0) ub.queryParam("year", year);
                        if (area != null && !area.isEmpty()) ub.queryParam("area", area);
                        if (type != null && !type.isEmpty()) ub.queryParam("type", type);
                        return ub.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return parseMovieList(json);
        } catch (Exception e) {
            log.warn("getMovieCategory failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════
    // 高级查询 — /api/movies/query
    // ══════════════════════════════════════════════

    public List<CppMovieDTO> queryMovies(String type, String genre, int year, String area,
                                         String keyword, int page, int size, String source) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> {
                        var ub = uriBuilder.path("/api/movies/query")
                                .queryParam("pg", page)
                                .queryParam("size", size);
                        ifNotEmpty(ub, "type", type);
                        ifNotEmpty(ub, "genre", genre);
                        if (year > 0) ub.queryParam("year", year);
                        ifNotEmpty(ub, "area", area);
                        ifNotEmpty(ub, "q", keyword);
                        ifNotEmpty(ub, "source", source);
                        return ub.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return parseMovieList(json);
        } catch (Exception e) {
            log.warn("queryMovies failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════
    // 电影详情 — /api/movies/detail?id=&source=
    // ══════════════════════════════════════════════

    public CppMovieDTO getMovieDetail(int vodId, String source) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> {
                        var ub = uriBuilder.path("/api/movies/detail")
                                .queryParam("id", vodId);
                        ifNotEmpty(ub, "source", source);
                        return ub.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            if (json == null || json.isEmpty()) return null;
            // Go返回 {"error":"..."} 时，不要解析为有效对象
            if (json.startsWith("{\"error\"")) return null;
            return objectMapper.readValue(json, CppMovieDTO.class);
        } catch (Exception e) {
            log.warn("getMovieDetail failed, id={}: {}", vodId, e.getMessage());
            return null;
        }
    }

    // ══════════════════════════════════════════════
    // 搜索 — /api/movies/search?q=
    // ══════════════════════════════════════════════

    public List<CppMovieDTO> search(String keyword) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/movies/search")
                            .queryParam("q", keyword)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            return parseMovieList(json);
        } catch (Exception e) {
            log.warn("search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 高级搜索（带分面过滤）
     */
    public List<CppMovieDTO> searchAdvanced(String keyword, String genre, String source,
                                            String type, String area, int page, int size) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> {
                        var ub = uriBuilder.path("/api/movies/search_advanced")
                                .queryParam("q", keyword)
                                .queryParam("pg", page)
                                .queryParam("size", size);
                        ifNotEmpty(ub, "genre", genre);
                        ifNotEmpty(ub, "source", source);
                        ifNotEmpty(ub, "type", type);
                        ifNotEmpty(ub, "area", area);
                        return ub.build();
                    })
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            var root = objectMapper.readTree(json);
            var resultsNode = root.get("results");
            if (resultsNode != null && resultsNode.isArray()) {
                return objectMapper.readValue(
                        resultsNode.traverse(),
                        new TypeReference<List<CppMovieDTO>>() {});
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("searchAdvanced failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════
    // 分类/类型列表 — Go 返回纯字符串数组 ["喜剧","动作",...]
    // ══════════════════════════════════════════════

    public List<String> getGenres() {
        return fetchStringList("/api/movies/genres", "getGenres");
    }

    public List<String> getGenresByType(String type) {
        return fetchStringList("/api/movies/genres?type=" + type, "getGenresByType");
    }

    public List<String> getTypes() {
        return fetchStringList("/api/movies/types", "getTypes");
    }

    private List<String> fetchStringList(String path, String methodName) {
        try {
            String json = webClient.get()
                    .uri(path)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(6));
            if (json == null || json.isEmpty()) return Collections.emptyList();
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("{} failed: {}", methodName, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ══════════════════════════════════════════════
    // 播放工具
    // ══════════════════════════════════════════════

    /**
     * 获取视频的第一个播放 URL
     */
    public String getFirstPlayUrl(CppMovieDTO movie) {
        if (movie == null || movie.getPlays() == null) return null;
        for (var group : movie.getPlays()) {
            if (group.getUrls() != null && !group.getUrls().isEmpty()) {
                return group.getUrls().get(0).getUrl();
            }
        }
        return null;
    }

    /**
     * C++ proxy 地址
     */
    public String getProxyUrl(String rawUrl) {
        return baseUrl + "/api/movies/proxy?url=" + rawUrl;
    }

    // ══════════════════════════════════════════════
    // 内部工具
    // ══════════════════════════════════════════════

    private void ifNotEmpty(org.springframework.web.util.UriBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.queryParam(key, value);
        }
    }
}
