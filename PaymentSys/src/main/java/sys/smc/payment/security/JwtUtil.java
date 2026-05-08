package sys.smc.payment.security;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类（基于 Hutool JWT，hutool-all 已在 pom.xml 中）
 *
 * JWT Payload 结构：
 * {
 *   "userId":       "U001",
 *   "username":     "zhang_cai_wu",
 *   "groupId":      12,
 *   "parentGroupId": 345,   // 345=普通财务, 59=财务经理
 *   "exp":          1748600000
 * }
 *
 * 签名算法：HS256（HMAC-SHA256）
 */
@Component
@Slf4j
public class JwtUtil {

    @Value("${jwt.secret:smartone-payment-jwt-secret-key-2026}")
    private String secret;

    @Value("${jwt.expire-hours:24}")
    private int expireHours;

    /**
     * 生成 JWT Token
     */
    public String generateToken(String userId, String username, Integer groupId, Integer parentGroupId) {
        return JWT.create()
                .setPayload("userId", userId)
                .setPayload("username", username)
                .setPayload("groupId", groupId)
                .setPayload("parentGroupId", parentGroupId)
                .setExpiresAt(DateUtil.offsetHour(new Date(), expireHours))
                .setKey(secret.getBytes(StandardCharsets.UTF_8))
                .sign();
    }

    /**
     * 解析 Token，返回 UserContext
     * 验证签名 + 过期时间
     *
     * @throws IllegalArgumentException Token 无效或已过期
     */
    public UserContext parseToken(String token) {
        // 1. 验证签名
        boolean valid = JWTUtil.verify(token, secret.getBytes(StandardCharsets.UTF_8));
        if (!valid) {
            throw new IllegalArgumentException("JWT 签名无效");
        }

        // 2. 解析 payload
        JWT jwt = JWTUtil.parseToken(token);

        // 3. 验证过期时间
        try {
            JWTValidator.of(jwt).validateDate();
        } catch (Exception e) {
            throw new IllegalArgumentException("JWT 已过期");
        }

        // 4. 提取字段
        String userId       = Convert.toStr(jwt.getPayload("userId"));
        String username     = Convert.toStr(jwt.getPayload("username"));
        Integer groupId     = Convert.toInt(jwt.getPayload("groupId"));
        Integer parentGroupId = Convert.toInt(jwt.getPayload("parentGroupId"));

        return UserContext.builder()
                .userId(userId)
                .username(username)
                .groupId(groupId)
                .parentGroupId(parentGroupId)
                .build();
    }
}

