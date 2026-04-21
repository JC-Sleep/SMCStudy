package sys.smc.coupon.config.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.List;

/**
 * JWT工具类
 *
 * 职责：生成Token、解析Token、校验有效性
 *
 * Token结构：
 *   Header: {alg: HS256}
 *   Payload: {sub: userId, roles: ["USER"/"ADMIN"], iat, exp}
 *   Signature: HMAC-SHA256(secret)
 *
 * 角色说明：
 *   USER           — 普通用户，可调用兑换接口、自助解锁
 *   ADMIN          — 管理员，可批量生成码、客服解锁
 *   CUSTOMER_SERVICE — 客服，可查询批次、客服解锁
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${jwt.secret:CouponSys-JWT-Secret-Key-2026-SmartOne!}")
    private String secretStr;

    @Value("${jwt.expiration-hours:24}")
    private long expirationHours;

    private Key signingKey;

    @PostConstruct
    public void init() {
        // 密钥至少32字节（256bit），不足则填充
        byte[] keyBytes = secretStr.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成JWT Token
     *
     * @param userId 用户ID
     * @param roles  角色列表（["USER"] 或 ["ADMIN", "CUSTOMER_SERVICE"]）
     */
    public String generateToken(String userId, List<String> roles) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expirationHours * 3600 * 1000);
        return Jwts.builder()
                .setSubject(userId)
                .claim("roles", roles)
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * 从Token中提取用户ID
     */
    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从Token中提取角色列表
     */
    @SuppressWarnings("unchecked")
    public List<String> getRoles(String token) {
        return (List<String>) parseClaims(token).get("roles");
    }

    /**
     * 校验Token是否有效（格式正确 + 未过期 + 签名合法）
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("[JWT] Token已过期");
        } catch (JwtException e) {
            log.warn("[JWT] Token无效: {}", e.getMessage());
        }
        return false;
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }
}

