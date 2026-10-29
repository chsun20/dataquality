package com.ruoyi.dqservice;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.util.QualityStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 行级统计的独立回归，不依赖数据库或 Spring 上下文。 */
class QualityStatisticsTest
{
    @Test
    void repeatedHitsCountOncePerRowWithoutRemovingDetails()
    {
        List<Map<String, Object>> details = Arrays.asList(violation(1), violation(1L),
                violation(new BigDecimal("2.0")));
        assertEquals(2, QualityStatistics.countViolationRows(details, 3));
        assertEquals(3, details.size());
        assertEquals(Integer.valueOf(1), details.get(1).get("rowId"));
        assertEquals(Integer.valueOf(2), details.get(2).get("rowId"));
        assertEquals(33.3, QualityStatistics.passRate(3, 2).doubleValue());
    }

    @Test
    void emptyScanIsNotEvaluated()
    {
        assertEquals(0, QualityStatistics.countViolationRows(Collections.emptyList(), 0));
        assertNull(QualityStatistics.passRate(0, 0));
    }

    @ParameterizedTest
    @CsvSource({"10,0,100.0", "10,10,0.0", "3,1,66.7", "6,5,16.7", "10000,1,100.0"})
    void rateUsesActualScannedRowsAndOneDecimal(int total, int failed, double expected)
    {
        assertEquals(expected, QualityStatistics.passRate(total, failed).doubleValue());
    }

    @ParameterizedTest
    @CsvSource({"-1,0", "1,-1", "1,2", "0,1"})
    void inconsistentTotalsAreRejected(int total, int failed)
    {
        assertThrows(ServiceException.class, () -> QualityStatistics.passRate(total, failed));
    }

    @Test
    void invalidRowIdsAreRejectedRatherThanTruncatedOrIgnored()
    {
        for (Object value : Arrays.asList(null, "1", 0, -1, 4, 1.5, Long.MAX_VALUE, Double.NaN))
        {
            assertThrows(ServiceException.class,
                    () -> QualityStatistics.countViolationRows(Collections.singletonList(violation(value)), 3),
                    "不合法的行号：" + value);
        }
        assertThrows(ServiceException.class,
                () -> QualityStatistics.countViolationRows(Collections.singletonList(null), 3));
        assertThrows(ServiceException.class,
                () -> QualityStatistics.countViolationRows(Collections.singletonList(violation(1)), 0));
    }

    private Map<String, Object> violation(Object rowId)
    {
        Map<String, Object> detail = new HashMap<>();
        detail.put("rowId", rowId);
        return detail;
    }
}
