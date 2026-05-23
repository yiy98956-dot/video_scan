package com.videoplatform.video.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.dto.*;
import com.videoplatform.video.entity.CategoryVisibility;
import com.videoplatform.video.entity.VideoMeta;
import com.videoplatform.video.mapper.CategoryVisibilityMapper;
import com.videoplatform.video.mapper.VideoMetaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 视频元数据服务
 * <p>
 * 数据来源：C++ HTTP API（不缓存详情，C++ 数据实时变化）
 * 社交计数（like/collect/comment）来自本地 video_meta 表。
 * <p>
 * video_meta 记录在用户首次点赞/收藏/播放时自动创建。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetaService {

    /** 含伦理等违规内容的源，数据不可用于展示 */
    private static final Set<String> BAD_SOURCES = Set.of("SuoNi", "MaoTai");

    /** 标题中若含这些关键词 → 数据不可靠（如 [电影解说] 混入电视剧分类） */
    private static final Set<String> BAD_TITLE_PATTERNS = Set.of(
            "电影解说", "电影剪辑", "电影预告", "电影片段", "电影花絮");

    /** remark 中单集标识 → 电视剧/短剧分类下出现这些说明数据有误 */
    private static final Set<String> SINGLE_EPISODE_REMARKS = Set.of(
            "HD", "正片", "正片HD", "超清", "高清", "高清版", "蓝光", "4K", "1080P");

    /** 标准一级分类名 */
    private static final Set<String> TYPE_NAMES = Set.of(
            "电影","电视剧","短剧","动漫","综艺","纪录片","少儿","体育","资讯");

    /**
     * 检查视频的分类是否对用户可见（不被管理员隐藏）
     * 从传入的隐藏集合中验证
     */
    private boolean isCategoryVisible(CppMovieDTO movie, Set<String> hiddenTypes, Set<String> hiddenGenres) {
        if (hiddenTypes.isEmpty() && hiddenGenres.isEmpty()) return true;

        // 验证：type（一级分类）
        String type = movie.getType();
        if (type != null && !type.isEmpty() && TYPE_NAMES.contains(type) && hiddenTypes.contains(type)) {
            return false;
        }

        // 验证：genre（二级子分类）
        String genre = movie.getGenre();
        if (genre != null && !genre.isBlank()) {
            String[] parts = genre.split("[,/，、]");
            for (String p : parts) {
                p = p.trim();
                if (!p.isEmpty() && hiddenGenres.contains(p)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * 获取当前所有的隐藏分类配置
     */
    @Cacheable(value = "category:hidden", unless = "#result == null")
    private Map<String, Set<String>> getHiddenCategories() {
        Map<String, Set<String>> result = new HashMap<>();
        Set<String> hiddenTypes = new HashSet<>();
        Set<String> hiddenGenres = new HashSet<>();
        result.put("types", hiddenTypes);
        result.put("genres", hiddenGenres);

        try {
            List<CategoryVisibility> hiddenList = visibilityMapper.selectList(
                    new LambdaQueryWrapper<CategoryVisibility>()
                            .eq(CategoryVisibility::getVisible, false));

            for (CategoryVisibility cv : hiddenList) {
                if (cv.getCategoryId() != null && cv.getCategoryId() < 100) {
                    if (cv.getCategoryName() != null) hiddenTypes.add(cv.getCategoryName());
                } else {
                    if (cv.getCategoryName() != null) hiddenGenres.add(cv.getCategoryName());
                }
            }
            log.debug("Loaded {} hidden categories", hiddenList.size());
        } catch (Exception e) {
            log.warn("Failed to query hidden categories: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 数据真实性检查：剔除 CMS 源分类不准导致的垃圾数据
     * - 标题含 电影解说/电影剪辑 等 → 不是正经电视剧/短剧
     * - 电视剧/短剧类型的 remark 为 "HD"/"正片" → 实际是单集电影
     */
    private boolean isDataAuthentic(CppMovieDTO m) {
        String title = m.getTitle();
        if (title != null) {
            String t = title.toLowerCase();
            for (String p : BAD_TITLE_PATTERNS) {
                if (t.contains(p)) return false;
            }
        }
        // 电视剧/短剧类下，remark 为单集标识 → 数据不可靠
        String type = m.getType();
        if (type != null && (type.equals("电视剧") || type.equals("短剧"))) {
            String remark = m.getRemark();
            if (remark != null) {
                String r = remark.trim().toLowerCase();
                // 排除列表：如果备注包含 these 词，即便完全匹配单集词，也放行（如：更新至第12集HD）
                if (r.contains("第") || r.contains("集") || r.contains("更新") || r.contains("季")) {
                    return true;
                }
                for (String se : SINGLE_EPISODE_REMARKS) {
                    if (r.equals(se.toLowerCase())) return false;
                }
            }
        }
        return true;
    }

    private final CppVideoClient cppClient;
    private final VideoMetaMapper videoMetaMapper;
    private final CategoryVisibilityMapper visibilityMapper;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    @Qualifier("videoExecutor")
    private final Executor videoExecutor;

    // ══════════════════════════════════════════════
    // 启动预热 — 加速首次页面打开
    // ══════════════════════════════════════════════

    @PostConstruct
    public void warmup() {
        log.info("===== 开始预热缓存 =====");
        videoExecutor.execute(() -> {
            try {
                // 0. 先清空旧缓存，确保预热的是新数据
                clearVideoListCache();
                log.info("[warmup] 已清空旧视频列表缓存");

                // 1. 预热首页列表（参数必须和前端一致: null=不传，否则缓存key不匹配）
                log.info("[warmup] 预热首页列表...");
                VideoListVO vo = getVideoList(1, 40, null, "time", 0, null, null);
                log.info("[warmup] 首页列表就绪: {} 条", vo.getItems().size());

                // 2. 预热热映/轮播
                log.info("[warmup] 预热热映列表...");
                VideoListVO hot = getVideoList(1, 20, null, "hot", 0, null, null);
                log.info("[warmup] 热映列表就绪: {} 条", hot.getItems().size());

                log.info("===== 预热完成 =====");
            } catch (Exception e) {
                log.warn("[warmup] 预热异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 启动时清空旧缓存，确保首页展示的是新过滤规则下的数据
     */
    private void clearVideoListCache() {
        Cache cache = cacheManager.getCache("video:list");
        if (cache != null) {
            cache.clear();
            log.info("[warmup] 已清空 video:list 缓存");
        }
    }

    @Cacheable(value = "video:list", key = "'p'+#page+'s'+#size+'g'+#genre+'o'+#sort+'y'+#year+'a'+#area+'t'+#type",
               unless = "#result == null || #result.items.isEmpty()")
    public VideoListVO getVideoList(int page, int size, String genre, String sort, int year, String area, String type) {
        // 1. 映射参数
        String goGenre = (genre != null && !genre.isEmpty()) ? genre : null;
        String goType = (type != null && !type.isEmpty()) ? type : null;

        // 2. 调用 Go 端获取完整的分页数据
        CppVideoClient.PageData pd = cppClient.getMoviePageWithTotal(page, size, goGenre, sort, year, area, goType);

        // 3. 准备过滤环境：获取一次隐藏分类，避免在循环中查询 DB
        Map<String, Set<String>> hidden = getHiddenCategories();
        Set<String> hiddenTypes = hidden.get("types");
        Set<String> hiddenGenres = hidden.get("genres");

        // 4. 使用并行流并行处理数据清洗和推导逻辑，显著提升多核环境下的处理速度
        List<CppMovieDTO> filtered = (pd.items != null ? pd.items : Collections.<CppMovieDTO>emptyList())
                .parallelStream()
                .peek(this::refineType)
                .filter(m -> m.getId() > 0)
                .filter(m -> m.getTitle() != null && !m.getTitle().isBlank())
                .filter(m -> m.getCoverUrl() != null && !m.getCoverUrl().isBlank())
                .filter(m -> m.getSource() == null || !BAD_SOURCES.contains(m.getSource()))
                .filter(this::isDataAuthentic)
                .filter(m -> isCategoryVisible(m, hiddenTypes, hiddenGenres))
                .collect(Collectors.toList());

        // 5. refineType 后重新按 type 过滤，确保分类一致性
        //    Go 按 type 过滤后返回数据，但 refineType 可能修改了 type，
        //    导致"电影"分类下出现"电视剧"的数据
        if (goType != null && !goType.isEmpty()) {
            filtered = filtered.stream()
                    .filter(m -> goType.equals(m.getType()))
                    .collect(Collectors.toList());
        }

        // 批量查询 video_meta，避免 N+1 问题
        List<Integer> movieIds = filtered.stream()
                .map(CppMovieDTO::getId)
                .collect(Collectors.toList());
        Map<Integer, VideoMeta> metaMap = batchQueryLocalMeta(movieIds);

        List<VideoItemVO> vos = filtered.stream()
                .map(m -> toItemVO(m, metaMap.get(m.getId())))
                .collect(Collectors.toList());

        VideoListVO result = new VideoListVO();
        result.setItems(vos);
        result.setPage(page);
        result.setSize(size);
        // 使用过滤后的实际数量，而非 Go 返回的 total
        // 当有 type 过滤时，Go 的 total 可能包含被 refineType 修改的数据
        int actualTotal = vos.isEmpty() ? 0 : Math.max(pd.total - (pd.items.size() - filtered.size()), vos.size());
        result.setTotal(actualTotal);
        result.setTotalPages((int) Math.ceil((double) actualTotal / size));
        return result;
    }

    // ══════════════════════════════════════════════
    // 视频详情（含播放源，Redis 缓存 10 分钟）
    // ══════════════════════════════════════════════

    @Cacheable(value = "video:detail", key = "#vodId+'_'+#source", unless = "#result == null")
    public VideoDetailVO getVideoDetail(int vodId, String source) {
        log.info("getVideoDetail called for vodId={}, source={}", vodId, source);
        CppMovieDTO movie = cppClient.getMovieDetail(vodId, source);
        // fallback: 指定 source 数据不全（null 或 无播放源）时，不带 source 重试
        boolean needsFallback = movie == null || movie.getPlays() == null || movie.getPlays().isEmpty();
        if (needsFallback && source != null && !source.isEmpty()) {
            log.warn("getVideoDetail: source={} incomplete for vodId={} (movie={} plays={}), retrying without source",
                    source, vodId, movie != null ? "ok" : "null",
                    movie != null && movie.getPlays() != null ? movie.getPlays().size() : 0);
            movie = cppClient.getMovieDetail(vodId, null);
        }
        if (movie == null) {
            log.warn("getVideoDetail: C++ returned null for vodId={}", vodId);
            return null;
        }
        // 数据有效性检查：标题为空 → 无效数据，不缓存
        if (movie.getTitle() == null || movie.getTitle().isBlank()) {
            log.warn("getVideoDetail: C++ returned empty title for vodId={}, not caching", vodId);
            return null;
        }
        log.info("C++ movie detail: title='{}', plays={}", movie.getTitle(),
                movie.getPlays() != null ? movie.getPlays().size() : 0);

        // Java 第3层类型判别
        refineType(movie);

        // 合并本地社交计数
        VideoMeta local = queryLocalMeta(vodId);

        // 获取播放地址
        String playUrl = cppClient.getFirstPlayUrl(movie);
        String proxyUrl = (playUrl != null) ? cppClient.getProxyUrl(playUrl) : null;

        VideoDetailVO vo = new VideoDetailVO();
        vo.setCmsVideoId(movie.getId());
        vo.setSource(movie.getSource());
        vo.setLocalId(local != null ? local.getId() : null);
        vo.setTitle(movie.getTitle());
        vo.setCoverUrl(movie.getCoverUrl());
        vo.setYear(movie.getYear());
        vo.setArea(movie.getArea());
        vo.setGenre(movie.getGenre());
        vo.setType(movie.getType());
        vo.setDirector(movie.getDirector());
        vo.setActors(movie.getActors());
        vo.setDescription(movie.getDescription());
        vo.setScore(movie.getScore());
        vo.setRemark(movie.getRemark());
        vo.setPlayUrl(proxyUrl);
        vo.setRawPlayUrl(playUrl);
        vo.setPlays(movie.getPlays());
        vo.setPlayCount(local != null ? local.getPlayCount() : 0);
        vo.setLikeCount(local != null ? local.getLikeCount() : 0);
        vo.setCollectCount(local != null ? local.getCollectCount() : 0);
        vo.setCommentCount(local != null ? local.getCommentCount() : 0);
        return vo;
    }

    // ─── 分类/类型列表 ───

    /** 腾讯视频风格的标准类型顺序 */
    private static final List<String> CANONICAL_TYPES = List.of(
        "电影", "电视剧", "综艺", "动漫", "纪录片"
    );

    /** genre 规范化映射表 — 把各种变体映射到标准大类名 */
    private static final Map<String, String> GENRE_NORMALIZE = new HashMap<>();
    static {
        // 标准大类（白名单）
        String[][] major = {
            {"动作","动作"},{"喜剧","喜剧"},{"爱情","爱情"},{"科幻","科幻"},
            {"悬疑","悬疑"},{"恐怖","恐怖"},{"剧情","剧情"},{"犯罪","犯罪"},
            {"战争","战争"},{"古装","古装"},{"奇幻","奇幻"},{"冒险","冒险"},
            {"动画","动画"},{"纪录片","纪录片"},{"短片","短片"},{"历史","历史"},
            {"音乐","音乐"},{"经典","经典"},{"军事","军事"},{"武侠","武侠"},{"惊悚","惊悚"},
        };
        for (String[] m : major) GENRE_NORMALIZE.put(m[0], m[1]);

        // 变体映射到标准名（a.k.a. 去重）
        String[][] variants = {
            {"动作片","动作"},{"喜剧片","喜剧"},{"爱情片","爱情"},{"科幻片","科幻"},
            {"恐怖片","恐怖"},{"剧情片","剧情"},{"战争片","战争"},{"古装剧","古装"},
            {"奇幻剧","奇幻"},{"冒险片","冒险"},{"动画片","动画"},{"动作剧","动作"},
            {"喜剧剧","喜剧"},{"爱情剧","爱情"},{"犯罪剧","犯罪"},{"悬疑剧","悬疑"},
            {"恐怖剧","恐怖"},{"科幻剧","科幻"},{"武侠剧","武侠"},{"奇幻片","奇幻"},
            {"冒险剧","冒险"},{"战争剧","战争"},{"历史剧","历史"},{"古装片","古装"},
            {"惊悚片","惊悚"},{"武侠片","武侠"},{"纪录","纪录片"},{"记录","纪录片"},
            {"灾难","动作"},{"警匪","犯罪"},{"黑帮","犯罪"},{"谍战","悬疑"},
            {"穿越","奇幻"},{"丧尸","恐怖"},{"僵尸","恐怖"},{"鬼怪","恐怖"},
            {"魔幻","奇幻"},{"悬疑片","悬疑"},{"竞技","动作"},{"热血","动作"},
        };
        for (String[] v : variants) {
            GENRE_NORMALIZE.put(v[0], v[1]);
        }
    }

    /** 管理员可见性控制 —— 隐藏低频体裁 */
    private final ConcurrentHashMap<String, Boolean> genreVisibility = new ConcurrentHashMap<>();

    @Cacheable(value = "video:categories", unless = "#result.isEmpty()")
    public List<String> getGenres() {
        return getGenresInternal(null);
    }

    /** 按类型获取体裁（不做缓存） */
    public List<String> getGenresByType(String type) {
        return getGenresInternal(type);
    }

    private List<String> getGenresInternal(String type) {
        List<String> raw;
        if (type != null && !type.isBlank()) {
            raw = cppClient.getGenresByType(type);
        } else {
            raw = cppClient.getGenres();
        }
        List<String> all = normalizeGenres(raw);
        // 初始化新出现的体裁为可见
        for (String g : all) {
            genreVisibility.putIfAbsent(g, true);
        }
        // 返回对用户可见的
        List<String> result = new ArrayList<>();
        for (String g : all) {
            if (genreVisibility.getOrDefault(g, true)) {
                result.add(g);
            }
        }
        return result;
    }

    /** 管理员获取全部分类 + 可见性 */
    public Map<String, Object> getGenresForAdmin() {
        // 先刷新一遍，确保 getAll 是最新的
        List<String> raw = cppClient.getGenres();
        List<String> all = normalizeGenres(raw);
        for (String g : all) {
            genreVisibility.putIfAbsent(g, true);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (String g : all) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", g);
            item.put("visible", genreVisibility.getOrDefault(g, true));
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", all.size());
        return result;
    }

    /** 管理员设置体裁可见性 */
    public void setGenreVisibility(String genre, boolean visible) {
        genreVisibility.put(genre, visible);
    }

    @Cacheable(value = "video:types", unless = "#result.isEmpty()")
    public List<String> getTypes() {
        List<String> raw = cppClient.getTypes();
        // 只保留标准类型，按固定顺序输出
        List<String> result = new ArrayList<>();
        for (String ct : CANONICAL_TYPES) {
            if (raw.contains(ct)) result.add(ct);
        }
        return result;
    }

    /**
     * 对原始 genre 列表去重 + 规范化 + 仅保留大类
     * <p>
     * 例如：动作片→动作, 喜剧片→喜剧, 纪录→纪录片, 动作-冒险→[动作,冒险]
     * 不在映射表的低频体裁被丢弃（腾讯视频风格）
     */
    private List<String> normalizeGenres(List<String> raw) {
        if (raw == null || raw.isEmpty()) return raw != null ? raw : List.of();

        // 1. 规范化：处理直接映射 + 复合体裁拆分
        Set<String> canonical = new LinkedHashSet<>();
        for (String g : raw) {
            // 先试直接映射
            String norm = GENRE_NORMALIZE.get(g);
            if (norm != null) {
                canonical.add(norm);
                continue;
            }
            // 复合体裁拆分，如 "动作-冒险"、"动作·冒险"、"动作/冒险"
            for (String sep : List.of("-", "·", "·", "/", "、", " ")) {
                if (g.contains(sep)) {
                    for (String part : g.split(sep)) {
                        String p = part.trim();
                        if (!p.isEmpty()) {
                            String n = GENRE_NORMALIZE.get(p);
                            if (n != null) canonical.add(n);
                        }
                    }
                    break; // 只按第一个匹配的分隔符拆分
                }
            }
        }

        // 2. 按常见度排序：热门在前面
        List<String> order = List.of(
            "动作", "喜剧", "爱情", "科幻", "悬疑", "恐怖", "剧情", "犯罪",
            "战争", "古装", "奇幻", "冒险", "动画", "纪录片", "短片", "历史",
            "音乐", "经典", "军事", "武侠", "惊悚"
        );
        List<String> result = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String o : order) {
            if (canonical.contains(o) && added.add(o)) result.add(o);
        }
        // 补上不在 order 中的剩余项（防御性）
        for (String c : canonical) {
            if (added.add(c)) result.add(c);
        }
        return result;
    }

    // ══════════════════════════════════════════════
    // 搜索
    // ══════════════════════════════════════════════

    public List<VideoItemVO> search(String keyword) {
        List<CppMovieDTO> movies = cppClient.search(keyword);
        if (movies == null || movies.isEmpty()) {
            return Collections.emptyList();
        }

        // 准备过滤环境
        Map<String, Set<String>> hidden = getHiddenCategories();
        Set<String> hiddenTypes = hidden.get("types");
        Set<String> hiddenGenres = hidden.get("genres");

        List<CppMovieDTO> filtered = movies.stream()
                .peek(this::refineType)
                .filter(m -> m.getId() > 0 && m.getTitle() != null && !m.getTitle().isBlank())
                .filter(m -> m.getSource() == null || !BAD_SOURCES.contains(m.getSource()))
                .filter(this::isDataAuthentic)
                .filter(m -> isCategoryVisible(m, hiddenTypes, hiddenGenres))
                .collect(Collectors.toList());

        // 批量查询 video_meta
        List<Integer> movieIds = filtered.stream()
                .map(CppMovieDTO::getId)
                .collect(Collectors.toList());
        Map<Integer, VideoMeta> metaMap = batchQueryLocalMeta(movieIds);

        return filtered.stream()
                .map(m -> toItemVO(m, metaMap.get(m.getId())))
                .collect(Collectors.toList());
    }

    // ══════════════════════════════════════════════
    // Java 第3层类型判别 — 基于完整数据重新推导
    // ══════════════════════════════════════════════

    /**
     * 第3层类型判别：Java 层基于完整数据独立推导一级分类。
     * <p>
     * 优先级:
     *   1. rawType（CMS源原始type_name）→ 最准确
     *   2. playUrls 集数 → detail 才有
     *   3. remark → 列表页和详情页都有
     *   4. title → 兜底
     * <p>
     * 只对当前 type="电影" 时触发修正。已有的正确分类不变。
     */
    private void refineType(CppMovieDTO movie) {
        if (movie == null) return;
        String type = movie.getType();
        String rawType = movie.getRawType() != null ? movie.getRawType() : "";
        String genre = movie.getGenre() != null ? movie.getGenre() : "";
        String remark = movie.getRemark() != null ? movie.getRemark() : "";
        String title = movie.getTitle() != null ? movie.getTitle() : "";

        // ── 已经有了标准分类就不再重算（信任 Go 层） ──
        if (type != null && !type.isEmpty() && !type.equals("其他") && !type.equals("电影")) {
            return;
        }
        // 仅当 type 为 null, "其他" 或 "电影" 时，尝试根据更细致的 rawType/genre 修正
        // 特别是 "电影" 分类，很多时候 CMS 会把动漫或电视剧误报为电影，所以需要进一步检查

        // ── 第0优先: 短剧判断 (genre 或 rawType 明确是短剧) ──
        // 短剧特征：genre 包含"短剧"，或 rawType 是短剧
        if (genre.contains("短剧") || rawType.equals("短剧") || rawType.equals("微短剧")) {
            log.debug("refineType[shortDrama]: {} → 短剧 (genre={}, rawType={})", title, genre, rawType);
            movie.setType("短剧");
            return;
        }
        // 标题特征：明确包含"短剧"字样
        if (title.contains("短剧") && (title.contains("穿越") || title.contains("重生") 
            || title.contains("总裁") || title.contains("甜宠") || title.contains("赘婿"))) {
            log.debug("refineType[shortDramaTitle]: {} → 短剧", title);
            movie.setType("短剧");
            return;
        }

        // ── 第1优先: rawType (CMS 源原始 type_name) ──
        if (!rawType.isEmpty()) {
            String derived = deriveByRawType(rawType);
            if (derived != null) {
                log.debug("refineType[rawType]: {} rawType={} → {}", title, rawType, derived);
                movie.setType(derived);
                return;
            }
        }

        // ── 第2优先: playUrl 信息 (仅 detail 有) ──
        // 注意：短剧通常也是多集，但已在上面判断，这里只处理非短剧
        if (movie.getPlays() != null) {
            int total = 0;
            boolean hasEpisodeName = false;
            for (CppMovieDTO.CppPlayGroup g : movie.getPlays()) {
                if (g.getUrls() != null) {
                    total += g.getUrls().size();
                    for (CppMovieDTO.CppPlayUrl u : g.getUrls()) {
                        String ep = u.getEpisode() != null ? u.getEpisode() : "";
                        if (ep.contains("集") || ep.contains("话") || ep.contains("期")) {
                            hasEpisodeName = true;
                        }
                    }
                }
            }
            if (total > 3) {
                log.debug("refineType[playUrls]: {} → 电视剧 ({}集)", title, total);
                movie.setType("电视剧");
                return;
            }
            if (hasEpisodeName) {
                log.debug("refineType[episodeName]: {} → 电视剧", title);
                movie.setType("电视剧");
                return;
            }
        }

        // ── 第3优先: remark ──
        // 电视剧特征: 连载、全集、更新至X集
        if (remark.contains("连载") || remark.contains("全集")
            || (remark.contains("更新至") && (remark.contains("集") || remark.contains("话") || remark.contains("期")))) {
            log.debug("refineType[remark]: {} → 电视剧 (remark={})", title, remark);
            movie.setType("电视剧");
            return;
        }
        // "第X集" 但排除品质词(HD/高清/中字/国语/粤语/英语)
        if (remark.contains("集") && !remark.contains("HD") && !remark.contains("高清")
            && !remark.contains("中字") && !remark.contains("国语")
            && !remark.contains("粤语") && !remark.contains("英语")) {
            log.debug("refineType[remark]: {} → 电视剧 (remark={})", title, remark);
            movie.setType("电视剧");
            return;
        }
        // ── 第4优先: genre ──
        if (!genre.isEmpty()) {
            String derived = deriveByGenre(genre);
            if (derived != null) {
                log.debug("refineType[genre]: {} genre={} → {}", title, genre, derived);
                movie.setType(derived);
                return;
            }
        }

        // 动漫特征: title 含常见动漫词
        if (isAnimeTitle(title)) {
            log.debug("refineType[title]: {} → 动漫", title);
            movie.setType("动漫");
            return;
        }

        // ── 兜底: 没有任何分类匹配的剩余内容 → 电影 ──
        if (type == null || type.isEmpty() || type.equals("其他")) {
            log.debug("refineType[fallback]: {} → 电影 (原type={})", title, type);
            movie.setType("电影");
        }
    }

    /** 根据 genre 字段推导标准一级分类 */
    private String deriveByGenre(String genre) {
        if (genre == null || genre.isEmpty()) return null;
        String g = genre.replaceAll("[,/，、\\s]", "").trim();
        // 短剧: genre 为"短剧"或包含"短剧"
        if (g.contains("短剧") || g.contains("微短剧") || g.contains("短片")) return "短剧";
        // 纪录片
        if (g.contains("纪录片") || g.contains("纪录") || g.contains("纪实")) return "纪录片";
        // 综艺
        if (g.contains("综艺") || g.contains("真人秀") || g.contains("选秀")
            || g.contains("脱口秀") || g.contains("访谈")) return "综艺";
        // 动漫
        if (g.contains("动漫") || g.contains("动画")) return "动漫";
        // 少儿
        if (g.contains("少儿") || g.contains("儿童") || g.contains("亲子")) return "少儿";
        // 体育
        if (g.contains("体育") || g.contains("赛事") || g.contains("NBA")) return "体育";
        // 资讯
        if (g.contains("资讯") || g.contains("新闻")) return "资讯";
        return null;
    }

    /** 根据 CMS 原始 type_name 推导标准一级分类 */
    private String deriveByRawType(String rawType) {
        if (rawType == null || rawType.isEmpty()) return null;
        // ── 精确匹配 ──
        if (rawType.equals("电影") || rawType.equals("电影片") || rawType.equals("电影解说")) return "电影";
        if (rawType.equals("电视剧") || rawType.equals("连续剧")) return "电视剧";
        if (rawType.equals("综艺") || rawType.equals("综艺片")) return "综艺";
        if (rawType.equals("纪录片") || rawType.equals("纪录")) return "纪录片";
        if (rawType.equals("短剧") || rawType.equals("微短剧") || rawType.equals("动态漫") || rawType.equals("短片")) return "短剧";
        if (rawType.equals("少儿") || rawType.equals("儿童")) return "少儿";
        if (rawType.equals("体育") || rawType.equals("体育赛事") || rawType.equals("体育片")) return "体育";
        if (rawType.equals("资讯")) return "资讯";

        // ── 动漫类 ──
        if (rawType.equals("动漫") || rawType.equals("动画") || rawType.equals("动漫片")
            || rawType.equals("国产动漫") || rawType.equals("日韩动漫") || rawType.equals("欧美动漫")
            || rawType.equals("港台动漫") || rawType.equals("动漫电影") || rawType.equals("里番动漫")) {
            return "动漫";
        }

        // ── 后缀推断 ──
        if (rawType.endsWith("片")) return "电影";          // 动作片、喜剧片→电影
        if (rawType.endsWith("剧")) return "电视剧";        // 国产剧、美剧→电视剧
        if (rawType.contains("动漫") || rawType.contains("动画")) return "动漫";
        if (rawType.contains("综艺")) return "综艺";

        return null;
    }

    /** 根据标题推断是否为动漫 */
    private boolean isAnimeTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        String low = title.toLowerCase();
        // 常见动漫标题特征
        String[] animeKws = {"动漫", "动画", "anime", "番剧", "剧场版", "ova", "ona"};
        for (String kw : animeKws) {
            if (low.contains(kw)) return true;
        }
        // 著名动漫标题前缀/特征
        String[] popularAnime = {"名侦探柯南", "鬼灭之刃", "斗罗大陆", "斗破苍穹", "海贼王", "火影忍者", "蜡笔小新", "多啦a梦"};
        for (String kw : popularAnime) {
            if (low.contains(kw)) return true;
        }
        return false;
    }

     // ══════════════════════════════════════════════
     // 内部工具
     // ══════════════════════════════════════════════

    private VideoItemVO toItemVO(CppMovieDTO movie, VideoMeta local) {
        VideoItemVO vo = new VideoItemVO();
        vo.setCmsVideoId(movie.getId());
        vo.setSource(movie.getSource());
        vo.setLocalId(local != null ? local.getId() : null);
        vo.setTitle(movie.getTitle());
        vo.setCoverUrl(movie.getCoverUrl());
        vo.setGenre(movie.getGenre());
        vo.setScore(movie.getScore());
        vo.setType(movie.getType());
        vo.setRemark(movie.getRemark());
        vo.setDescription(movie.getDescription());
        vo.setDirector(movie.getDirector());
        vo.setActors(movie.getActors());
        vo.setYear(movie.getYear() > 0 ? movie.getYear() : null);
        vo.setArea(movie.getArea());
        vo.setPlayCount(local != null ? local.getPlayCount() : 0);
        vo.setLikeCount(local != null ? local.getLikeCount() : 0);
        vo.setCollectCount(local != null ? local.getCollectCount() : 0);
        vo.setCommentCount(local != null ? local.getCommentCount() : 0);
        return vo;
    }

    /**
     * 批量查询 video_meta，避免 N+1 问题
     */
    private Map<Integer, VideoMeta> batchQueryLocalMeta(List<Integer> cmsVideoIds) {
        if (cmsVideoIds == null || cmsVideoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        // 去重
        List<Integer> distinctIds = cmsVideoIds.stream()
                .distinct()
                .collect(Collectors.toList());

        List<VideoMeta> metas = videoMetaMapper.selectList(
                new LambdaQueryWrapper<VideoMeta>()
                        .in(VideoMeta::getCmsVideoId, distinctIds));

        return metas.stream()
                .collect(Collectors.toMap(
                        VideoMeta::getCmsVideoId,
                        m -> m,
                        (existing, replacement) -> existing  // 处理重复 key
                ));
    }

    /**
     * 查询或自动创建 video_meta 记录
     * <p>
     * 用户首次点赞/收藏/播放时，从 C++ 服务获取视频信息并插入本地库。
     *
     * @param cmsVideoId C++ 端的 vodId
     * @return video_meta.id（本地主键），不会返回 null
     */
    public Long getOrCreateMeta(int cmsVideoId) {
        return getOrCreateMeta(cmsVideoId, null);
    }

    /**
     * 获取或创建 video_meta 记录
     * @param cmsVideoId C++ 端的 vodId
     * @param source 数据源标识（可选，传参时可精确匹配正确源的视频信息）
     * @return video_meta.id（本地主键），不会返回 null
     */
    public Long getOrCreateMeta(int cmsVideoId, String source) {
        // 1. 查本地（精确匹配 cmsVideoId + source）
        VideoMeta existing = queryLocalMeta(cmsVideoId, source);
        if (existing != null) return existing.getId();

        // 1.5 如果传了 source 但精确匹配没找到，检查是否有同 cmsVideoId 但不同 source 的旧记录
        // （旧数据可能是跨源匹配导致的错误信息，需要修正）
        if (source != null && !source.isEmpty()) {
            VideoMeta oldRecord = queryLocalMeta(cmsVideoId);
            if (oldRecord != null) {
                // 旧记录存在但 source 不匹配，从 Go 服务获取正确信息并更新
                CppMovieDTO movie = cppClient.getMovieDetail(cmsVideoId, source);
                if (movie != null && movie.getId() > 0) {
                    oldRecord.setSource(movie.getSource() != null ? movie.getSource() : "");
                    oldRecord.setTitle(movie.getTitle() != null ? movie.getTitle() : "");
                    oldRecord.setCoverUrl(movie.getCoverUrl() != null ? movie.getCoverUrl() : "");
                    videoMetaMapper.updateById(oldRecord);
                    log.info("getOrCreateMeta: updated meta source for cmsVideoId={} oldSource='{}' newSource='{}', title='{}'",
                            cmsVideoId, oldRecord.getSource(), source, oldRecord.getTitle());
                    return oldRecord.getId();
                }
                // Go 服务也查不到，返回旧记录
                return oldRecord.getId();
            }
        }

        // 2. 本地无记录 → 从 Go 服务获取视频信息（携带 source 避免跨源匹配）
        CppMovieDTO movie = cppClient.getMovieDetail(cmsVideoId, source);
        if (movie == null || movie.getId() <= 0) {
            // 极端情况：C++ 也查不到，创建一个占位记录
            VideoMeta fallback = new VideoMeta();
            fallback.setCmsVideoId(cmsVideoId);
            fallback.setSource("");
            fallback.setTitle("视频 #" + cmsVideoId);
            fallback.setPlayCount(0);
            fallback.setLikeCount(0);
            fallback.setCollectCount(0);
            fallback.setCommentCount(0);
            videoMetaMapper.insert(fallback);
            log.info("getOrCreateMeta: created fallback meta for cmsVideoId={}, localId={}", cmsVideoId, fallback.getId());
            return fallback.getId();
        }

        // 3. 创建本地记录
        VideoMeta meta = new VideoMeta();
        meta.setCmsVideoId(movie.getId());
        meta.setSource(movie.getSource() != null ? movie.getSource() : "");
        meta.setTitle(movie.getTitle() != null ? movie.getTitle() : "");
        meta.setCoverUrl(movie.getCoverUrl() != null ? movie.getCoverUrl() : "");
        meta.setPlayCount(0);
        meta.setLikeCount(0);
        meta.setCollectCount(0);
        meta.setCommentCount(0);
        videoMetaMapper.insert(meta);
        log.info("getOrCreateMeta: created meta for '{}' (cmsVideoId={}, source={}), localId={}",
                meta.getTitle(), cmsVideoId, meta.getSource(), meta.getId());
        return meta.getId();
    }

    private VideoMeta queryLocalMeta(int cmsVideoId) {
        return queryLocalMeta(cmsVideoId, null);
    }

    private VideoMeta queryLocalMeta(int cmsVideoId, String source) {
        try {
            LambdaQueryWrapper<VideoMeta> wrapper = new LambdaQueryWrapper<VideoMeta>()
                    .eq(VideoMeta::getCmsVideoId, cmsVideoId);
            if (source != null && !source.isEmpty()) {
                wrapper.eq(VideoMeta::getSource, source);
            }
            wrapper.last("LIMIT 1");
            return videoMetaMapper.selectOne(wrapper);
        } catch (Exception e) {
            log.warn("queryLocalMeta failed for {} (source={}): {}", cmsVideoId, source, e.getMessage());
            return null;
        }
    }
}
