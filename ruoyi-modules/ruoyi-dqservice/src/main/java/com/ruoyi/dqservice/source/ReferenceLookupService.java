package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.template.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 按本次源业务键分批读取参考值；完整成功后才交给规则上下文。 */
@Service
public class ReferenceLookupService
{
    public List<Map<String, Object>> load(MysqlSourceClient.Session session, SourceTable source,
            List<TemplateCheck> checks, List<Map<String, Object>> rows, QualityExecutionContext context)
    {
        Map<List<Object>, QualityExecutionContext.ReferenceData> cache = new HashMap<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (TemplateCheck check : checks)
        {
            if (!check.isReference()) continue;
            Map<String, String> p = check.getParams();
            int id = ContextRuleTemplate.referenceSource(p);
            boolean match = "referenceMatch".equals(check.getTemplate());
            String sourceKey = match ? p.get("sourceKey") : check.getField();
            String keyType = source.column(sourceKey).getType();
            SourceTable reference = session.table(id, null, p.get("referenceTable"));
            String keyField = p.get("referenceKey");
            String valueField = match ? p.get("referenceValue") : null;
            Set<Object> requested = new LinkedHashSet<>();
            for (Map<String, Object> row : rows)
            {
                Object key = QualityTypes.key(row.get(sourceKey), keyType);
                if (key != null) requested.add(key);
            }
            List<Object> cacheKey = Arrays.asList(id, reference.getTableName(), keyField, valueField, requested);
            QualityExecutionContext.ReferenceData data = cache.get(cacheKey);
            if (data == null)
            {
                Map<Object, Object> loaded = new LinkedHashMap<>();
                List<Object> keys = new ArrayList<>(requested);
                for (int start = 0; start < keys.size(); start += 200)
                {
                    List<Object> batch = keys.subList(start, Math.min(start + 200, keys.size()));
                    Set<Object> exactBatch = new HashSet<>(batch);
                    Set<Object> seen = new HashSet<>();
                    for (Map<String, Object> row : session.reference(id, reference, keyField, valueField, batch))
                    {
                        Object key = QualityTypes.key(row.get(keyField), keyType);
                        // SQL collation 只选候选；最终严格按 Java 文本相等或规范化数值键匹配。
                        if (!exactBatch.contains(key)) continue;
                        if (match && !seen.add(key)) throw new ServiceException("参考键返回多条记录，准确性核验失败");
                        loaded.put(key, match ? row.get(valueField) : Boolean.TRUE);
                    }
                }
                session.checkBudget();
                data = new QualityExecutionContext.ReferenceData(keyType, loaded, Instant.now());
                cache.put(cacheKey, data);
            }
            context.addReference(check.getCheckId(), data);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("checkId", check.getCheckId());
            snapshot.put("sourceId", id);
            snapshot.put("sourceName", session.config(id).getName());
            snapshot.put("configVersion", session.config(id).getConfigVersion());
            snapshot.put("table", reference.getTableName());
            snapshot.put("key", keyField);
            snapshot.put("value", valueField);
            snapshot.put("basisName", p.get("basisName"));
            snapshot.put("basisVersion", p.get("basisVersion"));
            snapshot.put("readAt", data.getReadAt());
            snapshots.add(snapshot);
        }
        return snapshots;
    }
}
