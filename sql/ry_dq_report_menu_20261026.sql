-- ----------------------------
-- 数据质量模块 - 质量报告菜单
-- 适用：ry-cloud 库
-- 日期：2026-10-26
-- 说明：挂在「数据质量」目录下，指向 rules/report/index 页面
-- ----------------------------

-- mysql 客户端默认字符集可能是 latin1，显式声明避免中文注释与菜单名被二次编码
SET NAMES utf8mb4;

USE `ry-cloud`;

DELETE FROM sys_menu WHERE menu_id BETWEEN 2010 AND 2019;

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query, is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time, remark)
VALUES (2010, '质量报告', 2000, 4, 'report', 'rules/report/index', '', 1, 0, 'C', '0', '0', 'dqservice:report:list', 'chart', 'admin', NOW(), '规则执行历史报告');
