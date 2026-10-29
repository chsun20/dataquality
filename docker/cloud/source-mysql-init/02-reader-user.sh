#!/bin/bash
# 演示数据源只创建一个限定库表的只读账号，业务写入一律不允许
set -euo pipefail

: "${DQ_READER_USER:?DQ_READER_USER 未设置}"
: "${DQ_READER_PASSWORD:?DQ_READER_PASSWORD 未设置}"

MYSQL_ARGS=(-uroot -p"${MYSQL_ROOT_PASSWORD}" --protocol=socket)

mysql "${MYSQL_ARGS[@]}" <<SQL
CREATE USER IF NOT EXISTS '${DQ_READER_USER}'@'%' IDENTIFIED BY '${DQ_READER_PASSWORD}';
-- 只授 SELECT：不要额外授写权限，也不要做无授权的 REVOKE（5.7 会报错）
GRANT SELECT ON \`dq_demo\`.* TO '${DQ_READER_USER}'@'%';
FLUSH PRIVILEGES;
SQL

echo "[source-init] 已创建只读账号 ${DQ_READER_USER}（仅 dq_demo 库 SELECT）"
