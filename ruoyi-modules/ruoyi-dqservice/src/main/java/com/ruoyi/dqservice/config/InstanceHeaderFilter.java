package com.ruoyi.dqservice.config;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 多副本部署时输出实例标识，便于演示请求分流与故障摘除。
 *
 * <p>值只来自服务端配置（dq.instance.id），不接受请求方传入；未配置时不输出该响应头。
 * 不输出容器地址、凭据或环境变量。</p>
 *
 * @author ruoyi
 */
@Component
public class InstanceHeaderFilter extends OncePerRequestFilter
{
    /** 实例标识响应头名称 */
    public static final String INSTANCE_HEADER = "X-DQ-Instance";

    @Value("${dq.instance.id:}")
    private String instanceId = "";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException
    {
        if (instanceId != null && !instanceId.trim().isEmpty())
        {
            // 覆盖请求方可能伪造的同名头，只暴露实例标识
            response.setHeader(INSTANCE_HEADER, instanceId.trim());
        }
        chain.doFilter(request, response);
    }
}
