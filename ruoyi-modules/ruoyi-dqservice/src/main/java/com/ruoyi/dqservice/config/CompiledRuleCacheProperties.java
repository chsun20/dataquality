package com.ruoyi.dqservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 编译缓存仅在进程内复用，配置在启动时绑定。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "dq.execution.compiled-cache")
public class CompiledRuleCacheProperties
{
    private boolean enabled = true;
    private int maximumSize = 32;
    private long expireAfterAccessMinutes = 30;

    public void validate()
    {
        if (maximumSize < 1 || expireAfterAccessMinutes < 1)
            throw new IllegalArgumentException("编译缓存容量和访问过期分钟数必须大于零");
    }
}
