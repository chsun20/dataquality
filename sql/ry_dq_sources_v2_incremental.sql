-- 六维度与外部 MySQL 增量迁移。先备份平台元数据，在独立测试库验证后应用。
-- 不重建、不清空既有表。每列按 information_schema 判重；中断后可重新运行。
-- 使用 mysql 客户端执行，当前数据库必须为目标平台库。
-- mysql 客户端默认字符集可能是 latin1，显式声明避免中文注释被二次编码。
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS dq_add_column_v2$$
CREATE PROCEDURE dq_add_column_v2(IN table_arg varchar(64), IN column_arg varchar(64), IN definition_arg text)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = table_arg AND COLUMN_NAME = column_arg) THEN
        SET @dq_ddl = CONCAT('ALTER TABLE `', table_arg, '` ADD COLUMN `', column_arg, '` ', definition_arg);
        PREPARE dq_stmt FROM @dq_ddl;
        EXECUTE dq_stmt;
        DEALLOCATE PREPARE dq_stmt;
    END IF;
END$$
DELIMITER ;

CALL dq_add_column_v2('data_source', 'host', 'varchar(253) DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'port', 'int DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'database_name', 'varchar(64) DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'username', 'varchar(128) DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'password_cipher', 'text');
CALL dq_add_column_v2('data_source', 'tls_mode', 'varchar(32) DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'time_zone', 'varchar(64) DEFAULT NULL');
CALL dq_add_column_v2('data_source', 'enabled', 'tinyint(1) NOT NULL DEFAULT 0');
CALL dq_add_column_v2('data_source', 'config_version', 'int NOT NULL DEFAULT 0');
CALL dq_add_column_v2('rule', 'source_id', 'int DEFAULT NULL');

CREATE TABLE IF NOT EXISTS dq_rule_source_ref (
    rule_id bigint NOT NULL,
    source_id int NOT NULL,
    PRIMARY KEY (rule_id, source_id),
    KEY idx_dq_source_dependency (source_id),
    CONSTRAINT fk_dq_ref_rule FOREIGN KEY (rule_id) REFERENCES `rule` (rule_id),
    CONSTRAINT fk_dq_ref_source FOREIGN KEY (source_id) REFERENCES data_source (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则源连接及参考连接依赖';

CALL dq_add_column_v2('dq_execution', 'source_id', 'int DEFAULT NULL');
CALL dq_add_column_v2('dq_execution', 'source_name', 'varchar(128) DEFAULT NULL');
CALL dq_add_column_v2('dq_execution', 'detected_at', 'varchar(40) DEFAULT NULL');
CALL dq_add_column_v2('dq_execution', 'evaluation_status', 'varchar(24) DEFAULT NULL');
CALL dq_add_column_v2('dq_execution', 'error_message', 'varchar(500) DEFAULT NULL');
CALL dq_add_column_v2('dq_execution', 'config_snapshot', 'mediumtext');
CALL dq_add_column_v2('dq_execution', 'check_counts', 'mediumtext');
CALL dq_add_column_v2('dq_execution', 'unverifiable_count', 'int NOT NULL DEFAULT 0');
CALL dq_add_column_v2('dq_execution', 'scan_note', 'varchar(500) DEFAULT NULL');
CALL dq_add_column_v2('dq_violation', 'check_id', 'varchar(64) DEFAULT NULL');
CALL dq_add_column_v2('dq_violation', 'template_code', 'varchar(64) DEFAULT NULL');
CALL dq_add_column_v2('dq_violation', 'dimension_code', 'varchar(32) DEFAULT NULL');
CALL dq_add_column_v2('dq_violation', 'result_type', 'varchar(24) NOT NULL DEFAULT ''VIOLATION''');
CALL dq_add_column_v2('dq_violation', 'reason', 'varchar(255) DEFAULT NULL');
CALL dq_add_column_v2('dq_violation', 'comparison_value', 'text');
CALL dq_add_column_v2('dq_violation', 'involved_fields', 'text');
DROP PROCEDURE dq_add_column_v2;

-- 新按钮权限仅向管理员开放；其它角色在角色管理中显式授权。
-- 不覆盖或删除旧菜单，不给普通角色补权限。
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
                      menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT p.id, p.label, p.parent, p.id - 2120, '', '', 1, 0, 'F', '0', '0', p.perm, '#', 'admin', NOW(), '外部 MySQL 与六维度增量权限'
FROM (
    SELECT 2120 id, '连接列表' label, 2001 parent, 'dqservice:dataSource:list' perm UNION ALL
    SELECT 2121, '连接详情', 2001, 'dqservice:dataSource:query' UNION ALL
    SELECT 2122, '新增连接', 2001, 'dqservice:dataSource:add' UNION ALL
    SELECT 2123, '修改连接', 2001, 'dqservice:dataSource:edit' UNION ALL
    SELECT 2124, '删除连接', 2001, 'dqservice:dataSource:remove' UNION ALL
    SELECT 2125, '导出连接描述', 2001, 'dqservice:dataSource:export' UNION ALL
    SELECT 2126, '使用连接', 2001, 'dqservice:dataSource:use' UNION ALL
    SELECT 2127, '测试连接', 2001, 'dqservice:dataSource:test' UNION ALL
    SELECT 2128, '读取元数据', 2001, 'dqservice:dataSource:metadata' UNION ALL
    SELECT 2129, '预览数据', 2001, 'dqservice:dataSource:preview' UNION ALL
    SELECT 2130, '执行规则', 2002, 'dqservice:rule:execute' UNION ALL
    SELECT 2131, '自定义可信规则', 2002, 'dqservice:rule:custom'
) p
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.menu_id = p.id OR m.perms = p.perm);
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM sys_menu m
WHERE m.perms IN ('dqservice:dataSource:list','dqservice:dataSource:query','dqservice:dataSource:add',
                 'dqservice:dataSource:edit','dqservice:dataSource:remove','dqservice:dataSource:export',
                 'dqservice:dataSource:use','dqservice:dataSource:test','dqservice:dataSource:metadata',
                 'dqservice:dataSource:preview','dqservice:rule:execute','dqservice:rule:custom')
AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id);
