-- ----------------------------
-- 修复表/字段注释的二次编码乱码（ry-cloud 库）
-- 日期：2026-10-31
--
-- 成因：导入建表脚本时 mysql 客户端字符集是 latin1（5.7 默认 character_set_client=latin1），
--       脚本里的 UTF-8 中文注释被当作 cp1252 解释后又以 UTF-8 存回，库里留下双重编码的乱码。
--       受影响的是 sql/quartz.sql 与 sql/ry_dq_task_20261029.sql 建的表（dq_* / qrtz_*）；
--       sys_* 等由 sql/ry_20210908.sql 建的表因首行有 SET NAMES utf8mb4 而未受影响。
--
-- 用法：mysql -uroot -p --default-character-set=utf8mb4 -D ry-cloud < 本文件
-- 说明：写入的是还原后的显式中文注释，可重复执行（幂等）。
-- ----------------------------

SET NAMES utf8mb4;

-- ---------- 表注释 ----------
ALTER TABLE `dq_execution` COMMENT = '数据质量执行记录';
ALTER TABLE `dq_task` COMMENT = '质控任务';
ALTER TABLE `dq_task_rule` COMMENT = '质控任务规则关联';
ALTER TABLE `dq_task_run` COMMENT = '质控任务运行批次';
ALTER TABLE `dq_violation` COMMENT = '数据质量违规明细';
ALTER TABLE `qrtz_blob_triggers` COMMENT = 'Blob类型的触发器表';
ALTER TABLE `qrtz_calendars` COMMENT = '日历信息表';
ALTER TABLE `qrtz_cron_triggers` COMMENT = 'Cron类型的触发器表';
ALTER TABLE `qrtz_fired_triggers` COMMENT = '已触发的触发器表';
ALTER TABLE `qrtz_job_details` COMMENT = '任务详细信息表';
ALTER TABLE `qrtz_locks` COMMENT = '存储的悲观锁信息表';
ALTER TABLE `qrtz_paused_trigger_grps` COMMENT = '暂停的触发器表';
ALTER TABLE `qrtz_scheduler_state` COMMENT = '调度器状态表';
ALTER TABLE `qrtz_simple_triggers` COMMENT = '简单触发器的信息表';
ALTER TABLE `qrtz_simprop_triggers` COMMENT = '同步机制的行锁表';
ALTER TABLE `qrtz_triggers` COMMENT = '触发器详细信息表';

-- ---------- 字段注释：dq_execution ----------
ALTER TABLE `dq_execution` MODIFY COLUMN `execution_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '执行批次ID';
ALTER TABLE `dq_execution` MODIFY COLUMN `rule_id` bigint(20) NULL COMMENT '规则ID';
ALTER TABLE `dq_execution` MODIFY COLUMN `rule_name` varchar(128) NULL COMMENT '规则名称';
ALTER TABLE `dq_execution` MODIFY COLUMN `db_name` varchar(64) NULL COMMENT '数据库名';
ALTER TABLE `dq_execution` MODIFY COLUMN `table_name` varchar(64) NULL COMMENT '数据表名';
ALTER TABLE `dq_execution` MODIFY COLUMN `total_rows` int(11) NULL DEFAULT '0' COMMENT '扫描行数';
ALTER TABLE `dq_execution` MODIFY COLUMN `violation_count` int(11) NULL DEFAULT '0' COMMENT '违规条数';
ALTER TABLE `dq_execution` MODIFY COLUMN `violation_rows` int(11) NULL DEFAULT '0' COMMENT '命中行数';
ALTER TABLE `dq_execution` MODIFY COLUMN `pass_rate` decimal(5,1) NULL DEFAULT '0.0' COMMENT '通过率(%)';
ALTER TABLE `dq_execution` MODIFY COLUMN `cost_ms` bigint(20) NULL DEFAULT '0' COMMENT '执行耗时(毫秒)';
ALTER TABLE `dq_execution` MODIFY COLUMN `create_by` varchar(64) NULL COMMENT '执行人';
ALTER TABLE `dq_execution` MODIFY COLUMN `create_time` datetime NULL COMMENT '执行时间';

-- ---------- 字段注释：dq_task ----------
ALTER TABLE `dq_task` MODIFY COLUMN `source_id` int(11) NULL COMMENT 'NULL 表示内置本地数据源';
ALTER TABLE `dq_task` MODIFY COLUMN `limit_rows` int(11) NOT NULL DEFAULT '1000' COMMENT '单次执行每条规则的扫描上限';
ALTER TABLE `dq_task` MODIFY COLUMN `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用定时调度';
ALTER TABLE `dq_task` MODIFY COLUMN `cron_expression` varchar(64) NULL COMMENT 'Spring 6 段 Cron（秒 分 时 日 月 周）';
ALTER TABLE `dq_task` MODIFY COLUMN `misfire_policy` varchar(16) NOT NULL DEFAULT 'SKIP' COMMENT 'SKIP 跳过本轮；FIRE_ONCE 立即补跑一次';

