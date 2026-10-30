package com.ruoyi.dqservice.connecter;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.config.CompiledRuleCacheProperties;
import com.ruoyi.dqservice.template.DrlSupport;
import com.ruoyi.dqservice.template.QualityExecutionContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.runtime.KieSession;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实编译产物验证复用与隔离，不用耗时阈值推断缓存命中。 */
class QualityCompiledRuleCacheTest
{
    private String drl(String value)
    {
        return DrlSupport.HEADER + DrlSupport.ruleBlock("同名规则", "v", "test", "this[\"v\"] == \"" + value + "\"");
    }

    private List<Map<String, Object>> rows(String value, int rowId)
    {
        Map<String, Object> row = new HashMap<>();
        row.put("v", value);
        row.put("__rowId", rowId);
        return Collections.singletonList(row);
    }

    @Test
    void identicalContentReusesBaseAndChangedContentTakesEffect()
    {
        CompiledRuleCache cache = new CompiledRuleCache();
        DroolsManager manager = new DroolsManager(cache);
        List<Map<String, Object>> first = manager.fireRules(drl("A"), rows("A", 1));
        List<Map<String, Object>> second = manager.fireRules(drl("A"), rows("B", 2));
        assertEquals(1, first.size());
        assertTrue(second.isEmpty());
        assertNotSame(first, second);
        assertEquals(1, cache.getBuildSuccessCount());
        assertEquals(1, cache.stats().hitCount());
        assertEquals(1, manager.fireRules(drl("B"), rows("B", 2)).size());
        assertEquals(2, cache.getBuildSuccessCount());
        CompiledRuleCache.CompiledRule compiled = cache.get(drl("A"));
        assertSame(compiled, cache.get(drl("A")));
        assertNull(KieServices.get().getRepository().getKieModule(compiled.getReleaseId()));
    }

    @Test
    void failedCompilationIsNotCachedAndVerificationDoesNotFillExecutionCache()
    {
        CompiledRuleCache cache = new CompiledRuleCache();
        String invalid = DrlSupport.HEADER + "rule \"broken\" when MissingFactType() then end";
        assertThrows(ServiceException.class, () -> cache.get(invalid));
        assertThrows(ServiceException.class, () -> cache.get(invalid));
        assertEquals(2, cache.getBuildFailureCount());
        assertEquals(0, cache.size());
        cache.verify(drl("A"));
        assertEquals(0, cache.size());
        assertEquals(0, cache.getBuildSuccessCount());
        assertEquals(1, new DroolsManager(cache).fireRules(drl("A"), rows("A", 1)).size());
    }

    @Test
    void disabledCacheBuildsEveryTime()
    {
        CompiledRuleCacheProperties p = new CompiledRuleCacheProperties();
        p.setEnabled(false);
        CompiledRuleCache cache = new CompiledRuleCache(p);
        assertNotSame(cache.get(drl("A")), cache.get(drl("A")));
        assertEquals(2, cache.getBuildSuccessCount());
        assertEquals(0, cache.size());
    }

    @Test
    void expirationRebuildsWithoutDestroyingAnActiveSession()
    {
        AtomicLong clock = new AtomicLong();
        CompiledRuleCache cache = new CompiledRuleCache(new CompiledRuleCacheProperties(), clock::get);
        CompiledRuleCache.CompiledRule first = cache.get(drl("A"));
        KieSession session = first.getBase().newKieSession();
        try
        {
            List<Map<String, Object>> hits = new ArrayList<>();
            List<Map<String, Object>> rows = rows("A", 1);
            session.setGlobal("violations", hits);
            session.setGlobal("qualityContext", new QualityExecutionContext(Instant.now(), rows));
            session.insert(rows.get(0));
            clock.addAndGet(TimeUnit.MINUTES.toNanos(31));
            cache.cleanUp();
            assertEquals(0, cache.size());
            assertNotSame(first, cache.get(drl("A")));
            assertEquals(2, cache.getBuildSuccessCount());
            assertEquals(1, session.fireAllRules());
            assertEquals(1, hits.size());
        }
        finally { session.dispose(); }
    }

    @Test
    void maximumSizeBoundsResidentEntriesAndEvictedContentCanBeBuiltAgain()
    {
        CompiledRuleCacheProperties p = new CompiledRuleCacheProperties();
        p.setMaximumSize(1);
        CompiledRuleCache cache = new CompiledRuleCache(p);
        cache.get(drl("A"));
        cache.get(drl("B"));
        cache.cleanUp();
        assertEquals(1, cache.size());
        assertTrue(cache.stats().evictionCount() >= 1);
        long builds = cache.getBuildSuccessCount();
        cache.get(drl("A"));
        cache.get(drl("B"));
        assertTrue(cache.getBuildSuccessCount() > builds);
        cache.cleanUp();
        assertEquals(1, cache.size());
    }

    @Test
    void concurrentSameContentBuildsOnceAndUsesIndependentGlobals() throws Exception
    {
        concurrent(false);
    }

    @Test
    void concurrentDifferentContentDoesNotUseAnotherModulesRules() throws Exception
    {
        concurrent(true);
    }

    private void concurrent(boolean differentRules) throws Exception
    {
        CompiledRuleCache cache = new CompiledRuleCache();
        DroolsManager manager = new DroolsManager(cache);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();
        try
        {
            for (int i = 0; i < 4; i++)
            {
                final int id = i + 1;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未启动");
                    String value = differentRules ? "V" + id : "A";
                    return manager.fireRules(drl(value), rows(value, id));
                }));
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            for (int i = 0; i < futures.size(); i++)
            {
                List<Map<String, Object>> hits = futures.get(i).get(60, TimeUnit.SECONDS);
                assertEquals(1, hits.size());
                assertEquals(i + 1, hits.get(0).get("rowId"));
            }
            assertEquals(differentRules ? 4 : 1, cache.getBuildSuccessCount());
        }
        finally
        {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void springBindingAndConstructorInjectionUseConfiguredCache()
    {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext())
        {
            Map<String, Object> values = new HashMap<>();
            values.put("dq.execution.compiled-cache.enabled", "false");
            values.put("dq.execution.compiled-cache.maximum-size", "2");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
            context.register(Binding.class, CompiledRuleCache.class, DroolsManager.class);
            context.refresh();
            DroolsManager manager = context.getBean(DroolsManager.class);
            manager.fireRules(drl("A"), rows("A", 1));
            manager.fireRules(drl("A"), rows("A", 2));
            assertEquals(2, context.getBean(CompiledRuleCache.class).getBuildSuccessCount());
            assertEquals(2, context.getBean(CompiledRuleCacheProperties.class).getMaximumSize());
        }
        CompiledRuleCacheProperties p = new CompiledRuleCacheProperties();
        p.setMaximumSize(0);
        assertThrows(IllegalArgumentException.class, () -> new CompiledRuleCache(p));
        p.setMaximumSize(1);
        p.setExpireAfterAccessMinutes(0);
        assertThrows(IllegalArgumentException.class, () -> new CompiledRuleCache(p));
    }

    @Configuration
    @EnableConfigurationProperties(CompiledRuleCacheProperties.class)
    static class Binding { }
}
