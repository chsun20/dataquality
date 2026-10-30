-- 质控任务管理增量迁移。先备份平台元数据，在独立测试库验证后应用。
-- 不重建、不清空既有表；列与索引均按 information_schema 判重，中断后可重新运行。
-- 使用 mysql 客户端执行，当前数据库必须为目标平台库（ry-cloud）。
-- mysql 客户端默认字符集可能是 latin1，显式声明避免中文注释被二次编码。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS dq_add_column_v3$$
CREATE PROCEDURE dq_add_column_v3(IN table_arg varchar(64), IN column_arg varchar(64), IN definition_arg text)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_arg AND COLUMN_NAME = column_arg) THEN
        SET @dq_ddl = CONCAT('ALTER TABLE `', table_arg, '` ADD COLUMN `', column_arg, '` ', definition_arg);
        PREPARE dq_stmt FROM @dq_ddl;
        EXECUTE dq_stmt;
        DEALLOCATE PREPARE dq_stmt;
    END IF;
END$$

DROP PROCEDURE IF EXISTS dq_add_index_v3$$
CREATE PROCEDURE dq_add_index_v3(IN table_arg varchar(64), IN index_arg varchar(64), IN definition_arg text)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_arg AND INDEX_NAME = index_arg) THEN
        SET @dq_ddl = CONCAT('ALTER TABLE `', table_arg, '` ADD ', definition_arg);
        PREPARE dq_stmt FROM @dq_ddl;
        EXECUTE dq_stmt;
        DEALLOCATE PREPARE dq_stmt;
    END IF;
END$$
DELIMITER ;

-- 质控任务：一个任务固定绑定一个数据源的一张表，可挂多条规则。
CREATE TABLE IF NOT EXISTS dq_task (
    task_id bigint NOT NULL AUTO_INCREMENT,
    task_name varchar(100) NOT NULL,
    source_id int DEFAULT NULL COMMENT 'NULL 表示内置本地数据源',
    db_name varchar(64) NOT NULL,
    table_name varchar(64) NOT NULL,
    limit_rows int NOT NULL DEFAULT 1000 COMMENT '单次执行每条规则的扫描上限',
    enabled tinyint(1) NOT NULL DEFAULT 0 COMMENT '是否启用定时调度',
    cron_expression varchar(64) DEFAULT NULL COMMENT 'Spring 6 段 Cron（秒 分 时 日 月 周）',
    misfire_policy varchar(16) NOT NULL DEFAULT 'SKIP' COMMENT 'SKIP 跳过本轮；FIRE_ONCE 立即补跑一次',
    remark varchar(255) DEFAULT NULL,
    create_by varchar(64) DEFAULT NULL,
    create_time datetime DEFAULT NULL,
    update_by varchar(64) DEFAULT NULL,
    update_time datetime DEFAULT NULL,
    PRIMARY KEY (task_id),
    KEY idx_dq_task_source (source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质控任务';

-- 任务与规则关联：规则必须属于任务绑定的同一数据源与表。
CREATE TABLE IF NOT EXISTS dq_task_rule (
    task_id bigint NOT NULL,
    rule_id bigint NOT NULL,
    sort_no int NOT NULL DEFAULT 0,
    PRIMARY KEY (task_id, rule_id),
    KEY idx_dq_task_rule_rule (rule_id),
    CONSTRAINT fk_dq_task_rule_task FOREIGN KEY (task_id) REFERENCES dq_task (task_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质控任务规则关联';

-- 任务运行批次：一次触发对应一条记录，规则级结果在 dq_execution 中按 run_id 关联。
-- task_id 不建外键，任务删除后仍保留历史批次。
CREATE TABLE IF NOT EXISTS dq_task_run (
    run_id bigint NOT NULL AUTO_INCREMENT,
    task_id bigint NOT NULL,
    task_name varchar(100) NOT NULL COMMENT '触发时的任务名快照',
    trigger_type varchar(16) NOT NULL COMMENT 'MANUAL 手动触发；CRON 定时触发',
    trigger_by varchar(64) DEFAULT NULL,
    status varchar(16) NOT NULL COMMENT 'RUNNING/SUCCESS/PARTIAL/NO_DATA/FAILED/SKIPPED',
    rule_total int NOT NULL DEFAULT 0,
    rule_success int NOT NULL DEFAULT 0,
    rule_partial int NOT NULL DEFAULT 0,
    rule_no_data int NOT NULL DEFAULT 0,
    rule_failed int NOT NULL DEFAULT 0,
    violation_count int NOT NULL DEFAULT 0 COMMENT '规则违规明细条数合计，可按规则相加',
    unverifiable_count int NOT NULL DEFAULT 0,
    cost_ms bigint DEFAULT NULL,
    message varchar(500) DEFAULT NULL,
    started_at datetime NOT NULL,
    finished_at datetime DEFAULT NULL,
    running_key bigint DEFAULT NULL COMMENT 'RUNNING 时等于 task_id，结束后置 NULL',
    PRIMARY KEY (run_id),
    KEY idx_dq_task_run_task (task_id, started_at),
    UNIQUE KEY uk_dq_task_run_running (task_id, running_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质控任务运行批次';

-- 报告归属：任务触发的执行批次回填 run_id/task_id，手工执行保持 NULL。
CALL dq_add_column_v3('dq_execution', 'run_id', 'bigint DEFAULT NULL');
CALL dq_add_column_v3('dq_execution', 'task_id', 'bigint DEFAULT NULL');
CALL dq_add_index_v3('dq_execution', 'idx_dq_execution_run', 'KEY idx_dq_execution_run (run_id)');

DROP PROCEDURE dq_add_column_v3;
DROP PROCEDURE dq_add_index_v3;

-- 只向管理员开放新菜单；其它角色在角色管理中显式授权。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT 2140, '质控任务', 2000, 5, 'task', 'rules/task/index', 1, 0, 'C', '0', '0',
       'dqservice:task:list', 'job', 'admin', NOW(), '质控任务管理'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = 2140 OR m.perms = 'dqservice:task:list');

-- 挂载点必须是目录(2000 数据质量)：若依 buildMenus 只对 M 类型递归子路由，
-- 挂在 C 类型菜单(2002 规则管理)下会导致子菜单既不生成路由也不显示。
UPDATE sys_menu SET parent_id = 2000, order_num = 5
WHERE menu_id = 2140 AND parent_id <> 2000;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT p.id, p.label, 2140, p.id - 2140, '', '', 1, 0, 'F', '0', '0', p.perm, '#', 'admin', NOW(), '质控任务增量权限'
FROM (
    SELECT 2141 id, '任务详情' label, 'dqservice:task:query' perm UNION ALL
    SELECT 2142, '新增任务', 'dqservice:task:add' UNION ALL
    SELECT 2143, '修改任务', 'dqservice:task:edit' UNION ALL
    SELECT 2144, '删除任务', 'dqservice:task:remove' UNION ALL
    SELECT 2145, '执行任务', 'dqservice:task:execute' UNION ALL
    SELECT 2146, '运行历史', 'dqservice:task:run'
) p
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = p.id OR m.perms = p.perm);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE m.perms IN ('dqservice:task:list', 'dqservice:task:query', 'dqservice:task:add',
                  'dqservice:task:edit', 'dqservice:task:remove', 'dqservice:task:execute',
                  'dqservice:task:run')
AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);
