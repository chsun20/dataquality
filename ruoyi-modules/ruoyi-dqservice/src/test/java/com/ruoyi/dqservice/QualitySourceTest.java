package com.ruoyi.dqservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import com.ruoyi.dqservice.source.*;
import com.ruoyi.dqservice.template.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 安全边界与参考批次测试；不以 mock 冒充真实 MySQL 联调。 */
class QualitySourceTest
{
    @Test
    void aesGcmUsesRandomNonceAndRejectsTamperingAndMissingKey()
    {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        SourceCredentialCipher cipher = new SourceCredentialCipher(() -> key);
        String first = cipher.encrypt("test-only-口令");
        String second = cipher.encrypt("test-only-口令");
        assertNotEquals(first, second);
        assertEquals("test-only-口令", cipher.decrypt(first));
        byte[] packed = Base64.getDecoder().decode(first.substring(3));
        packed[15] ^= 1;
        assertThrows(ServiceException.class, () -> cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(packed)));
        assertThrows(ServiceException.class, () -> new SourceCredentialCipher(() -> null).encrypt("test"));
        assertThrows(ServiceException.class, () -> cipher.decrypt("v2:unknown"));
    }

    @Test
    void credentialsNeverAppearInDtoEntityJsonOrToString() throws Exception
    {
        ObjectMapper json = new ObjectMapper();
        SourceRequest request = json.readValue("{\"password\":\"test-only-password\"}", SourceRequest.class);
        assertEquals("test-only-password", request.getPassword());
        assertFalse(json.writeValueAsString(request).contains("test-only-password"));
        assertFalse(request.toString().contains("test-only-password"));
        DataSource source = source();
        source.setPasswordCipher("test-only-cipher");
        assertFalse(json.writeValueAsString(source).contains("test-only-cipher"));
        assertFalse(source.toString().contains("test-only-cipher"));
        String response = json.writeValueAsString(new SourceView(source));
        assertFalse(response.contains("password"));
        assertFalse(response.contains("cipher"));
    }

    @Test
    void defaultDenyTlsAndIdentifierPoliciesAreEnforced()
    {
        SourcePolicy policy = new SourcePolicy();
        DataSource source = source();
        assertThrows(ServiceException.class, () -> policy.validate(source));
        ReflectionTestUtils.setField(policy, "allowedTargets", "127.0.0.1:3307");
        assertDoesNotThrow(() -> policy.validateResolvedAddresses(source));
        source.setTlsMode("DISABLED");
        assertThrows(ServiceException.class, () -> policy.validate(source));
        ReflectionTestUtils.setField(policy, "localTlsDisabled", "127.0.0.1:3307");
        assertDoesNotThrow(() -> policy.validate(source));
        source.setHost("jdbc:mysql://127.0.0.1");
        assertThrows(ServiceException.class, () -> policy.validate(source));
        for (String value : Arrays.asList("a`b", "a;SELECT 1", "../db", "a b", ""))
            assertThrows(ServiceException.class, () -> SourcePolicy.identifier(value));
        assertEquals("`test-db`", SourcePolicy.identifier("test-db"));
    }

    @Test
    void disabledUnconfiguredDeletedAndDatabaseSwitchAreRejected()
    {
        MysqlSourceClient client = new MysqlSourceClient();
        DataSourceMapper mapper = mock(DataSourceMapper.class);
        ReflectionTestUtils.setField(client, "sources", mapper);
        assertThrows(ServiceException.class, () -> client.requireSource(1));
        DataSource source = source();
        when(mapper.selectDataSourceById(1)).thenReturn(source);
        assertThrows(ServiceException.class, () -> client.requireSource(1));
        source.setPasswordCipher("cipher");
        source.setEnabled(false);
        assertThrows(ServiceException.class, () -> client.requireSource(1));
        source.setEnabled(true);
        try (MysqlSourceClient.Session session = client.session(true))
        {
            assertEquals("test_db", session.database(1, null));
            assertThrows(ServiceException.class, () -> session.database(1, "other_db"));
        }
    }

    @Test
    void externalPermitsAreReleasedExactlyOnce()
    {
        MysqlSourceClient client = new MysqlSourceClient();
        MysqlSourceClient.Session first = client.session(true);
        MysqlSourceClient.Session second = client.session(true);
        assertThrows(ServiceException.class, () -> client.session(true));
        first.close();
        first.close();
        try (MysqlSourceClient.Session third = client.session(true))
        {
            assertThrows(ServiceException.class, () -> client.session(true));
        }
        second.close();
        try (MysqlSourceClient.Session fourth = client.session(true); MysqlSourceClient.Session fifth = client.session(true))
        {
            assertThrows(ServiceException.class, () -> client.session(true));
        }
    }

    @Test
    void dependencyPreventsSoftDeletionAndConfigurationVersionPreventsLostUpdate()
    {
        DataSourceMapper mapper = mock(DataSourceMapper.class);
        SourceManagementService management = new SourceManagementService();
        ReflectionTestUtils.setField(management, "mapper", mapper);
        DataSource source = source();
        source.setConfigVersion(2);
        when(mapper.lockById(1)).thenReturn(source);
        when(mapper.countDependencies(1)).thenReturn(1);
        assertThrows(ServiceException.class, () -> management.remove(new Integer[]{1}));
        verify(mapper, never()).deleteDataSourceByIds(any());
        SourceRequest request = new SourceRequest();
        request.setId(1);
        request.setConfigVersion(1);
        assertThrows(ServiceException.class, () -> management.save(request, true));
        verify(mapper, never()).updateDataSource(any());
    }

    @Test
    void referenceQueryDeduplicatesAndBatchesKeysAndCachesWithinExecution()
    {
        MysqlSourceClient.Session session = mock(MysqlSourceClient.Session.class);
        SourceTable source = table();
        when(session.table(eq(1), isNull(), eq("ref"))).thenReturn(source);
        when(session.config(1)).thenReturn(source());
        List<Integer> sizes = new ArrayList<>();
        when(session.reference(eq(1), eq(source), eq("id"), isNull(), anyList())).thenAnswer(call -> {
            List<Object> keys = call.getArgument(4);
            sizes.add(keys.size());
            List<Map<String, Object>> found = new ArrayList<>();
            for (Object key : keys) found.add(Collections.singletonMap("id", key));
            return found;
        });
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 401; i++) rows.add(Collections.singletonMap("id", i));
        rows.add(Collections.singletonMap("id", new BigDecimal("1.00")));
        TemplateCheck first = reference("first");
        TemplateCheck second = reference("second");
        QualityExecutionContext context = new QualityExecutionContext(Instant.now(), rows);
        List<Map<String, Object>> snapshots = new ReferenceLookupService().load(session, source, Arrays.asList(first, second), rows, context);
        assertEquals(Arrays.asList(200, 200, 1), sizes);
        assertEquals(2, snapshots.size());
        assertEquals(snapshots.get(0).get("readAt"), snapshots.get(1).get("readAt"));
    }

    @Test
    void duplicateAccuracyReferenceFailsInsteadOfPickingArbitraryValue()
    {
        MysqlSourceClient.Session session = mock(MysqlSourceClient.Session.class);
        SourceTable table = table();
        TemplateCheck check = reference("accuracy");
        check.setTemplate("referenceMatch");
        check.getParams().put("sourceKey", "id");
        check.getParams().put("referenceValue", "id");
        when(session.table(eq(1), isNull(), eq("ref"))).thenReturn(table);
        List<Map<String, Object>> duplicates = Arrays.asList(Collections.singletonMap("id", 1), Collections.singletonMap("id", 1));
        when(session.reference(eq(1), eq(table), eq("id"), eq("id"), anyList())).thenReturn(duplicates);
        QualityExecutionContext context = new QualityExecutionContext(Instant.now(), duplicates);
        assertThrows(ServiceException.class, () -> new ReferenceLookupService().load(session, table, Collections.singletonList(check), duplicates, context));
    }

    @Test
    void closedAndExpiredSessionsRejectFurtherUse()
    {
        MysqlSourceClient client = new MysqlSourceClient();
        try (MysqlSourceClient.Session session = client.session(true))
        {
            ReflectionTestUtils.setField(session, "deadline", System.nanoTime() - 1);
            assertThrows(ServiceException.class, session::checkBudget);
            assertThrows(ServiceException.class, () -> session.config(1));
        }
        MysqlSourceClient.Session closed = client.session(true);
        closed.close();
        assertThrows(ServiceException.class, closed::checkBudget);
        assertThrows(ServiceException.class, () -> closed.database(1, null));
    }

    @Test
    void queryFailureClosesResourcesAndSanitizesJdbcException() throws Exception
    {
        MysqlSourceClient client = new MysqlSourceClient();
        javax.sql.DataSource pool = mock(javax.sql.DataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        ReflectionTestUtils.setField(client, "localDataSource", pool);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(new java.sql.SQLException("sensitive-test-jdbc-error"));
        SourceTable table = table();
        table.setDatabaseName("test_db");
        try (MysqlSourceClient.Session session = client.session(false))
        {
            ServiceException error = assertThrows(ServiceException.class, () -> session.scan(null, table, 1));
            assertFalse(error.getMessage().contains("sensitive-test"));
            assertNull(error.getCause());
            verify(statement).setQueryTimeout(10);
            verify(statement).setMaxRows(2);
            verify(statement).close();
        }
        verify(connection).close();
    }

    @Test
    void queriesReuseConnectionWithinSessionAndCloseItOnce() throws Exception
    {
        MysqlSourceClient client = new MysqlSourceClient();
        javax.sql.DataSource pool = mock(javax.sql.DataSource.class);
        java.sql.Connection connection = mock(java.sql.Connection.class);
        java.sql.PreparedStatement statement = mock(java.sql.PreparedStatement.class);
        java.sql.ResultSet result = mock(java.sql.ResultSet.class);
        ReflectionTestUtils.setField(client, "localDataSource", pool);
        when(pool.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        SourceTable table = table();
        table.setDatabaseName("test_db");
        try (MysqlSourceClient.Session session = client.session(false))
        {
            assertTrue(session.scan(null, table, 1).isEmpty());
            assertTrue(session.scan(null, table, 1).isEmpty());
            verify(pool, times(1)).getConnection();
        }
        verify(connection, times(1)).close();
        verify(statement, times(2)).close();
        verify(result, times(2)).close();
    }

    private TemplateCheck reference(String id)
    {
        TemplateCheck check = new TemplateCheck();
        check.setCheckId(id);
        check.setField("id");
        check.setTemplate("referenceExists");
        check.getParams().put("referenceSourceId", "1");
        check.getParams().put("referenceTable", "ref");
        check.getParams().put("referenceKey", "id");
        return check;
    }

    private SourceTable table()
    {
        SourceTable table = new SourceTable();
        table.setTableName("ref");
        SourceTable.Column column = new SourceTable.Column();
        column.setName("id");
        column.setType("int");
        table.getColumns().add(column);
        return table;
    }

    private DataSource source()
    {
        DataSource source = new DataSource();
        source.setName("演示参考连接");
        source.setType("MYSQL");
        source.setHost("127.0.0.1");
        source.setPort(3307);
        source.setDatabaseName("test_db");
        source.setUsername("readonly");
        source.setTlsMode("VERIFY_IDENTITY");
        source.setTimeZone("UTC");
        source.setEnabled(true);
        return source;
    }
}
