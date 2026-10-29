package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import com.ruoyi.dqservice.domain.DataSource;
import com.ruoyi.dqservice.mapper.DataSourceMapper;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceManagementService
{
    @Autowired private DataSourceMapper mapper;
    @Autowired private SourceCredentialCipher cipher;
    @Autowired private SourcePolicy policy;
    @Autowired private MysqlSourceClient client;

    public SourceView get(Integer id)
    {
        DataSource source = mapper.selectDataSourceById(id);
        if (source == null) throw new ServiceException("数据源不存在或已删除");
        return new SourceView(source);
    }

    public List<SourceView> options()
    {
        return mapper.selectDataSourceList(new DataSource()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()) && s.getHost() != null && s.getPasswordCipher() != null)
                .map(SourceView::new).collect(Collectors.toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public int save(SourceRequest request, boolean update)
    {
        DataSource old = update ? mapper.lockById(request.getId()) : null;
        if (update && old == null) throw new ServiceException("数据源不存在或已删除");
        if (update && !Objects.equals(request.getConfigVersion(), old.getConfigVersion()))
            throw new ServiceException("连接配置已变更，请刷新后重新编辑");
        DataSource source = convert(request);
        source.setId(update ? request.getId() : null);
        String password = request.getPassword();
        if (password == null && old != null)
        {
            if (!sameTarget(old, source)) throw new ServiceException("修改连接目标时必须重新填写密码");
            source.setPasswordCipher(old.getPasswordCipher());
        }
        else
        {
            if (password == null || password.isEmpty() || password.length() > 1024)
                throw new ServiceException("请明确填写数据库密码（最多 1024 字符）");
            source.setPasswordCipher(cipher.encrypt(password));
        }
        if (source.getPasswordCipher() == null) throw new ServiceException("旧数据源未配置凭据，请填写密码");
        source.setDel(0);
        source.setConfigVersion(update ? old.getConfigVersion() : 1);
        source.setUpdateTime(new Date());
        source.setCreatTime(update ? old.getCreatTime() : new Date());
        int changed = update ? mapper.updateDataSource(source) : mapper.insertDataSource(source);
        if (changed != 1) throw new ServiceException("连接配置保存失败，请刷新后重试");
        return changed;
    }

    public void testDraft(SourceRequest request)
    {
        DataSource source = convert(request);
        String password = request.getPassword();
        if (password == null && request.getId() != null)
        {
            DataSource saved = mapper.selectDataSourceById(request.getId());
            if (saved == null) throw new ServiceException("数据源不存在");
            // 不允许利用已有凭据对新的目标试连，避免将密码发送到其它主机。
            if (!sameTarget(saved, source))
                throw new ServiceException("修改连接目标后，测试时必须重新填写密码");
            password = cipher.decrypt(saved.getPasswordCipher());
        }
        if (password == null || password.isEmpty()) throw new ServiceException("连接测试需要密码");
        try (MysqlSourceClient.Session session = client.session(true)) { session.testDraft(source, password); }
    }

    public void testSaved(Integer id)
    {
        DataSource source = client.requireSource(id);
        try (MysqlSourceClient.Session session = client.session(true))
        {
            session.testDraft(source, cipher.decrypt(source.getPasswordCipher()));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int remove(Integer[] ids)
    {
        if (ids == null || ids.length == 0) throw new ServiceException("请选择数据源");
        Integer[] sorted = Arrays.stream(ids).distinct().sorted().toArray(Integer[]::new);
        for (Integer id : sorted)
        {
            if (mapper.lockById(id) == null) throw new ServiceException("数据源不存在或已删除");
            if (mapper.countDependencies(id) > 0) throw new ServiceException("数据源被规则使用，请先解除源表及参考库依赖");
        }
        return mapper.deleteDataSourceByIds(sorted);
    }

    private boolean sameTarget(DataSource a, DataSource b)
    {
        return Objects.equals(a.getHost(), b.getHost()) && Objects.equals(a.getPort(), b.getPort())
                && Objects.equals(a.getDatabaseName(), b.getDatabaseName()) && Objects.equals(a.getUsername(), b.getUsername())
                && Objects.equals(a.getTlsMode(), b.getTlsMode());
    }

    private DataSource convert(SourceRequest r)
    {
        DataSource s = new DataSource();
        s.setName(r.getName());
        s.setType(r.getType());
        s.setDescription(r.getDescription());
        s.setHost(r.getHost());
        s.setPort(r.getPort());
        s.setDatabaseName(r.getDatabaseName());
        s.setUsername(r.getUsername());
        s.setTlsMode(r.getTlsMode());
        s.setTimeZone(r.getTimeZone());
        s.setEnabled(r.getEnabled() == null ? true : r.getEnabled());
        policy.validate(s);
        if (s.getDescription() != null && s.getDescription().length() > 500)
            throw new ServiceException("描述最多 500 字符");
        return s;
    }
}
