-- ═══════════════════════════════════════════════
-- Video Platform — 基础表结构
-- 引擎: InnoDB  字符集: utf8mb4  排序: utf8mb4_unicode_ci
-- ═══════════════════════════════════════════════

-- ─── 用户表 ───
CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username`    VARCHAR(64)  NOT NULL                COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL              COMMENT '密码哈希',
    `nickname`    VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '昵称',
    `avatar_url`  VARCHAR(512) NOT NULL DEFAULT ''     COMMENT '头像URL',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT '角色: user=普通用户, admin=管理员',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态: 0=禁用, 1=正常',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';


-- ─── 关注表 ───
CREATE TABLE IF NOT EXISTS `follow` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `follower_id` BIGINT       NOT NULL                COMMENT '关注者ID',
    `followee_id` BIGINT       NOT NULL                COMMENT '被关注者ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee` (`followee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注表';


-- ─── 视频元数据表 ───
CREATE TABLE IF NOT EXISTS `video_meta` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `cms_video_id`  INT          NOT NULL DEFAULT 0      COMMENT 'CMS原始视频ID',
    `source`        VARCHAR(64)  NOT NULL DEFAULT ''     COMMENT '数据源标识，如 BaiDu、YingHua',
    `title`         VARCHAR(255) NOT NULL DEFAULT ''     COMMENT '标题',
    `cover_url`     VARCHAR(512) NOT NULL DEFAULT ''     COMMENT '封面URL',
    `duration`      INT          NOT NULL DEFAULT 0      COMMENT '时长(秒)',
    `category_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '分类ID',
    `tags`          VARCHAR(512) NOT NULL DEFAULT ''     COMMENT '标签(逗号分隔)',
    `play_count`    INT          NOT NULL DEFAULT 0      COMMENT '播放次数',
    `like_count`    INT          NOT NULL DEFAULT 0      COMMENT '点赞数',
    `collect_count` INT          NOT NULL DEFAULT 0      COMMENT '收藏数',
    `comment_count` INT          NOT NULL DEFAULT 0      COMMENT '评论数',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态: 0=下架, 1=上架',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_cms_video_id_source` (`cms_video_id`, `source`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频元数据表';


-- ─── 点赞记录表 ───
CREATE TABLE IF NOT EXISTS `like_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `video_id`    BIGINT       NOT NULL                COMMENT '视频ID',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态: 0=取消, 1=点赞',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_video_id` (`video_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞记录表';


-- ─── 收藏表 ───
CREATE TABLE IF NOT EXISTS `favorite` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `video_id`    BIGINT       NOT NULL                COMMENT '视频ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_video_id` (`video_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='收藏表';


-- ─── 播放历史表 ───
CREATE TABLE IF NOT EXISTS `play_history` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`     BIGINT       NOT NULL                COMMENT '用户ID',
    `video_id`    BIGINT       NOT NULL                COMMENT '视频ID',
    `progress`    INT          NOT NULL DEFAULT 0      COMMENT '播放进度(秒)',
    `duration`    INT          NOT NULL DEFAULT 0      COMMENT '视频总时长(秒)',
    `play_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '播放时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_user_play_time` (`user_id`, `play_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='播放历史表';


-- ─── 评论表 ───
CREATE TABLE IF NOT EXISTS `comment` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `video_id`     BIGINT       NOT NULL                COMMENT '视频ID',
    `user_id`      BIGINT       NOT NULL                COMMENT '评论用户ID',
    `parent_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '父评论ID(0=顶级)',
    `reply_to_uid` BIGINT       NOT NULL DEFAULT 0      COMMENT '回复目标用户ID(0=非回复)',
    `content`      VARCHAR(2000) NOT NULL DEFAULT ''    COMMENT '评论内容',
    `like_count`   INT          NOT NULL DEFAULT 0      COMMENT '点赞数',
    `status`       TINYINT      NOT NULL DEFAULT 1      COMMENT '状态: 0=隐藏, 1=显示',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_video_id` (`video_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_parent_id` (`parent_id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';
