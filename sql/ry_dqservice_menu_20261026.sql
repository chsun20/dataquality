-- ----------------------------
-- 数据质量模块菜单（dqservice）
-- 适用：ry-cloud 库
-- 日期：2026-10-26
-- 说明：
--   1. menu_id 从 2000 起，避开 RuoYi 原生 1060 以内区间，便于后续升级
--   2. 前端页面已存在：views/dataAssets/DataSourceList.vue、views/rules/Rules.vue、
--      views/rules/accuracy/index.vue
--   3. admin 为超管，插入后即时可见；其他角色需在【系统管理-角色管理】中勾选授权
-- ----------------------------

DELETE FROM sys_menu WHERE menu_id BETWEEN 2000 AND 2099;

-- 一级目录：数据质量
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2000, '数据质量', 0, 5, 'dq', NULL, '', 1, 0, 'M', '0', '0', '', 'education', 'admin', NOW(), '数据质量模块目录');

-- 数据资产（Hive 元数据表浏览）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2001, '数据资产', 2000, 1, 'dataAssets', 'dataAssets/DataSourceList', '', 1, 0, 'C', '0', '0', 'dqservice:metastore:list', 'table', 'admin', NOW(), 'Hive 元数据表浏览');

-- 规则管理（规则列表）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2002, '规则管理', 2000, 2, 'rule', 'rules/Rules', '', 1, 0, 'C', '0', '0', 'dqservice:rule:list', 'edit', 'admin', NOW(), '质量规则列表');

-- 规则制作（四步向导：选数据源 -> 选规则 -> 选分区 -> 命名）
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2003, '规则制作', 2000, 3, 'ruleMaking', 'rules/accuracy/index', '', 1, 0, 'C', '0', '0', 'dqservice:rule:add', 'form', 'admin', NOW(), '规则制作向导');

-- 规则管理按钮权限
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2004, '规则查询', 2002, 1, '', '', '', 1, 0, 'F', '0', '0', 'dqservice:rule:query', '#', 'admin', NOW(), ''),
       (2005, '规则新增', 2002, 2, '', '', '', 1, 0, 'F', '0', '0', 'dqservice:rule:add', '#', 'admin', NOW(), ''),
       (2006, '规则修改', 2002, 3, '', '', '', 1, 0, 'F', '0', '0', 'dqservice:rule:edit', '#', 'admin', NOW(), ''),
       (2007, '规则删除', 2002, 4, '', '', '', 1, 0, 'F', '0', '0', 'dqservice:rule:remove', '#', 'admin', NOW(), ''),
       (2008, '规则导出', 2002, 5, '', '', '', 1, 0, 'F', '0', '0', 'dqservice:rule:export', '#', 'admin', NOW(), '');
