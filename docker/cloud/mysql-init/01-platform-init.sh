#!/bin/bash
# 只在全新数据卷上执行：按固定顺序导入版本化 SQL，重建含数据初始化脚本的基础表。
# 若平台卷已有数据，MySQL 不会再执行本脚本，避免覆盖用户、规则与报告。
set -euo pipefail

DB="${MYSQL_DATABASE:-ry-cloud}"
SQL_DIR=/opt/sql

MYSQL_ARGS=(-uroot -p"${MYSQL_ROOT_PASSWORD}" --default-character-set=utf8mb4 --protocol=socket)

run_sql() {
    local file="$1"
    if [ ! -f "${SQL_DIR}/${file}" ]; then
        echo "[init] 缺少 SQL：${SQL_DIR}/${file}"
        exit 1
    fi
    echo "[init] 导入 ${file}"
    mysql "${MYSQL_ARGS[@]}" "${DB}" < "${SQL_DIR}/${file}"
}

echo "[init] 创建平台库 ${DB}"
mysql "${MYSQL_ARGS[@]}" -e "CREATE DATABASE IF NOT EXISTS \`${DB}\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 顺序不可随意调整：基础表 → 质控表 → 报告表 → 菜单 → 列增量 → 任务表与任务权限
run_sql ry_20210908.sql
run_sql ry_dqservice_20261026.sql
run_sql ry_dq_report_20261026.sql
run_sql ry_dq_rule_template_20261028.sql
run_sql ry_dqservice_menu_20261026.sql
run_sql ry_dq_report_menu_20261026.sql
run_sql ry_dq_sources_v2_incremental.sql
run_sql ry_dq_task_20261029.sql

# 初始化完成标记：常规重启不会重复导入，便于排查“到底是没初始化还是配置错”
mysql "${MYSQL_ARGS[@]}" "${DB}" <<'SQL'
CREATE TABLE IF NOT EXISTS dq_demo_init (
  version     varchar(32)  NOT NULL COMMENT '初始化版本',
  applied_at  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '完成时间',
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='演示环境初始化标记';

INSERT INTO dq_demo_init (version) VALUES ('local-ha-v1')
  ON DUPLICATE KEY UPDATE applied_at = applied_at;
SQL

echo "[init] 平台库初始化完成：${DB}"
