-- 核心记忆表（对话内学习 + 跨会话记忆）
-- 在 walissh 库中执行

CREATE TABLE IF NOT EXISTS `core_memory` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` varchar(64) NOT NULL DEFAULT 'default' COMMENT '用户ID',
  `scope` varchar(20) NOT NULL COMMENT '作用域: user/session',
  `category` varchar(30) NOT NULL COMMENT '分类: Rule/Preference/Decision/Correction/Fact',
  `title` varchar(200) NOT NULL COMMENT '记忆标题',
  `keywords` varchar(500) DEFAULT NULL COMMENT '关键词(逗号分隔)',
  `content` text COMMENT '记忆正文',
  `priority` int NOT NULL DEFAULT 3 COMMENT '优先级1-5(越高越重要)',
  `source_session_id` varchar(64) DEFAULT NULL COMMENT '来源会话ID',
  `use_count` int NOT NULL DEFAULT 1 COMMENT '使用次数',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `last_used_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_priority` (`user_id`, `priority`),
  KEY `idx_user_last_used` (`user_id`, `last_used_at`),
  KEY `idx_keywords` (`keywords`(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核心记忆表(对话内学习+跨会话记忆)';
