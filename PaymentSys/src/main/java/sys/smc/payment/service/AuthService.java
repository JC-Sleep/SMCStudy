package sys.smc.payment.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.crypto.digest.BCrypt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sys.smc.payment.dto.LoginRequest;
import sys.smc.payment.dto.LoginResponse;
import sys.smc.payment.entity.SysUser;
import sys.smc.payment.exception.UnauthorizedException;
import sys.smc.payment.mapper.SysGroupMapper;
import sys.smc.payment.mapper.SysUserMapper;
import sys.smc.payment.security.JwtUtil;

import java.util.Date;

/**
 * 认证服务
 *
 * ═══════════════════════════════════════════════════════════
 * ⭐ 核心安全原则：groupId/parentGroupId 从 DB 查，绝不信任客户端
 * ═══════════════════════════════════════════════════════════
 *
 * 登录流程：
 *   1. 客户端只传 username + password（无 groupId）
 *   2. 服务端根据 username 查 SYS_USER（含 GROUP_ID）
 *   3. BCrypt 验证密码
 *   4. 用 user.GROUP_ID 查 SYS_GROUP 得到 PARENT_GROUP_ID
 *   5. 用真实的 groupId + parentGroupId 生成 JWT
 *   6. JWT 用 HS256 签名，前端无法篡改 payload
 *
 * 为什么这样就安全了？
 *   - 客户端不传 groupId → 无法注入恶意角色
 *   - JWT payload 被签名保护 → 无法篡改 parentGroupId
 *   - 服务端验证签名 → 签名无效的 Token 直接拒绝
 *   - 关键操作二次验 DB → 即使 Token 盗用也有保障
 */
@Service
@Slf4j
public class AuthService {

    /** 连续失败多少次后锁定账户 */
    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysGroupMapper sysGroupMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${jwt.expire-hours:24}")
    private int expireHours;

    /**
     * 登录
     *
     * @param request 登录请求（只有 username + password，无 groupId！）
     * @param clientIp 客户端IP（审计用）
     * @return JWT Token 和用户信息
     * @throws UnauthorizedException 用户名/密码错误、账户被锁定/禁用
     */
    public LoginResponse login(LoginRequest request, String clientIp) {

        // ── Step 1：根据用户名查用户（不接受客户端传 groupId） ─────────────
        SysUser user = sysUserMapper.selectByUsername(request.getUsername());

        if (user == null) {
            // 用户不存在：故意不说"用户不存在"，防止枚举攻击
            log.warn("[登录] 用户名不存在：{}，IP={}", request.getUsername(), clientIp);
            throw UnauthorizedException.unauthenticated("用户名或密码错误");
        }

        // ── Step 2：检查账户状态 ────────────────────────────────────────────
        if (user.getEnabled() == null || user.getEnabled() == 0) {
            log.warn("[登录] 账户已禁用：userId={}，IP={}", user.getUserId(), clientIp);
            throw UnauthorizedException.unauthenticated("账户已被禁用，请联系管理员");
        }

        if (user.getLocked() != null && user.getLocked() == 1) {
            log.warn("[登录] 账户已锁定（连续{}次失败）：userId={}，IP={}",
                    MAX_FAILED_ATTEMPTS, user.getUserId(), clientIp);
            throw UnauthorizedException.unauthenticated(
                    "账户已被锁定（连续登录失败超过" + MAX_FAILED_ATTEMPTS + "次），请联系管理员解锁");
        }

        // ── Step 3：BCrypt 验证密码 ─────────────────────────────────────────
        // ⚠️ 必须用 BCrypt 对比，不能直接比较明文！
        boolean passwordMatch = BCrypt.checkpw(request.getPassword(), user.getPasswordHash());

        if (!passwordMatch) {
            // 登录失败：累加失败次数，达到上限自动锁定
            sysUserMapper.incrementFailedAttempts(user.getUserId());
            int remaining = MAX_FAILED_ATTEMPTS - (user.getFailedAttempts() + 1);
            log.warn("[登录] 密码错误：userId={}，剩余尝试次数={}，IP={}",
                    user.getUserId(), Math.max(remaining, 0), clientIp);

            String message = remaining > 0
                    ? "密码错误，还有 " + remaining + " 次机会"
                    : "密码错误次数过多，账户已被锁定";
            throw UnauthorizedException.unauthenticated(message);
        }

        // ── Step 4：从 DB 查该用户的真实 parentGroupId ──────────────────────
        //
        // ⭐⭐⭐ 这是整个权限系统最关键的一行 ⭐⭐⭐
        //
        // 流程：user.groupId（从DB来）→ 查 SYS_GROUP → 得到 PARENT_GROUP_ID
        //
        // 这里绝对不用：request.getGroupId()（客户端传来的，不可信）
        //
        Integer parentGroupId = sysGroupMapper.selectParentGroupId(user.getGroupId());
        if (parentGroupId == null) {
            log.error("[登录] 用户组配置异常，找不到父GroupId：userId={}，groupId={}",
                    user.getUserId(), user.getGroupId());
            throw new RuntimeException("系统配置异常，请联系管理员");
        }

        // ── Step 5：重置失败次数，记录登录时间 ──────────────────────────────
        sysUserMapper.resetFailedAttempts(user.getUserId());
        log.info("[登录] 成功：userId={}，groupId={}，parentGroupId={}，IP={}",
                user.getUserId(), user.getGroupId(), parentGroupId, clientIp);

        // ── Step 6：用服务端查到的 groupId/parentGroupId 生成 JWT ───────────
        //
        // JWT payload 里的 parentGroupId 是从 DB 查的真实值
        // 非财务人员的 parentGroupId（如 200）不等于 345 或 59
        // → FinanceAuthInterceptor 会拒绝他访问财务接口
        //
        String token = jwtUtil.generateToken(
                user.getUserId(),
                user.getRealName() != null ? user.getRealName() : user.getUsername(),
                user.getGroupId(),
                parentGroupId   // ← DB 查来的，不是客户端提供的！
        );

        // ── Step 7：构建响应（不含 groupId，不含密码哈希）──────────────────
        return LoginResponse.builder()
                .token(token)
                .expireAt(DateUtil.offsetHour(new Date(), expireHours))
                .userId(user.getUserId())
                .realName(user.getRealName())
                .role(resolveRoleLabel(parentGroupId))
                .build();
    }

