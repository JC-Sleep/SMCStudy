package sys.smc.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 登录响应 DTO
 *
 * ⚠️ 安全注意：
 *   - 不返回 password_hash
 *   - 不返回 groupId（防止客户端用于逻辑判断）
 *   - 只返回 role 描述（供 UI 展示，不用于权限判断）
 *   - 权限判断永远在服务端做，前端的 role 只用来决定"显示哪个菜单"
 */
@Data
@Builder
public class LoginResponse {

    /** JWT Token */
    private String token;

    /** Token 过期时间（给前端做刷新提示用） */
    private Date expireAt;

    /** 用户ID */
    private String userId;

    /** 显示名称 */
    private String realName;

    /**
     * 角色描述（仅供 UI 菜单展示，不用于后端权限判断！）
     * 示例值：FINANCE_MANAGER / FINANCE / CUSTOMER_SERVICE / USER
     */
    private String role;
}
