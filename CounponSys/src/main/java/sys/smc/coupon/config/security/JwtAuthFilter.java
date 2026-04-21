package sys.smc.coupon.config.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT认证过滤器（每次请求执行一次）
 *
 * 流程：
 * 1. 从 Authorization Header 提取 "Bearer {token}"
 * 2. JwtUtil验证token有效性
 * 3. 解析userId和roles，注入到 SecurityContext
 * 4. 后续 Controller/Service 可通过 SecurityContext 获取当前用户
 *
 * 注意：token失效不直接拒绝请求，而是让SecurityConfig的权限规则处理
 *      （公开接口不需要token，受保护接口会返回401）
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (token != null && jwtUtil.isValid(token)) {
            try {
                String userId = jwtUtil.getUserId(token);
                List<String> roles = jwtUtil.getRoles(token);

                // 将角色转为 Spring Security 的 GrantedAuthority（加 ROLE_ 前缀）
                List<SimpleGrantedAuthority> authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .collect(Collectors.toList());

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);

                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("[JWT] 认证成功 userId={} roles={}", userId, roles);

            } catch (Exception e) {
                log.warn("[JWT] 解析失败: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 从 Authorization Header 提取 Bearer Token
     * 支持格式：Authorization: Bearer eyJhbGc...
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}

