package sys.smc.payment.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import sys.smc.payment.security.FinanceAuthInterceptor;

/**
 * Spring MVC 配置
 * 注册财务权限拦截器，仅作用于 /api/finance/** 路径
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private FinanceAuthInterceptor financeAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(financeAuthInterceptor)
                // 只拦截财务后台接口
                .addPathPatterns("/api/finance/**")
                // 如有登录接口在此路径下，可用 excludePathPatterns 排除
                ;
    }
}
