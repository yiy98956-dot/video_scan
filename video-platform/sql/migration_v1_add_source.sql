-- ═══════════════════════════════════════════════
-- 迁移 v1 — video_meta 增加 source 列和唯一索引
-- ═══════════════════════════════════════════════

-- 1. 加列（已存在则忽略, no-op）
ALTER TABLE video_meta
    ADD COLUMN `source` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '数据源标识，如 BaiDu、YingHua' AFTER `cms_video_id`;

-- 2. 加复合唯一索引（替换旧的普通索引）
-- 先删旧索引，再加新索引
ALTER TABLE video_meta
    DROP INDEX `idx_cms_video_id`,
    ADD UNIQUE KEY `uk_cms_video_id_source` (`cms_video_id`, `source`);
