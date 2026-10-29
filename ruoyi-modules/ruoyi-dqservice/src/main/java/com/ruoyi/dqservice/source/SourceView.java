package com.ruoyi.dqservice.source;

import com.ruoyi.dqservice.domain.DataSource;
import lombok.Getter;

/** 返回对象采用显式白名单，凭据及密文均不出服务层。 */
@Getter
public final class SourceView
{
    private final Integer id;
    private final String name;
    private final String type;
    private final String description;
    private final String host;
    private final Integer port;
    private final String databaseName;
    private final String username;
    private final String tlsMode;
    private final String timeZone;
    private final Boolean enabled;
    private final Integer configVersion;
    private final boolean configured;

    public SourceView(DataSource source)
    {
        id = source.getId();
        name = source.getName();
        type = source.getType();
        description = source.getDescription();
        host = source.getHost();
        port = source.getPort();
        databaseName = source.getDatabaseName();
        username = source.getUsername();
        tlsMode = source.getTlsMode();
        timeZone = source.getTimeZone();
        enabled = source.getEnabled();
        configVersion = source.getConfigVersion();
        configured = source.getHost() != null && source.getPasswordCipher() != null;
    }
}
