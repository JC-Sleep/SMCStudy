package sys.smc.payment.security;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;
import sys.smc.payment.exception.UnauthorizedException;

/**
 * 财务权限 AOP 切面（双保险）
 *
 * 与 FinanceAuthInterceptor 配合：
 *   - Interceptor → 基于 URL 路径 /api/finance/**
 *   - 本切面     → 基于注解 @RequireFinance / @RequireManager（方法级别）
 *
 * 场景：内部 Service 之间直接调用时，Interceptor 不会介入，
 * 此切面仍然生效，防止绕过 HTTP 层直接调用 Service。
 */
@Aspect
@Component
@Slf4j
public class FinanceAuthAspect {

    /**
     * 拦截所有标注 @RequireFinance 的方法
     */
    @Before("@annotation(sys.smc.payment.security.RequireFinance) || " +
            "@within(sys.smc.payment.security.RequireFinance)")
    public void checkFinancePermission(JoinPoint joinPoint) {
        UserContext ctx = UserContextHolder.get();

        if (ctx == null) {
            throw UnauthorizedException.unauthenticated("请先登录");
        }

        if (!ctx.isFinance()) {
            log.warn("[AOP安全] 非财务人员调用财务方法: method={}, userId={}, parentGroupId={}",
                    joinPoint.getSignature().getName(), ctx.getUserId(), ctx.getParentGroupId());
            throw new UnauthorizedException("无权操作：需要财务权限");
        }
    }

    /**
     * 拦截所有标注 @RequireManager 的方法
     */
    @Before("@annotation(sys.smc.payment.security.RequireManager) || " +
            "@within(sys.smc.payment.security.RequireManager)")
    public void checkManagerPermission(JoinPoint joinPoint) {
        UserContext ctx = UserContextHolder.get();

        if (ctx == null) {
            throw UnauthorizedException.unauthenticated("请先登录");
        }

        if (!ctx.isManager()) {
            log.warn("[AOP安全] 非经理调用经理专属方法: method={}, userId={}, parentGroupId={}",
                    joinPoint.getSignature().getName(), ctx.getUserId(), ctx.getParentGroupId());
            throw new UnauthorizedException("无权操作：需要经理权限（parentGroupId=59）");
        }
    }
}
