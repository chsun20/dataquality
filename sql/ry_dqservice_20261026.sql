-- ----------------------------
-- 数据质量模块表结构（rule / data_source）
-- 适用：ry-cloud 库
-- 日期：2026-10-26
-- ----------------------------

-- mysql 客户端默认字符集可能是 latin1，显式声明避免中文注释被二次编码
SET NAMES utf8mb4;

DROP TABLE IF EXISTS `rule`;
CREATE TABLE `rule` (
  `rule_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '规则ID',
  `rule_name` varchar(255) DEFAULT NULL COMMENT '规则名称',
  `user_id` bigint(20) DEFAULT NULL COMMENT '用户ID',
  `user_name` varchar(64) DEFAULT NULL COMMENT '用户名称',
  `rule_context` text COMMENT '规则内容',
  `db_name` varchar(128) DEFAULT NULL COMMENT '数据库名',
  `table_name` varchar(128) DEFAULT NULL COMMENT '表名',
  `column_name` varchar(500) DEFAULT NULL COMMENT '列名',
  `type_name` varchar(64) DEFAULT NULL COMMENT '规则类型名称',
  `type_code` varchar(64) DEFAULT NULL COMMENT '规则类型编码',
  `source_where` varchar(500) DEFAULT NULL COMMENT '数据过滤条件',
  `source_partition_number` int(11) DEFAULT NULL COMMENT '分区数量',
  `source_partition_unit` varchar(32) DEFAULT NULL COMMENT '分区单位',
  `source_time_zone` varchar(64) DEFAULT NULL COMMENT '时区',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del` int(1) DEFAULT '0' COMMENT '删除标志（0存在 1删除）',
  PRIMARY KEY (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量规则表';

DROP TABLE IF EXISTS `data_source`;
CREATE TABLE `data_source` (
  `id` int(11) NOT NULL AUTO_INCREMENT COMMENT '数据源ID',
  `name` varchar(128) DEFAULT NULL COMMENT '数据源名称',
  `type` varchar(64) DEFAULT NULL COMMENT '数据源类型',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `creat_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `del` int(1) DEFAULT '0' COMMENT '删除标志（0存在 1删除）',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源表';
