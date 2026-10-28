package com.ruoyi.dqservice.connecter;

import javax.annotation.PreDestroy;

import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class HiveMetaStoreProxy {
    private static final Logger LOGGER = LoggerFactory
            .getLogger(HiveMetaStoreProxy.class);

    @Value("${hive.metastore.uris}")
    private String uris;

    /**
     * Set attempts and interval for HiveMetastoreClient to retry.
     *
     * @hive.hmshandler.retry.attempts: The number of times to retry a
     * HMSHandler call if there were a connection error
     * .
     * @hive.hmshandler.retry.interval: The time between HMSHandler retry
     * attempts on failure.
     */
    @Value("${hive.hmshandler.retry.attempts}")
    private int attempts;

    @Value("${hive.hmshandler.retry.interval}")
    private String interval;

    private IMetaStoreClient client = null;

    @Bean
    public IMetaStoreClient initHiveMetastoreClient() {
        try {
            HiveConf hiveConf = new HiveConf();
            hiveConf.set("hive.metastore.local", "false");
            hiveConf.setIntVar(HiveConf.ConfVars.METASTORETHRIFTCONNECTIONRETRIES,
                    3);
            hiveConf.setVar(HiveConf.ConfVars.METASTOREURIS, uris);
            hiveConf.setIntVar(HiveConf.ConfVars.HMSHANDLERATTEMPTS, attempts);
            hiveConf.setVar(HiveConf.ConfVars.HMSHANDLERINTERVAL, interval);
            client = HiveMetaStoreClient.newSynchronizedClient(new HiveMetaStoreClient(hiveConf));
        } catch (Throwable e) {
            // Hive 环境不可用时（含 fat jar 下 HiveConf 静态初始化失败）不影响其他功能启动
            LOGGER.error("Failed to connect hive metastore, hive features unavailable. {}", e.getMessage());
        }
        return client;
    }

    @PreDestroy
    public void destroy() {
        if (null != client) {
            client.close();
        }
    }
}
