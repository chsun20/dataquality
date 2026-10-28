-- ----------------------------
-- 数据质量执行报告表
-- 适用：ry-cloud 库
-- 日期：2026-10-26
-- 说明：
--   1. dq_execution 每次规则执行写一条汇总记录
--   2. dq_violation 保存该次执行的逐条违规明细
--   3. 使用 CREATE TABLE IF NOT EXISTS，重复执行不会清空历史数据
-- ----------------------------

CREATE TABLE IF NOT EXISTS `dq_execution` (
  `execution_id`    bigint(20)    NOT NULL AUTO_INCREMENT COMMENT '执行批次ID',
  `rule_id`         bigint(20)    DEFAULT NULL COMMENT '规则ID',
  `rule_name`       varchar(128)  DEFAULT NULL COMMENT '规则名称',
  `db_name`         varchar(64)   DEFAULT NULL COMMENT '数据库名',
  `table_name`      varchar(64)   DEFAULT NULL COMMENT '数据表名',
  `total_rows`      int(11)       DEFAULT '0' COMMENT '扫描行数',
  `violation_count` int(11)       DEFAULT '0' COMMENT '违规条数',
  `violation_rows`  int(11)       DEFAULT '0' COMMENT '命中行数',
  `pass_rate`       decimal(5,1)  DEFAULT '0.0' COMMENT '通过率(%)',
  `cost_ms`         bigint(20)    DEFAULT '0' COMMENT '执行耗时(毫秒)',
  `create_by`       varchar(64)   DEFAULT NULL COMMENT '执行人',
  `create_time`     datetime      DEFAULT NULL COMMENT '执行时间',
  PRIMARY KEY (`execution_id`),
  KEY `idx_rule_id` (`rule_id`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量执行记录';

CREATE TABLE IF NOT EXISTS `dq_violation` (
  `violation_id` bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '违规明细ID',
  `execution_id` bigint(20)   NOT NULL COMMENT '执行批次ID',
  `row_id`       int(11)      DEFAULT NULL COMMENT '扫描行号',
  `record_id`    varchar(64)  DEFAULT NULL COMMENT '记录主键值',
  `field_name`   varchar(64)  DEFAULT NULL COMMENT '违规字段',
  `rule_name`    varchar(128) DEFAULT NULL COMMENT '命中规则',
  `field_value`  varchar(255) DEFAULT NULL COMMENT '字段值',
  PRIMARY KEY (`violation_id`),
  KEY `idx_execution_id` (`execution_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据质量违规明细';
