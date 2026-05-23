-- ═══════════════════════════════════════════════
-- Video Platform — 自动建表（Spring Boot 启动时执行）
-- ═══════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS `user` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `username`    VARCHAR(64)  NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `nickname`    VARCHAR(64)  NOT NULL DEFAULT '',
    `avatar_url`  VARCHAR(512) NOT NULL DEFAULT '',
    `role`        VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT 'user=普通用户, admin=管理员',
    `status`      TINYINT      NOT NULL DEFAULT 1,
    `banned`      TINYINT      NOT NULL DEFAULT 0 COMMENT '1=封禁(无法登录)',
    `muted_until` DATETIME     NULL COMMENT '禁言到期时间, NULL=未禁言',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `follow` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `follower_id` BIGINT       NOT NULL,
    `followee_id` BIGINT       NOT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee` (`followee_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `video_meta` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `cms_video_id`  INT          NOT NULL DEFAULT 0,
    `title`         VARCHAR(255) NOT NULL DEFAULT '',
    `cover_url`     VARCHAR(512) NOT NULL DEFAULT '',
    `duration`      INT          NOT NULL DEFAULT 0,
    `category_id`   BIGINT       NOT NULL DEFAULT 0,
    `tags`          VARCHAR(512) NOT NULL DEFAULT '',
    `play_count`    INT          NOT NULL DEFAULT 0,
    `like_count`    INT          NOT NULL DEFAULT 0,
    `collect_count` INT          NOT NULL DEFAULT 0,
    `comment_count` INT          NOT NULL DEFAULT 0,
    `status`        TINYINT      NOT NULL DEFAULT 1,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_cms_video_id` (`cms_video_id`),
    KEY `idx_category_id` (`category_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `like_record` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `video_id`    BIGINT       NOT NULL,
    `status`      TINYINT      NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_video_id` (`video_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `favorite` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `video_id`    BIGINT       NOT NULL,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_video_id` (`video_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `play_history` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT       NOT NULL,
    `video_id`    BIGINT       NOT NULL,
    `progress`    INT          NOT NULL DEFAULT 0,
    `duration`    INT          NOT NULL DEFAULT 0,
    `play_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_user_play_time` (`user_id`, `play_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `comment` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `video_id`     BIGINT       NOT NULL,
    `user_id`      BIGINT       NOT NULL,
    `parent_id`    BIGINT       NOT NULL DEFAULT 0,
    `reply_to_uid` BIGINT       NOT NULL DEFAULT 0,
    `content`      VARCHAR(2000) NOT NULL DEFAULT '',
    `like_count`   INT          NOT NULL DEFAULT 0,
    `status`       TINYINT      NOT NULL DEFAULT 1,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_video_id` (`video_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_parent_id` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ═══════════════════════════════════════════════
-- 分类可见性控制（Java 接管后持久化到 DB）
-- ═══════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS `category_visibility` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `category_id`   INT          NOT NULL DEFAULT 0,
    `category_type` VARCHAR(16)  NOT NULL DEFAULT 'genre' COMMENT 'type=一级分类 / genre=二级子分类',
    `category_name` VARCHAR(64)  NOT NULL DEFAULT '',
    `visible`       TINYINT      NOT NULL DEFAULT 1,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category` (`category_id`),
    KEY `idx_category_type` (`category_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
