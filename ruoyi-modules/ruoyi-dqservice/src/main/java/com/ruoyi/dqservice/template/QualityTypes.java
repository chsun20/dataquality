package com.ruoyi.dqservice.template;

import com.ruoyi.common.core.exception.ServiceException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.Locale;

/** 严格按字段类型处理，不把文本数字当作数值。 */
public final class QualityTypes
{
    private QualityTypes() { }

    public static String kind(String type)
    {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT).replaceAll("\\(.*", "").trim();
        if (t.matches("tinyint|smallint|mediumint|int|integer|bigint|decimal|numeric|float|double|real")) return "NUMBER";
        if (t.matches("char|varchar|tinytext|text|mediumtext|longtext|enum|set|string")) return "TEXT";
        if (t.matches("datetime|timestamp|date")) return "TIME";
        return "OTHER";
    }

    public static BigDecimal number(Object value)
    {
        if (!(value instanceof Number)) throw new ServiceException("数值字段返回了不兼容的数据类型");
        try { return new BigDecimal(value.toString()); }
        catch (NumberFormatException e) { throw new ServiceException("数值字段包含非有限数值"); }
    }

    public static Instant instant(Object value, String zone)
    {
        return instant(value, ZoneId.of(zone));
    }

    static Instant instant(Object value, ZoneId zoneId)
    {
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof Timestamp) return ((Timestamp) value).toInstant();
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate().atStartOfDay(zoneId).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        if (value instanceof LocalDateTime)
        {
            LocalDateTime time = (LocalDateTime) value;
            java.util.List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(time);
            if (offsets.size() != 1) throw new ServiceException("时间落在夏令时重叠或缺失区间，无法唯一确定时间点");
            return time.toInstant(offsets.get(0));
        }
        if (value instanceof LocalDate) return ((LocalDate) value).atStartOfDay(zoneId).toInstant();
        if (value instanceof OffsetDateTime) return ((OffsetDateTime) value).toInstant();
        throw new ServiceException("时间字段返回了不兼容的数据类型");
    }

    public static int compare(Object left, Object right, String type, String zone)
    {
        String kind = kind(type);
        return comparePrepared(left, right, kind, "TIME".equals(kind) ? ZoneId.of(zone) : null);
    }

    static int comparePrepared(Object left, Object right, String kind, ZoneId zone)
    {
        switch (kind)
        {
            case "NUMBER": return number(left).compareTo(number(right));
            case "TIME": return instant(left, zone).compareTo(instant(right, zone));
            case "TEXT":
                if (!(left instanceof String) || !(right instanceof String))
                    throw new ServiceException("文本字段返回了不兼容的数据类型");
                return ((String) left).compareTo((String) right);
            default: throw new ServiceException("不支持此字段类型的值比较");
        }
    }

    public static Object key(Object value, String type)
    {
        if (value == null) return null;
        if ("NUMBER".equals(kind(type))) return number(value).stripTrailingZeros();
        if ("TEXT".equals(kind(type)) && value instanceof String) return value;
        throw new ServiceException("参考业务键仅支持兼容的数值或文本类型");
    }
}
