package sys.smc.payment.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户上下文
 * 由 AuthFilter 从 JWT 解析后放入 UserContextHolder
 *
 * 角色判断规则（基于 parentGroupId）：
 *   parentGroupId = 345  → 普通财务（最多3次部分退款）
 *   parentGroupId = 59   → 财务经理（退款次数无限制）
 *   其他                  → 无财务权限
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserContext {

    /** 用户ID */
    private String userId;

    /** 用户名 */
    private String username;

    /** 当前所属 GroupId */
    private Integer groupId;

    /** 父 GroupId（决定角色） */
    private Integer parentGroupId;

    /** 客户端 IP（用于审计） */
    private String clientIp;

    // ───────── 角色判断 ─────────

    /** 是否是财务人员（普通财务 或 财务经理） */
    public boolean isFinance() {
        return parentGroupId != null
                && (parentGroupId == 345 || parentGroupId == 59);
    }

    /** 是否是财务经理（退款次数无限制） */
    public boolean isManager() {
        return parentGroupId != null && parentGroupId == 59;
    }

    /** 是否是普通财务（退款次数最多3次） */
    public boolean isRegularFinance() {
        return parentGroupId != null && parentGroupId == 345;
    }
}
