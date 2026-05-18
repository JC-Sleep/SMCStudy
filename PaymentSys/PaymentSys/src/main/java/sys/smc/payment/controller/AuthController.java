package sys.smc.payment.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.dto.LoginRequest;
import sys.smc.payment.dto.LoginResponse;
import sys.smc.payment.security.AuthFilter;
import sys.smc.payment.service.AuthService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * 认证控制器
 *
 * 登录接口故意只接收 username + password，
 * 拒绝接收任何 groupId / role 字段。
 * 角色由服务端从 DB 查，写进 JWT。
 */
@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * 登录
     *
     * POST /api/auth/login
     * Body: { "username": "zhang_caiwu", "password": "xxx" }
     *       ← 注意：没有 groupId 字段！
     *
     * 成功返回：
     * {
     *   "token": "eyJ...",         ← API 调用用这个 Bearer Token
     *   "expireAt": "...",
     *   "role": "FINANCE",         ← 仅供 UI 决定显示哪个菜单，不用于权限判断
     *   "realName": "张财务"
     * }
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Validated @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpSession session) {
        String clientIp = getClientIp(httpRequest);
        LoginResponse response = authService.login(request, clientIp);

        // Web 模式：同时将 Token 存入 Session（浏览器用 Cookie 自动携带）
        // API 模式：前端用返回的 token 放 Authorization Header
        session.setAttribute(AuthFilter.SESSION_JWT_KEY, response.getToken());

        log.info("[登录] 成功：userId={}，role={}，ip={}", response.getUserId(), response.getRole(), clientIp);
        return ApiResponse.success(response);
    }

    /**
     * 登出（清除 Session）
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpSession session) {
        String token = (String) session.getAttribute(AuthFilter.SESSION_JWT_KEY);
        if (StringUtils.hasText(token)) {
            session.removeAttribute(AuthFilter.SESSION_JWT_KEY);
            session.invalidate();
        }
        return ApiResponse.success(null, "已登出");
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xForwardedFor)) return xForwardedFor.split(",")[0].trim();
        String xRealIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(xRealIp)) return xRealIp.trim();
        return request.getRemoteAddr();
    }
}

