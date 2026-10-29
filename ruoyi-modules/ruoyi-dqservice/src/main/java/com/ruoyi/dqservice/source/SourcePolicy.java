package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.DataSource;
import java.net.InetAddress;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 默认拒绝所有外部目标，白名单只能由运维配置。 */
@Component
public class SourcePolicy
{
    @Value("${DQ_MYSQL_ALLOWED_TARGETS:}")
    private String allowedTargets = "";
    @Value("${DQ_MYSQL_LOCAL_TLS_DISABLED:}")
    private String localTlsDisabled = "";

    public void validate(DataSource source)
    {
        if (!"MYSQL".equals(source.getType())) fail("仅支持 MYSQL 数据源");
        text(source.getName(), 100, "名称");
        text(source.getUsername(), 128, "用户名");
        identifier(source.getDatabaseName());
        String host = source.getHost();
        if (host == null || host.length() > 253 || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]*"))
            fail("主机必须是 IPv4 地址或 DNS 主机名，不接受 JDBC URL");
        if (source.getPort() == null || source.getPort() < 1 || source.getPort() > 65535)
            fail("端口必须在 1～65535 之间");
        String target = host.toLowerCase(Locale.ROOT) + ":" + source.getPort();
        if (!entries(allowedTargets).contains(target)) fail("连接目标未列入服务器白名单");
        String tls = source.getTlsMode();
        if (!Arrays.asList("VERIFY_IDENTITY", "VERIFY_CA", "REQUIRED", "DISABLED").contains(tls))
            fail("TLS 模式不合法");
        if ("DISABLED".equals(tls))
        {
            if (!("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))
                    || !entries(localTlsDisabled).contains(target))
                fail("仅显式授权的本地目标可以关闭 TLS");
        }
        try { ZoneId.of(source.getTimeZone()); }
        catch (RuntimeException e) { fail("时区必须是有效的 IANA 时区，例如 Asia/Shanghai"); }
    }

    /** DNS 主机与其全部解析地址都要授权，防止白名单域名意外指向其它网络。 */
    public void validateResolvedAddresses(DataSource source)
    {
        validate(source);
        try
        {
            for (InetAddress address : InetAddress.getAllByName(source.getHost()))
            {
                if (!entries(allowedTargets).contains(address.getHostAddress() + ":" + source.getPort()))
                    fail("主机解析地址未列入服务器白名单");
            }
        }
        catch (java.net.UnknownHostException e) { fail("无法解析已配置的 MySQL 主机"); }
    }

    public static String identifier(String value)
    {
        if (value == null || value.length() > 64 || !value.matches("[A-Za-z0-9_\\-]+"))
            fail("库、表或字段标识符不合法（只允许字母、数字、下划线和短横线）");
        return "`" + value + "`";
    }

    public static void text(String value, int max, String label)
    {
        if (value == null || value.trim().isEmpty() || value.length() > max || value.indexOf('\0') >= 0)
            fail(label + "不能为空且长度不能超过 " + max);
    }

    private static Set<String> entries(String raw)
    {
        if (raw == null || raw.trim().isEmpty()) return Collections.emptySet();
        Set<String> values = new HashSet<>();
        for (String value : raw.split(",")) values.add(value.trim().toLowerCase(Locale.ROOT));
        return values;
    }

    private static void fail(String message) { throw new ServiceException(message); }
}
