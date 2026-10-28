package com.ruoyi.dqservice.config;

import org.apache.commons.lang.StringUtils;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public KeyGenerator cacheKeyGenerator() {
        return (o, method, objects) -> {
            StringBuilder sb = new StringBuilder(method.getName());
            String params = StringUtils.join(objects);
            if (!StringUtils.isEmpty(params)) {
                sb.append(params);
            }
            return sb.toString();
        };
    }
}
