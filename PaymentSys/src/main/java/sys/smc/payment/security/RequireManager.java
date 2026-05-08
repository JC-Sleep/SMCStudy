package sys.smc.payment.security;

import java.lang.annotation.*;

/**
 * 经理权限注解（parentGroupId = 59）
 *
 * 经理权限比财务权限更高，退款次数无限制。
 * 标注此注解的方法会检查 parentGroupId == 59。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireManager {
}