    /**
     * ═══════════════════════════════════════════════════════════
     * ⭐ 敏感操作二次验证（Token 可能被盗用，此方法从 DB 重新确认权限）
     * ═══════════════════════════════════════════════════════════
     *
     * 场景：财务 Token 有效期24小时，期间若该财务被撤权，
     *       旧 Token 虽签名还有效，但 DB 里 group 已更改。
     *       退款审批等高风险操作调用此方法，强制 DB 二次确认。
     *
     * @param userId       JWT 里的 userId
     * @param requiredRole 要求的角色（"FINANCE" 或 "MANAGER"）
     * @throws UnauthorizedException DB 里已无该权限
     */
    public void verifyRoleFromDB(String userId, String requiredRole) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getEnabled() == 0 || user.getLocked() == 1) {
            log.warn("[二次验权] 用户状态异常：userId={}", userId);
            throw UnauthorizedException.unauthenticated("账户状态异常，请重新登录");
        }

        Integer parentGroupId = sysGroupMapper.selectParentGroupId(user.getGroupId());
        boolean isFinance  = (parentGroupId != null && (parentGroupId == 345 || parentGroupId == 59));
        boolean isManager  = (parentGroupId != null && parentGroupId == 59);

        if ("MANAGER".equals(requiredRole) && !isManager) {
            log.warn("[二次验权] 非经理访问经理专属操作：userId={}，实际parentGroupId={}", userId, parentGroupId);
            throw new UnauthorizedException("权限不足：需要经理权限（实时验证）");
        }
        if ("FINANCE".equals(requiredRole) && !isFinance) {
            log.warn("[二次验权] 非财务访问财务操作：userId={}，实际parentGroupId={}", userId, parentGroupId);
            throw new UnauthorizedException("权限不足：需要财务权限（实时验证）");
        }
    }

    // ───────── 工具方法 ─────────

    /** 角色标签（仅供 UI 展示菜单，不用于后端权限判断） */
    private String resolveRoleLabel(Integer parentGroupId) {
        if (parentGroupId == null) return "USER";
        if (parentGroupId == 59)  return "FINANCE_MANAGER";
        if (parentGroupId == 345) return "FINANCE";
        if (parentGroupId == 100) return "CUSTOMER_SERVICE";
        return "USER";
    }

    /**
     * 生成 BCrypt 密码哈希（管理员创建用户时调用）
     * 示例：hashPassword("myPassword123") → "$2a$10$..."
     */
    public String hashPassword(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
    }
}



