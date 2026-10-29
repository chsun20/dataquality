#!/bin/sh
# 把 docker/cloud/config 下的 cloud profile 配置发布到独立的 demo Nacos。
# 只在容器内运行，Nacos 不对外暴露控制台与端口。
set -eu

: "${NACOS_ADDR:?NACOS_ADDR 未设置}"
CONFIG_DIR=${CONFIG_DIR:-/config}
GROUP=${NACOS_GROUP:-DEFAULT_GROUP}

echo "[config] 等待 Nacos 就绪：${NACOS_ADDR}"
attempt=0
until curl -fsS "http://${NACOS_ADDR}/nacos/v1/console/health/readiness" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "${attempt}" -ge 60 ]; then
        echo "[config] Nacos 在等待窗口内未就绪，初始化失败"
        exit 1
    fi
    sleep 2
done

publish() {
    data_id="$1"
    file="$2"
    type="$3"
    attempt=0
    while :; do
        body=$(curl -fsS -X POST "http://${NACOS_ADDR}/nacos/v1/cs/configs" \
            --data-urlencode "dataId=${data_id}" \
            --data-urlencode "group=${GROUP}" \
            --data-urlencode "type=${type}" \
            --data-urlencode "content@${CONFIG_DIR}/${file}") || body=""
        if [ "${body}" = "true" ]; then
            echo "[config] 已发布 ${data_id}"
            return 0
        fi
        attempt=$((attempt + 1))
        if [ "${attempt}" -ge 5 ]; then
            echo "[config] 发布失败：${data_id}（返回：${body}）"
            exit 1
        fi
        sleep 2
    done
}

# 共享配置必须先于各服务配置，保证服务启动时能取到 application-cloud.yml
publish "application-cloud.yml" "application-cloud.yml" "yaml"
publish "ruoyi-gateway-cloud.yml" "ruoyi-gateway-cloud.yml" "yaml"
publish "ruoyi-auth-cloud.yml" "ruoyi-auth-cloud.yml" "yaml"
publish "ruoyi-system-cloud.yml" "ruoyi-system-cloud.yml" "yaml"
publish "ruoyi-dqservice-cloud.yml" "ruoyi-dqservice-cloud.yml" "yaml"
# 网关 bootstrap.yml 里 Sentinel 数据源的 flow 规则，内容为空规则集，避免启动期反复报错
publish "sentinel-ruoyi-gateway" "sentinel-ruoyi-gateway.json" "json"

echo "[config] 全部配置发布完成"
