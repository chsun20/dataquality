package com.ruoyi.dqservice.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ruoyi.common.core.exception.ServiceException;

public final class QualityJson
{
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private QualityJson() { }

    public static String write(Object value)
    {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new ServiceException("质控配置或结果无法序列化"); }
    }
}