-- ---------- 字段注释：dq_task_run ----------
ALTER TABLE `dq_task_run` MODIFY COLUMN `task_name` varchar(100) NOT NULL COMMENT '触发时的任务名快照';
ALTER TABLE `dq_task_run` MODIFY COLUMN `trigger_type` varchar(16) NOT NULL COMMENT 'MANUAL 手动触发；CRON 定时触发';
ALTER TABLE `dq_task_run` MODIFY COLUMN `violation_count` int(11) NOT NULL DEFAULT '0' COMMENT '规则违规明细条数合计，可按规则相加';
ALTER TABLE `dq_task_run` MODIFY COLUMN `running_key` bigint(20) NULL COMMENT 'RUNNING 时等于 task_id，结束后置 NULL';

-- ---------- 字段注释：dq_violation ----------
ALTER TABLE `dq_violation` MODIFY COLUMN `violation_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '违规明细ID';
ALTER TABLE `dq_violation` MODIFY COLUMN `execution_id` bigint(20) NOT NULL COMMENT '执行批次ID';
ALTER TABLE `dq_violation` MODIFY COLUMN `row_id` int(11) NULL COMMENT '扫描行号';
ALTER TABLE `dq_violation` MODIFY COLUMN `record_id` varchar(64) NULL COMMENT '记录主键值';
ALTER TABLE `dq_violation` MODIFY COLUMN `field_name` varchar(64) NULL COMMENT '违规字段';
ALTER TABLE `dq_violation` MODIFY COLUMN `rule_name` varchar(128) NULL COMMENT '命中规则';
ALTER TABLE `dq_violation` MODIFY COLUMN `field_value` varchar(255) NULL COMMENT '字段值';

-- ---------- 字段注释：qrtz_blob_triggers ----------
ALTER TABLE `qrtz_blob_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_blob_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键';
ALTER TABLE `qrtz_blob_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';
ALTER TABLE `qrtz_blob_triggers` MODIFY COLUMN `blob_data` blob NULL COMMENT '存放持久化Trigger对象';

-- ---------- 字段注释：qrtz_calendars ----------
ALTER TABLE `qrtz_calendars` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_calendars` MODIFY COLUMN `calendar_name` varchar(200) NOT NULL COMMENT '日历名称';
ALTER TABLE `qrtz_calendars` MODIFY COLUMN `calendar` blob NOT NULL COMMENT '存放持久化calendar对象';

-- ---------- 字段注释：qrtz_cron_triggers ----------
ALTER TABLE `qrtz_cron_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_cron_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键';
ALTER TABLE `qrtz_cron_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';
ALTER TABLE `qrtz_cron_triggers` MODIFY COLUMN `cron_expression` varchar(200) NOT NULL COMMENT 'cron表达式';
ALTER TABLE `qrtz_cron_triggers` MODIFY COLUMN `time_zone_id` varchar(80) NULL COMMENT '时区';

-- ---------- 字段注释：qrtz_fired_triggers ----------
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `entry_id` varchar(95) NOT NULL COMMENT '调度器实例id';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `instance_name` varchar(200) NOT NULL COMMENT '调度器实例名';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `fired_time` bigint(13) NOT NULL COMMENT '触发的时间';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `sched_time` bigint(13) NOT NULL COMMENT '定时器制定的时间';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `priority` int(11) NOT NULL COMMENT '优先级';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `state` varchar(16) NOT NULL COMMENT '状态';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `job_name` varchar(200) NULL COMMENT '任务名称';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `job_group` varchar(200) NULL COMMENT '任务组名';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `is_nonconcurrent` varchar(1) NULL COMMENT '是否并发';
ALTER TABLE `qrtz_fired_triggers` MODIFY COLUMN `requests_recovery` varchar(1) NULL COMMENT '是否接受恢复执行';

