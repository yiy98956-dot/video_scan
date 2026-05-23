package com.videoplatform.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据库迁移 + 初始化 — 启动时自动执行
 * <p>
 * 幂等设计：每条 SQL 独立执行并捕获异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    @PostConstruct
    public void migrate() {
        log.info("[DB Migration] 开始执行数据库迁移...");

        migrateV1();
        migrateV2();
        migrateV3();
        migrateV4();

        log.info("[DB Migration] 完成");
    }

    private void migrateV1() {
        // video_meta 增加 source 列
        try {
            jdbcTemplate.execute(
                "ALTER TABLE video_meta ADD COLUMN `source` VARCHAR(64) NOT NULL DEFAULT '' " +
                "COMMENT '数据源标识，如 BaiDu、YingHua' AFTER `cms_video_id`"
            );
            log.info("[DB Migration] v1: 成功添加 video_meta.source 列");
        } catch (Exception e) {
            log.info("[DB Migration] v1: video_meta.source 列已存在，跳过 ({})", e.getMessage());
        }

        // 删旧索引
        try {
            jdbcTemplate.execute("ALTER TABLE video_meta DROP INDEX `idx_cms_video_id`");
            log.info("[DB Migration] v1: 成功删除旧索引 idx_cms_video_id");
        } catch (Exception e) {
            log.info("[DB Migration] v1: 旧索引 idx_cms_video_id 不存在，跳过");
        }

        // 加复合唯一索引
        try {
            jdbcTemplate.execute(
                "ALTER TABLE video_meta ADD UNIQUE KEY `uk_cms_video_id_source` (`cms_video_id`, `source`)"
            );
            log.info("[DB Migration] v1: 成功添加唯一索引 uk_cms_video_id_source");
        } catch (Exception e) {
            log.info("[DB Migration] v1: 唯一索引 uk_cms_video_id_source 已存在，跳过 ({})", e.getMessage());
        }
    }

    private void migrateV2() {
        // 1. user 表加 role 列
        try {
            jdbcTemplate.execute(
                "ALTER TABLE `user` ADD COLUMN `role` VARCHAR(16) NOT NULL DEFAULT 'user' COMMENT '角色: user=普通用户, admin=管理员' AFTER `avatar_url`"
            );
            log.info("[DB Migration] v2: 成功添加 user.role 列");
        } catch (Exception e) {
            log.info("[DB Migration] v2: user.role 列已存在，跳过 ({})", e.getMessage());
        }

        // 2. 初始化默认管理员（如果不存在）
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE `username` = 'admin'", Integer.class);
            if (count != null && count == 0) {
                String hash = passwordEncoder.encode("admin123");
                jdbcTemplate.update(
                    "INSERT INTO `user` (`username`, `password_hash`, `nickname`, `role`, `status`) VALUES (?, ?, ?, 'admin', 1)",
                    "admin", hash, "管理员"
                );
                log.info("[DB Migration] v2: 默认管理员已创建 (admin / admin123)");
            } else {
                log.info("[DB Migration] v2: 管理员用户已存在，跳过");
            }
        } catch (Exception e) {
            log.warn("[DB Migration] v2: 创建管理员失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    // v3 — 分类系统：video_category 表 + 固定一二级分类数据
    // ════════════════════════════════════════════════════════════
    private void migrateV3() {
        // 1. 建表
        try {
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS `video_category` (" +
                "  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '分类ID（一级固定）'," +
                "  `pid` int(11) NOT NULL DEFAULT '0' COMMENT '父级ID（0=一级）'," +
                "  `name` varchar(50) NOT NULL DEFAULT '' COMMENT '分类名称'," +
                "  `alias` varchar(50) NOT NULL DEFAULT '' COMMENT '英文别名'," +
                "  `is_show` tinyint(1) NOT NULL DEFAULT '1' COMMENT '显示状态（1显示 0隐藏）'," +
                "  `sort` int(11) NOT NULL DEFAULT '0' COMMENT '排序值'," +
                "  PRIMARY KEY (`id`)," +
                "  KEY `idx_pid` (`pid`)," +
                "  KEY `idx_show_sort` (`is_show`,`sort`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='影视分类表（一二级统一存储）'"
            );
            log.info("[DB Migration] v3: video_category 表已就绪");
        } catch (Exception e) {
            log.warn("[DB Migration] v3: 建表失败: {}", e.getMessage());
        }

        // 2. 插入一级主分类（INSERT IGNORE 幂等）
        try {
            Object[][] primaries = {
                {1, "电影",   "movie",        1, 1},
                {2, "电视剧", "tv",           1, 2},
                {3, "短剧",   "short",        1, 3},
                {4, "动漫",   "anime",        1, 4},
                {5, "综艺",   "variety",      1, 5},
                {6, "纪录片", "documentary",  1, 6},
                {7, "少儿",   "kids",         1, 7},
                {8, "体育",   "sports",       1, 8},
                {9, "资讯",   "news",         1, 9},
            };
            for (Object[] row : primaries) {
                jdbcTemplate.update(
                    "INSERT IGNORE INTO `video_category` (`id`,`pid`,`name`,`alias`,`is_show`,`sort`) VALUES (?,0,?,?,?,?)",
                    row[0], row[1], row[2], row[3], row[4]
                );
            }
            log.info("[DB Migration] v3: 一级分类数据写入完成 (9条)");
        } catch (Exception e) {
            log.warn("[DB Migration] v3: 一级分类写入失败: {}", e.getMessage());
        }

        // 3. 插入二级子分类
        try {
            // 格式: {pid, name, alias, sort}
            Object[][] subs = {
                // ── 1 电影 ──
                {1,"动作","action",1},{1,"喜剧","comedy",2},{1,"爱情","romance",3},
                {1,"科幻","sci-fi",4},{1,"悬疑","suspense",5},{1,"惊悚","thriller",6},
                {1,"剧情","drama",7},{1,"战争","war",8},{1,"灾难","disaster",9},
                {1,"犯罪","crime",10},{1,"奇幻","fantasy",11},{1,"文艺","literary",12},
                {1,"动画电影","anime_movie",13},{1,"经典老片","classic",14},
                // ── 2 电视剧 ──
                {2,"都市","urban",1},{2,"古装","costume",2},{2,"言情","romance_tv",3},
                {2,"悬疑","suspense_tv",4},{2,"谍战","spy",5},{2,"军旅","military",6},
                {2,"武侠","wuxia",7},{2,"仙侠","xianxia",8},{2,"年代","period",9},
                {2,"家庭","family",10},{2,"乡村","rural",11},{2,"网剧","web_series",12},
                {2,"港剧","hongkong_drama",13},{2,"台剧","taiwan_drama",14},
                {2,"韩剧","korean_drama",15},{2,"美剧","american_drama",16},
                // ── 3 短剧 ──
                {3,"甜宠","sweet_love",1},{3,"赘婿","soninlaw",2},{3,"逆袭","comeback",3},
                {3,"玄幻","fantasy_short",4},{3,"古风","ancient_short",5},{3,"复仇","revenge",6},
                {3,"豪门","rich_family",7},{3,"校园","campus",8},{3,"穿越","time_travel",9},
                {3,"都市逆袭","urban_reverse",10},
                // ── 4 动漫 ──
                {4,"国漫","domestic_anime",1},{4,"日漫","japanese_anime",2},
                {4,"欧美动漫","western_anime",3},{4,"热血","hot_blood",4},
                {4,"恋爱","love_anime",5},{4,"奇幻","fantasy_anime",6},
                {4,"冒险","adventure",7},{4,"校园","campus_anime",8},
                {4,"古风","ancient_anime",9},{4,"治愈","healing",10},
                {4,"悬疑","suspense_anime",11},{4,"少儿动漫","kids_anime",12},
                // ── 5 综艺 ──
                {5,"真人秀","reality_show",1},{5,"选秀","talent_show",2},
                {5,"音乐","music_show",3},{5,"脱口秀","talk_show",4},
                {5,"情感","emotion",5},{5,"亲子","parenting",6},
                {5,"户外","outdoor",7},{5,"美食","food",8},
                {5,"职场","workplace",9},{5,"搞笑综艺","funny_show",10},
                // ── 6 纪录片 ──
                {6,"人文历史","history_culture",1},{6,"自然地理","nature_geo",2},
                {6,"军事","military_doc",3},{6,"美食","food_doc",4},
                {6,"人物","biography",5},{6,"社会","society",6},
                {6,"动物","animal",7},{6,"科技","technology",8},
                // ── 7 少儿 ──
                {7,"早教","early_edu",1},{7,"儿歌","nursery_rhyme",2},
                {7,"动画片","cartoon",3},{7,"绘本","picture_book",4},
                {7,"益智","puzzle",5},{7,"亲子节目","parenting_kids",6},
                // ── 8 体育 ──
                {8,"足球","football",1},{8,"篮球","basketball",2},
                {8,"格斗","fighting",3},{8,"电竞","esports",4},
                {8,"综合赛事","sports_other",5},
                // ── 9 资讯 ──
                {9,"娱乐资讯","entertainment",1},{9,"影视资讯","film_news",2},
                {9,"社会热点","hot_news",3},
            };
            int subCount = 0;
            for (Object[] row : subs) {
                int n = jdbcTemplate.update(
                    "INSERT IGNORE INTO `video_category` (`pid`,`name`,`alias`,`is_show`,`sort`) VALUES (?,?,?,1,?)",
                    row[0], row[1], row[2], row[3]
                );
                if (n > 0) subCount++;
            }
            log.info("[DB Migration] v3: 二级子分类数据写入完成 (新增{}条)", subCount);
        } catch (Exception e) {
            log.warn("[DB Migration] v3: 二级分类写入失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    // v4 — 用户管理：封禁 + 禁言字段
    // ════════════════════════════════════════════════════════════
    private void migrateV4() {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE `user` ADD COLUMN `banned` TINYINT NOT NULL DEFAULT 0 COMMENT '1=封禁(无法登录)' AFTER `status`"
            );
            log.info("[DB Migration] v4: 成功添加 user.banned 列");
        } catch (Exception e) {
            log.info("[DB Migration] v4: user.banned 列已存在，跳过 ({})", e.getMessage());
        }

        try {
            jdbcTemplate.execute(
                "ALTER TABLE `user` ADD COLUMN `muted_until` DATETIME NULL COMMENT '禁言到期时间, NULL=未禁言' AFTER `banned`"
            );
            log.info("[DB Migration] v4: 成功添加 user.muted_until 列");
        } catch (Exception e) {
            log.info("[DB Migration] v4: user.muted_until 列已存在，跳过 ({})", e.getMessage());
        }
    }
}
