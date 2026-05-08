package sys.smc.payment.security;

import java.lang.annotation.*;

/**
 * 财务权限注解（AOP 双保险）
 *
 * 用于 Controller 方法或 Service 方法级别的财务权限验证，
 * 与 FinanceAuthInterceptor（路径级）形成双重防护。
 *
 * 用法：
 *   @RequireFinance
 *   public void approveRefund(...) { ... }
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireFinance {
}

