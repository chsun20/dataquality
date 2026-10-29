package com.ruoyi.dqservice.util;

import com.ruoyi.common.core.exception.ServiceException;
import org.springframework.scheduling.support.CronExpression;

import java.util.Arrays;

/**
 * 质控任务 Cron 表达式处理。
 *
 * 前端沿用若依的六段表达式（秒 分 时 日 月 周），
 * 兼容习惯上多写的第七段年份（仅允许 *），统一转换为 Spring 可解析的六段。
 */
public final class TaskCron
{
    private TaskCron()
    {
    }

    public static String normalize(String cron)
    {
        if (cron == null || cron.trim().isEmpty())
        {
            throw new ServiceException("启用定时调度必须填写 Cron 表达式");
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 7)
        {
            if (!"*".equals(parts[6]))
            {
                throw new ServiceException("不支持按年份调度，请使用六段表达式");
            }
            parts = Arrays.copyOf(parts, 6);
        }
        if (parts.length != 6)
        {
            throw new ServiceException("Cron 表达式必须为六段：秒 分 时 日 月 周");
        }
        String value = String.join(" ", parts);
        try
        {
            CronExpression.parse(value);
        }
        catch (IllegalArgumentException e)
        {
            throw new ServiceException("Cron 表达式不合法：" + e.getMessage());
        }
        return value;
    }
}
