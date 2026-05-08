package sys.smc.payment.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

/**
 * 认证过滤器（Servlet Filter 层，最先执行）
 *
 * 支持两种身份验证方式：
 *   方式1：JWT Bearer Token（推荐，API 调用使用）
 *     Header: Authorization: Bearer <token>
 *
 *   方式2：Session（Web 浏览器使用）
 *     登录成功后 JWT 存储在 Session 属性 USER_JWT，
 *     浏览器通过 Cookie 自动携带 Session ID
 *
 * 注意：此 Filter 只负责解析身份，不拒绝请求。
 * 拒绝逻辑由 FinanceAuthInterceptor 按路径决定。
 */
@Component
@Order(1)
@Slf4j
public class AuthFilter extends OncePerRequestFilter {

    /** Session 中存 JWT 的 key */
    public static final String SESSION_JWT_KEY = "USER_JWT";

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);

            if (StringUtils.hasText(token)) {
                try {
                    UserContext ctx = jwtUtil.parseToken(token);
                    ctx.setClientIp(getClientIp(request));
                    UserContextHolder.set(ctx);
                    log.debug("身份验证成功: userId={}, parentGroupId={}, path={}",
                            ctx.getUserId(), ctx.getParentGroupId(), request.getRequestURI());
                } catch (Exception e) {
                    // Token 无效/过期 → 清空上下文，后续拦截器按需拒绝
                    log.warn("JWT 解析失败: {}, path={}", e.getMessage(), request.getRequestURI());
                    UserContextHolder.clear();
                }
            }

            chain.doFilter(request, response);

        } finally {
            // ⚠️ 必须在 finally 清理，防止线程复用时数据污染
            UserContextHolder.clear();
        }
    }

    /**
     * 解析 Token：优先 Authorization Header，其次 Session
     */
    private String resolveToken(HttpServletRequest request) {
        // 方式1：Authorization: Bearer <token>
        String authHeader = request.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }

        // 方式2：Session（不创建新 Session，false）
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object sessionJwt = session.getAttribute(SESSION_JWT_KEY);
            if (sessionJwt != null) {
                return sessionJwt.toString();
            }
        }

        return null;
    }

    /**
     * 获取真实客户端 IP（考虑 Nginx 反代）
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}

