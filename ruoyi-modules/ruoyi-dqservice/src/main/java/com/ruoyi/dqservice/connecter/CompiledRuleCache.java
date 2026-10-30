package com.ruoyi.dqservice.connecter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.config.CompiledRuleCacheProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kie.api.KieBase;
import org.kie.api.KieServices;
import org.kie.api.builder.Message;
import org.kie.api.builder.ReleaseId;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.internal.utils.KieHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 只共享编译产物，不共享 session、globals、事实或检测结果。 */
@Component
@Slf4j
public class CompiledRuleCache
{
    // 辅助实现或编译选项改变时同时更新指纹；进程重启也会自然清空缓存。
    private static final String FINGERPRINT = "drools=7.5.0.Final;helpers=l5-v1;builder=default";
    private final boolean enabled;
    private final Cache<String, CompiledRule> cache;
    private final AtomicLong buildSuccess = new AtomicLong();
    private final AtomicLong buildFailure = new AtomicLong();

    public CompiledRuleCache()
    {
        this(new CompiledRuleCacheProperties());
    }

    @Autowired
    public CompiledRuleCache(CompiledRuleCacheProperties properties)
    {
        this(properties, Ticker.systemTicker());
    }

    // 可替换单调时钟，过期测试无需等待真实时间。
    CompiledRuleCache(CompiledRuleCacheProperties properties, Ticker ticker)
    {
        properties.validate();
        enabled = properties.isEnabled();
        cache = Caffeine.newBuilder().maximumSize(properties.getMaximumSize())
                .expireAfterAccess(properties.getExpireAfterAccessMinutes(), TimeUnit.MINUTES)
                .ticker(ticker).recordStats()
                .removalListener((key, value, cause) -> log.debug("编译缓存移除：key={}，cause={}", key, cause))
                .build();
    }

    public CompiledRule get(String drl)
    {
        long started = System.nanoTime();
        String key = key(drl);
        boolean[] loaded = {false};
        try
        {
            if (!enabled) return compile(drl, key);
            return cache.get(key, ignored -> {
                loaded[0] = true;
                return compile(drl, key);
            });
        }
        finally
        {
            CacheStats stats = cache.stats();
            log.debug("编译缓存查询：key={}，mode={}，lookup_ns={}，hits={}，misses={}，evictions={}",
                    key, !enabled ? "bypass" : loaded[0] ? "load" : "reuse",
                    System.nanoTime() - started, stats.hitCount(), stats.missCount(), stats.evictionCount());
        }
    }

    /** 保存前只验证，不把预览或校验请求装入执行缓存。 */
    public void verify(String drl)
    {
        ReleaseId release = releaseId();
        try
        {
            verify(helper(drl, release));
        }
        finally
        {
            KieServices.get().getRepository().removeKieModule(release);
        }
    }

    private CompiledRule compile(String drl, String key)
    {
        long started = System.nanoTime();
        ReleaseId release = releaseId();
        boolean success = false;
        KieContainer container = null;
        try
        {
            KieHelper helper = helper(drl, release);
            verify(helper);
            // Drools 7.5 的 helper.build() 固定读取默认坐标，必须显式使用本次坐标。
            KieServices services = KieServices.get();
            container = services.newKieContainer(release);
            // 独立 KieBase 不交给容器管理，临时容器释放不会销毁后续执行的 session。
            CompiledRule result = new CompiledRule(container.newKieBase(services.newKieBaseConfiguration()), release);
            buildSuccess.incrementAndGet();
            success = true;
            return result;
        }
        catch (RuntimeException | Error e)
        {
            buildFailure.incrementAndGet();
            throw e;
        }
        finally
        {
            try
            {
                if (container != null) container.dispose();
            }
            finally
            {
                KieServices.get().getRepository().removeKieModule(release);
                log.debug("规则构建：key={}，success={}，compile_ns={}", key, success, System.nanoTime() - started);
            }
        }
    }

    private static KieHelper helper(String drl, ReleaseId release)
    {
        KieHelper helper = new KieHelper();
        helper.kfs.generateAndWritePomXML(release);
        return helper.addContent(drl, ResourceType.DRL);
    }

    private static ReleaseId releaseId()
    {
        return KieServices.get().newReleaseId("dq.execution", "rule-" + UUID.randomUUID(), "1.0.0");
    }

    private static void verify(KieHelper helper)
    {
        List<Message> errors = helper.verify().getMessages(Message.Level.ERROR);
        if (!errors.isEmpty())
            throw new ServiceException("规则编译失败：" + errors.stream().map(Message::getText).collect(Collectors.joining("; ")));
    }

    private static String key(String drl)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((FINGERPRINT + "\0" + drl).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest)
            {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("运行环境不支持 SHA-256", e);
        }
    }

    public CacheStats stats() { return cache.stats(); }
    public long getBuildSuccessCount() { return buildSuccess.get(); }
    public long getBuildFailureCount() { return buildFailure.get(); }
    void cleanUp() { cache.cleanUp(); }
    long size() { return cache.estimatedSize(); }

    @Getter
    public static final class CompiledRule
    {
        private final KieBase base;
        private final boolean usesContext;
        private final ReleaseId releaseId;

        private CompiledRule(KieBase base, ReleaseId releaseId)
        {
            this.base = base;
            this.releaseId = releaseId;
            usesContext = base.getKiePackages().stream().flatMap(p -> p.getGlobalVariables().stream())
                    .anyMatch(g -> "qualityContext".equals(g.getName()));
        }
    }
}