-- ---------- 字段注释：qrtz_job_details ----------
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `job_name` varchar(200) NOT NULL COMMENT '任务名称';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `job_group` varchar(200) NOT NULL COMMENT '任务组名';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `description` varchar(250) NULL COMMENT '相关介绍';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `job_class_name` varchar(250) NOT NULL COMMENT '执行任务类名称';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `is_durable` varchar(1) NOT NULL COMMENT '是否持久化';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `is_nonconcurrent` varchar(1) NOT NULL COMMENT '是否并发';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `is_update_data` varchar(1) NOT NULL COMMENT '是否更新数据';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `requests_recovery` varchar(1) NOT NULL COMMENT '是否接受恢复执行';
ALTER TABLE `qrtz_job_details` MODIFY COLUMN `job_data` blob NULL COMMENT '存放持久化job对象';

-- ---------- 字段注释：qrtz_locks ----------
ALTER TABLE `qrtz_locks` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_locks` MODIFY COLUMN `lock_name` varchar(40) NOT NULL COMMENT '悲观锁名称';

-- ---------- 字段注释：qrtz_paused_trigger_grps ----------
ALTER TABLE `qrtz_paused_trigger_grps` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_paused_trigger_grps` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';

-- ---------- 字段注释：qrtz_scheduler_state ----------
ALTER TABLE `qrtz_scheduler_state` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_scheduler_state` MODIFY COLUMN `instance_name` varchar(200) NOT NULL COMMENT '实例名称';
ALTER TABLE `qrtz_scheduler_state` MODIFY COLUMN `last_checkin_time` bigint(13) NOT NULL COMMENT '上次检查时间';
ALTER TABLE `qrtz_scheduler_state` MODIFY COLUMN `checkin_interval` bigint(13) NOT NULL COMMENT '检查间隔时间';

-- ---------- 字段注释：qrtz_simple_triggers ----------
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键';
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `repeat_count` bigint(7) NOT NULL COMMENT '重复的次数统计';
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `repeat_interval` bigint(12) NOT NULL COMMENT '重复的间隔时间';
ALTER TABLE `qrtz_simple_triggers` MODIFY COLUMN `times_triggered` bigint(10) NOT NULL COMMENT '已经触发的次数';

-- ---------- 字段注释：qrtz_simprop_triggers ----------
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_name的外键';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT 'qrtz_triggers表trigger_group的外键';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `str_prop_1` varchar(512) NULL COMMENT 'String类型的trigger的第一个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `str_prop_2` varchar(512) NULL COMMENT 'String类型的trigger的第二个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `str_prop_3` varchar(512) NULL COMMENT 'String类型的trigger的第三个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `int_prop_1` int(11) NULL COMMENT 'int类型的trigger的第一个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `int_prop_2` int(11) NULL COMMENT 'int类型的trigger的第二个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `long_prop_1` bigint(20) NULL COMMENT 'long类型的trigger的第一个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `long_prop_2` bigint(20) NULL COMMENT 'long类型的trigger的第二个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `dec_prop_1` decimal(13,4) NULL COMMENT 'decimal类型的trigger的第一个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `dec_prop_2` decimal(13,4) NULL COMMENT 'decimal类型的trigger的第二个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `bool_prop_1` varchar(1) NULL COMMENT 'Boolean类型的trigger的第一个参数';
ALTER TABLE `qrtz_simprop_triggers` MODIFY COLUMN `bool_prop_2` varchar(1) NULL COMMENT 'Boolean类型的trigger的第二个参数';

-- ---------- 字段注释：qrtz_triggers ----------
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `sched_name` varchar(120) NOT NULL COMMENT '调度名称';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `trigger_name` varchar(200) NOT NULL COMMENT '触发器的名字';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `trigger_group` varchar(200) NOT NULL COMMENT '触发器所属组的名字';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `job_name` varchar(200) NOT NULL COMMENT 'qrtz_job_details表job_name的外键';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `job_group` varchar(200) NOT NULL COMMENT 'qrtz_job_details表job_group的外键';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `description` varchar(250) NULL COMMENT '相关介绍';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `next_fire_time` bigint(13) NULL COMMENT '上一次触发时间（毫秒）';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `prev_fire_time` bigint(13) NULL COMMENT '下一次触发时间（默认为-1表示不触发）';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `priority` int(11) NULL COMMENT '优先级';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `trigger_state` varchar(16) NOT NULL COMMENT '触发器状态';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `trigger_type` varchar(8) NOT NULL COMMENT '触发器的类型';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `start_time` bigint(13) NOT NULL COMMENT '开始时间';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `end_time` bigint(13) NULL COMMENT '结束时间';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `calendar_name` varchar(200) NULL COMMENT '日程表名称';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `misfire_instr` smallint(2) NULL COMMENT '补偿执行的策略';
ALTER TABLE `qrtz_triggers` MODIFY COLUMN `job_data` blob NULL COMMENT '存放持久化job对象';
