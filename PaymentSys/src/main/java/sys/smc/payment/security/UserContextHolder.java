package sys.smc.payment.security;

/**
 * 用户上下文持有器（ThreadLocal）
 *
 * ⚠️ 使用规范：
 *   1. AuthFilter.doFilterInternal() 在请求开始时调用 set()
 *   2. AuthFilter.doFilterInternal() 在 finally 块调用 clear()（防止内存泄漏）
 *   3. @Async 方法运行在新线程，ThreadLocal 不会传递，异步方法不可使用此类
 */
public class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {}

    public static void set(UserContext ctx) {
        HOLDER.set(ctx);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 获取当前用户ID，未登录返回 "ANONYMOUS" */
    public static String currentUserId() {
        UserContext ctx = HOLDER.get();
        return ctx != null ? ctx.getUserId() : "ANONYMOUS";
    }
}
