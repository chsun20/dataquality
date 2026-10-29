package com.ruoyi.dqservice.source;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

/** 连接写入请求；不生成包含凭据的 toString。 */
@Getter
@Setter
public class SourceRequest
{
    private Integer id;
    private String name;
    private String type = "MYSQL";
    private String description;
    private String host;
    private Integer port = 3306;
    private String databaseName;
    private String username;
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String tlsMode = "VERIFY_IDENTITY";
    private String timeZone = "UTC";
    private Boolean enabled = true;
    private Integer configVersion;
}
