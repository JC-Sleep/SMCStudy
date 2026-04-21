package sys.smc.coupon.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Security 配置（修复瑕疵②：无统一身份认证）
 *
 * 接口权限设计：
 * ┌─────────────────────────────────────────────────────────┐
 * │ 接口路径                          │ 需要权限              │
 * ├─────────────────────────────────────────────────────────┤
 * │ POST /api/redeem-code/redeem     │ USER（登录用户）       │
 * │ POST /api/redeem-code/unlock/self│ USER                  │
 * │ POST /api/redeem-code/batch/**   │ ADMIN                 │
 * │ POST /api/redeem-code/unlock/admin│ ADMIN/CUSTOMER_SERVICE│
 * │ GET  /api/redeem-code/batch/**   │ ADMIN/CUSTOMER_SERVICE│
 * │ POST /api/seckill/grab           │ USER                  │
 * │ GET  /api/seckill/**             │ 公开（不需要登录）      │
 * │ /druid/**                        │ 公开（IP白名单控制）   │
 * │ /doc.html, /swagger/**           │ 公开（API文档）        │
 * └─────────────────────────────────────────────────────────┘
 *
 * 认证方式：JWT Bearer Token（无状态，不使用Session）
 */
@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)  // 支持 @PreAuthorize 注解
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 禁用CSRF（REST API使用JWT，不需要CSRF保护）
            .csrf().disable()

            // 无状态（不创建Session，完全依赖JWT）
            .sessionManagement()
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()

            // 权限规则
            .authorizeRequests()
                // ── 公开接口（不需要登录）──
                .antMatchers("/druid/**").permitAll()                    // Druid控制台（IP白名单在Druid配置中控制）
                .antMatchers("/doc.html", "/swagger-resources/**",
                             "/v2/api-docs", "/webjars/**").permitAll()  // API文档
                .antMatchers(HttpMethod.GET, "/api/seckill/activities",
                             "/api/seckill/activity/**",
                             "/api/seckill/status/**").permitAll()       // 秒杀活动查询（公开）
                .antMatchers("/api/auth/**").permitAll()                 // 登录/注册接口

                // ── 用户接口（需要登录）──
                .antMatchers(HttpMethod.POST, "/api/redeem-code/redeem").hasRole("USER")
                .antMatchers(HttpMethod.POST, "/api/redeem-code/unlock/self").hasRole("USER")
                .antMatchers(HttpMethod.POST, "/api/seckill/grab").hasRole("USER")
                .antMatchers(HttpMethod.POST, "/api/coupon/claim").hasRole("USER")

                // ── 管理员接口（需要ADMIN角色）──
                .antMatchers(HttpMethod.POST, "/api/redeem-code/batch/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/redeem-code/unlock/admin")
                    .hasAnyRole("ADMIN", "CUSTOMER_SERVICE")
                .antMatchers(HttpMethod.GET, "/api/redeem-code/batch/**")
                    .hasAnyRole("ADMIN", "CUSTOMER_SERVICE")
                .antMatchers("/api/monitor/**").hasRole("ADMIN")
                .antMatchers("/api/coupon/template/**").hasRole("ADMIN")

                // 其他接口需要认证
                .anyRequest().authenticated()
            .and()

            // 未认证 → 返回401 JSON（不重定向到登录页）
            .exceptionHandling()
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType(MediaType.APPLICATION_JSON_UTF8_VALUE);
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", 401);
                    body.put("message", "未授权，请先登录（Authorization: Bearer {token}）");
                    res.getWriter().write(new ObjectMapper().writeValueAsString(body));
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType(MediaType.APPLICATION_JSON_UTF8_VALUE);
                    Map<String, Object> body = new HashMap<>();
                    body.put("code", 403);
                    body.put("message", "权限不足，该操作需要更高权限");
                    res.getWriter().write(new ObjectMapper().writeValueAsString(body));
                })
            .and()

            // 在 UsernamePasswordAuthenticationFilter 之前插入JWT过滤器
            .addFilterBefore(new JwtAuthFilter(jwtUtil),
                             UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}

