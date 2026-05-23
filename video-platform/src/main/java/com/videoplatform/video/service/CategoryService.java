package com.videoplatform.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videoplatform.video.client.CppVideoClient;
import com.videoplatform.video.entity.CategoryVisibility;
import com.videoplatform.video.mapper.CategoryVisibilityMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 分类管理服务
 * <p>
 * 从 Go 获取原始分类数据，Java 重新分类 + 应用 DB 可见性规则。
 * 隐藏/显示操作持久化到 MySQL，重启不丢失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CppVideoClient cppClient;
    private final CategoryVisibilityMapper visibilityMapper;
    private final ObjectMapper objectMapper;

    /** 标准一级分类（固定9个，与Go对齐） */
    private static final List<Map<String, Object>> TOP_CATEGORIES = List.of(
            cat(1, "电影", "movie"),
            cat(2, "电视剧", "tv"),
            cat(3, "短剧", "short"),
            cat(4, "动漫", "anime"),
            cat(5, "综艺", "variety"),
            cat(6, "纪录片", "documentary"),
            cat(7, "少儿", "kids"),
            cat(8, "体育", "sports"),
            cat(9, "资讯", "news")
    );

    private static Map<String, Object> cat(int id, String name, String alias) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("alias", alias);
        return m;
    }

    /** 标准二级分类（每个一级分类下的子分类列表） */
    private static final Map<Integer, List<Map<String, Object>>> SUB_CATEGORIES = new LinkedHashMap<>();
    static {
        SUB_CATEGORIES.put(1, subs(1,
                "动作","喜剧","爱情","科幻","悬疑","惊悚","剧情","战争",
                "灾难","犯罪","奇幻","文艺","动画电影","经典老片","伦理"));
        SUB_CATEGORIES.put(2, subs(2,
                "都市","古装","言情","悬疑","谍战","军旅","武侠","仙侠",
                "年代","家庭","乡村","网剧","港剧","台剧","韩剧","美剧"));
        SUB_CATEGORIES.put(3, subs(3, "甜宠","赘婿","逆袭","穿越","重生"));
        SUB_CATEGORIES.put(4, subs(4,
                "热血","恋爱","奇幻","冒险","校园","古风","治愈","搞笑","机甲"));
        SUB_CATEGORIES.put(5, subs(5,
                "真人秀","选秀","音乐","脱口秀","情感","亲子","户外","美食"));
        SUB_CATEGORIES.put(6, subs(6,
                "人文历史","自然地理","军事","美食","人物","社会","动物","科技"));
        SUB_CATEGORIES.put(7, subs(7, "早教","儿歌","动画片","绘本","益智"));
        SUB_CATEGORIES.put(8, subs(8, "足球","篮球","格斗","综合赛事"));
        SUB_CATEGORIES.put(9, subs(9, "娱乐资讯","影视资讯","社会热点"));
    }

    private static List<Map<String, Object>> subs(int pid, String... names) {
        List<Map<String, Object>> list = new ArrayList<>();
        int baseId = pid * 100;
        for (int i = 0; i < names.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", baseId + i + 1);
            m.put("pid", pid);
            m.put("name", names[i]);
            list.add(m);
        }
        return list;
    }

    /**
     * 获取带影片数的分类树
     * 从 Go 取原始数据 → Java 重分类 → 应用 DB 可见性规则
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getTreeWithCounts(boolean showHidden) {
        // 1. 从 Go 获取原始数据
        String raw = cppClient.fetchRaw("/api/category/tree-with-counts?showHidden=1");
        List<Map<String, Object>> goTree;
        try {
            goTree = objectMapper.readValue(raw, List.class);
        } catch (Exception e) {
            log.warn("getTreeWithCounts: Go fetch failed, fallback to empty", e);
            goTree = List.of();
        }

        // 2. 加载 DB 可见性规则（全部）
        List<CategoryVisibility> visList = visibilityMapper.selectList(null);
        Map<Integer, Boolean> visMap = new HashMap<>();
        for (CategoryVisibility cv : visList) {
            visMap.put(cv.getCategoryId(), cv.getVisible());
        }

        // 3. 构建结果
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> topDef : TOP_CATEGORIES) {
            int tid = (int) topDef.get("id");
            String tName = (String) topDef.get("name");

            // 从Go数据中找到对应一级分类的计数
            long typeCount = 0;
            for (Map<String, Object> goCat : goTree) {
                if (tName.equals(goCat.get("name"))) {
                    typeCount = ((Number) goCat.getOrDefault("count", 0)).longValue();
                    break;
                }
            }

            // 检查可见性
            boolean typeVisible = visMap.getOrDefault(tid, true);
            if (!showHidden && !typeVisible) {
                continue;
            }

            // 构建子分类
            List<Map<String, Object>> subsList = SUB_CATEGORIES.getOrDefault(tid, List.of());
            List<Map<String, Object>> enrichedSubs = new ArrayList<>();
            long subTotalCount = 0;

            for (Map<String, Object> subDef : subsList) {
                int sid = (int) subDef.get("id");
                String sName = (String) subDef.get("name");

                long subCount = 0;
                for (Map<String, Object> goCat : goTree) {
                    if (tName.equals(goCat.get("name"))) {
                        Object goSubs = goCat.get("subs");
                        if (goSubs instanceof List) {
                            for (Map<String, Object> gs : (List<Map<String, Object>>) goSubs) {
                                if (sName.equals(gs.get("name"))) {
                                    subCount = ((Number) gs.getOrDefault("count", 0)).longValue();
                                    break;
                                }
                            }
                        }
                        break;
                    }
                }

                boolean subVisible = visMap.getOrDefault(sid, true);
                if (!showHidden && !subVisible) {
                    continue;
                }

                subTotalCount += subCount;

                Map<String, Object> subItem = new LinkedHashMap<>();
                subItem.put("id", sid);
                subItem.put("name", sName);
                subItem.put("count", subCount);
                subItem.put("is_show", subVisible);
                enrichedSubs.add(subItem);
            }

            Map<String, Object> catItem = new LinkedHashMap<>();
            catItem.put("id", tid);
            catItem.put("name", tName);
            catItem.put("alias", topDef.get("alias"));
            catItem.put("count", Math.max(typeCount, subTotalCount));
            catItem.put("is_show", typeVisible);
            if (!enrichedSubs.isEmpty()) {
                catItem.put("subs", enrichedSubs);
            }
            result.add(catItem);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", result);
        resp.put("total", result.size());
        return resp;
    }

    /**
     * 切换分类显示/隐藏，持久化到 DB
     */
    public Map<String, Object> toggle(Integer categoryId) {
        CategoryVisibility cv = visibilityMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CategoryVisibility>()
                        .eq(CategoryVisibility::getCategoryId, categoryId));

        if (cv == null) {
            // 数据库无记录，表示该分类从未被设置过，默认是"显示"状态
            // 点击 toggle 应该切换为"隐藏"
            String catType = (categoryId >= 100) ? "genre" : "type";
            String catName = findCategoryName(categoryId);

            cv = new CategoryVisibility();
            cv.setCategoryId(categoryId);
            cv.setCategoryType(catType);
            cv.setCategoryName(catName);
            cv.setVisible(false);  // 默认显示 → 切换为隐藏
            visibilityMapper.insert(cv);
        } else {
            // 有记录，直接取反
            cv.setVisible(!cv.getVisible());
            visibilityMapper.updateById(cv);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", categoryId);
        result.put("is_show", cv.getVisible());
        return result;
    }

    private String findCategoryName(int id) {
        if (id < 100) {
            for (Map<String, Object> c : TOP_CATEGORIES) {
                if ((int) c.get("id") == id) return (String) c.get("name");
            }
        } else {
            int pid = id / 100;
            List<Map<String, Object>> subs = SUB_CATEGORIES.get(pid);
            if (subs != null) {
                for (Map<String, Object> s : subs) {
                    if ((int) s.get("id") == id) return (String) s.get("name");
                }
            }
        }
        return "";
    }

    // ══════════════════════════════════════════════
    // 纠正的分类计数缓存 — 从Go拉数据+refineType后重算
    // ══════════════════════════════════════════════

    private final AtomicBoolean recountInProgress = new AtomicBoolean(false);
    // key: typeName, value: 该类型总量
    private volatile Map<String, Long> correctedTypeCounts = Map.of();
    // key: typeName, value: (subName → count)
    private volatile Map<String, Map<String, Long>> correctedSubCounts = Map.of();

    /**
     * 触发纠正计数重算（从Go拉全部数据 → refineType → 统计）
     */
    public void triggerRecount() {
        if (!recountInProgress.compareAndSet(false, true)) {
            log.info("recount already in progress, skip");
            return;
        }
        new Thread(() -> {
            try {
                log.info("====== 开始纠正分类计数重算 ======");
                long t0 = System.currentTimeMillis();
                Map<String, Long> typeTotals = new ConcurrentHashMap<>();
                Map<String, Map<String, Long>> subTotals = new ConcurrentHashMap<>();

                int totalFetched = 0;
                int page = 1;
                while (true) {
                    var pd = cppClient.getMoviePageWithTotal(page, 500, null, null, 0, null, null);
                    if (pd.items == null || pd.items.isEmpty()) break;
                    for (var m : pd.items) {
                        refineType(m);
                        String t = m.getType() != null && !m.getType().isEmpty() ? m.getType() : "(空)";
                        typeTotals.merge(t, 1L, Long::sum);

                        String genre = m.getGenre();
                        if (genre != null && !genre.isBlank()) {
                            Map<String, Long> subMap = subTotals.computeIfAbsent(t, k -> new ConcurrentHashMap<>());
                            String[] parts = genre.split("[,/，、]");
                            for (String p : parts) {
                                p = p.trim();
                                if (!p.isEmpty()) subMap.merge(p, 1L, Long::sum);
                            }
                        }
                    }
                    totalFetched += pd.items.size();
                    if (pd.items.size() < 500) break;
                    page++;
                    if (page > 200) break;
                }

                correctedTypeCounts = new HashMap<>(typeTotals);
                correctedSubCounts = new HashMap<>(subTotals);
                long elapsed = System.currentTimeMillis() - t0;
                log.info("====== 纠正计数重算完成: {}条, 耗时{}ms ======", totalFetched, elapsed);
            } catch (Exception e) {
                log.warn("recount failed: {}", e.getMessage(), e);
            } finally {
                recountInProgress.set(false);
            }
        }).start();
    }

    /**
     * 获取纠正后的分类树（替换Go的错误计数）
     */
    public Map<String, Object> getCorrectedTree(boolean showHidden) {
        // 首次访问懒触发重算
        if (correctedTypeCounts.isEmpty()) {
            triggerRecount();
        }

        Map<String, Object> result = getTreeWithCounts(showHidden);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        if (items == null) return result;

        for (Map<String, Object> cat : items) {
            String name = (String) cat.get("name");
            Long correctedTotal = correctedTypeCounts.get(name);
            if (correctedTotal != null) {
                cat.put("count", correctedTotal);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subs = (List<Map<String, Object>>) cat.get("subs");
            if (subs != null) {
                Map<String, Long> subMap = correctedSubCounts.getOrDefault(name, Map.of());
                for (Map<String, Object> sub : subs) {
                    String sName = (String) sub.get("name");
                    Long c = subMap.get(sName);
                    sub.put("count", c != null ? c : 0L);
                }
            }
        }
        return result;
    }

    /**
     * Java 第3层类型判别 — 基于rawType/remark/title重推type
     */
    private void refineType(com.videoplatform.video.dto.CppMovieDTO movie) {
        if (movie == null) return;
        String type = movie.getType();
        String rawType = movie.getRawType() != null ? movie.getRawType() : "";

        if (type != null && (type.equals("电视剧") || type.equals("动漫")
            || type.equals("综艺") || type.equals("纪录片") || type.equals("短剧"))) {
            return;
        }

        String remark = movie.getRemark() != null ? movie.getRemark() : "";
        String title = movie.getTitle() != null ? movie.getTitle() : "";

        // 第1优先: rawType
        if (!rawType.isEmpty()) {
            String derived = deriveByRawType(rawType);
            if (derived != null) {
                log.debug("refineType[rawType]: {} rawType={} → {}", title, rawType, derived);
                movie.setType(derived);
                return;
            }
        }

        // 第2优先: playUrl (仅detail有)
        if (movie.getPlays() != null) {
            int total = 0;
            boolean hasEpisodeName = false;
            for (var g : movie.getPlays()) {
                if (g.getUrls() != null) {
                    total += g.getUrls().size();
                    for (var u : g.getUrls()) {
                        String ep = u.getEpisode() != null ? u.getEpisode() : "";
                        if (ep.contains("集") || ep.contains("话") || ep.contains("期")) {
                            hasEpisodeName = true;
                        }
                    }
                }
            }
            if (total > 3) { movie.setType("电视剧"); return; }
            if (hasEpisodeName) { movie.setType("电视剧"); return; }
        }

        // 第3优先: remark
        if (remark.contains("连载") || remark.contains("全集")
            || (remark.contains("更新至") && (remark.contains("集") || remark.contains("话") || remark.contains("期")))) {
            movie.setType("电视剧"); return;
        }
        if (remark.contains("集") && !remark.contains("HD") && !remark.contains("高清")
            && !remark.contains("中字") && !remark.contains("国语")
            && !remark.contains("粤语") && !remark.contains("英语")) {
            movie.setType("电视剧"); return;
        }

        // title 动漫特征
        if (isAnimeTitle(title)) { movie.setType("动漫"); return; }
    }

    private String deriveByRawType(String rawType) {
        if (rawType == null || rawType.isEmpty()) return null;
        if (rawType.equals("电影") || rawType.equals("电影片") || rawType.equals("电影解说")) return "电影";
        if (rawType.equals("电视剧") || rawType.equals("连续剧")) return "电视剧";
        if (rawType.equals("综艺") || rawType.equals("综艺片")) return "综艺";
        if (rawType.equals("纪录片") || rawType.equals("纪录")) return "纪录片";
        if (rawType.equals("短剧") || rawType.equals("微短剧") || rawType.equals("动态漫") || rawType.equals("短片")) return "短剧";
        if (rawType.equals("少儿") || rawType.equals("儿童")) return "少儿";
        if (rawType.equals("体育") || rawType.equals("体育赛事") || rawType.equals("体育片")) return "体育";
        if (rawType.equals("资讯")) return "资讯";

        if (rawType.equals("动漫") || rawType.equals("动画") || rawType.equals("动漫片")
            || rawType.equals("国产动漫") || rawType.equals("日韩动漫") || rawType.equals("欧美动漫")
            || rawType.equals("港台动漫") || rawType.equals("动漫电影") || rawType.equals("里番动漫")) {
            return "动漫";
        }

        if (rawType.endsWith("片")) return "电影";
        if (rawType.endsWith("剧")) return "电视剧";
        if (rawType.contains("动漫") || rawType.contains("动画")) return "动漫";
        if (rawType.contains("综艺")) return "综艺";

        return null;
    }

    private boolean isAnimeTitle(String title) {
        if (title == null || title.isEmpty()) return false;
        String[] animeKws = {"动漫", "动画", "anime", "番剧", "年番", "篇"};
        for (String kw : animeKws) {
            if (title.contains(kw)) return true;
        }
        if (title.contains("季")) return true;
        if (title.contains("之")) return true;
        return false;
    }
}
