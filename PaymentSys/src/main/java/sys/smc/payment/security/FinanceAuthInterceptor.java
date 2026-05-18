package sys.smc.payment.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import sys.smc.payment.exception.UnauthorizedException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 财务权限拦截器（Spring MVC Interceptor 层，在 AuthFilter 之后执行）
 *
 * 拦截 /api/finance/** 下的所有请求：
 *   1. UserContext 为空（未登录）         → 401
 *   2. parentGroupId 不是 345 也不是 59   → 403，记录安全日志
 *   3. 财务身份验证通过                    → 放行
 *
 * 由 WebMvcConfig.addInterceptors() 注册，仅作用于 /api/finance/**
 */
@Component
@Slf4j
public class FinanceAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        UserContext ctx = UserContextHolder.get();

        // 未登录或 Token 无效
        if (ctx == null) {
            log.warn("[安全] 未认证访问财务接口: path={}, ip={}",
                    request.getRequestURI(), request.getRemoteAddr());
            throw UnauthorizedException.unauthenticated("请先登录");
        }

        // 有身份但无财务权限
        if (!ctx.isFinance()) {
            log.warn("[安全] 非财务人员尝试访问财务接口: userId={}, groupId={}, parentGroupId={}, path={}, ip={}",
                    ctx.getUserId(), ctx.getGroupId(), ctx.getParentGroupId(),
                    request.getRequestURI(), ctx.getClientIp());
            throw new UnauthorizedException("无权访问：需要财务权限（parentGroupId=345 或 59）");
        }

        log.debug("[财务] 权限验证通过: userId={}, isManager={}, path={}",
                ctx.getUserId(), ctx.isManager(), request.getRequestURI());
        return true;
    }
}
