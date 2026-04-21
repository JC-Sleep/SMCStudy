package sys.smc.coupon.util;

import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sys.smc.coupon.exception.CouponException;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 兑换码生成器 —— 防刷核心工具（支持密钥版本化轮换，2026-04-21更新）
 *
 * ┌──────────────────────────────────────────────────────────┐
 * │ 格式（v1）：CP-{BASE36(ID)}-{HMAC_4位}                    │
 * │ 格式（v2+）：CP-{version}-{BASE36(ID)}-{HMAC_4位}         │
 * │ 示例v1：CP-2K5XM1-A3F7                                   │
 * │ 示例v2：CP-V2-2K5XM1-B9X3                                │
 * └──────────────────────────────────────────────────────────┘
 *
 * 安全设计：
 *   1. HMAC签名（最后4位）由服务端私钥签出，攻击者无法伪造
 *   2. 密钥版本化：员工离职后可轮换密钥，旧码用旧密钥验签，新码用新密钥
 *   3. 密钥通过K8s Secret注入，不出现在任何代码/配置文件中
 *   4. 使用安全字符集（去掉0/O/1/I/L等易混字符），减少用户输入错误
 *
 * 易混字符排除：BASE36原始字符集去掉 0,O,I,1,L → 31个安全字符
 */
@Slf4j
@Component
public class RedeemCodeGenerator {

    // ==================== 密钥版本化配置 ====================
    // 支持同时持有多个版本密钥：
    //   active-version：新码用哪个版本生成
    //   secret-v1/v2：各版本密钥（通过K8s Secret注入，不在代码里）
    //
    // 轮换流程：员工离职 → 生成新密钥 → 注入v2 → 切换active-version=v2
    //          旧v1码仍可验签，新码全部用v2 → v1码过期后删除v1配置

    @Value("${coupon.code.hmac.active-version:v1}")
    private String activeVersion;

    @Value("${coupon.code.hmac.secret-v1:${coupon.code.redeem.hmac-secret:CouponSys@SmartOne2026!}}")
    private String secretV1;

    @Value("${coupon.code.hmac.secret-v2:}")
    private String secretV2;

    // 当前码格式版本（v1=旧格式3段，v2+=新格式4段含版本号）
    private static final String PREFIX = "CP";

    /**
     * 安全字符集：去掉 0(零)/O(字母)/1(一)/I(字母)/L(字母) 5个易混字符
     * 原BASE36 = 36个字符，安全字符集 = 31个字符
     * 生成的码更短且用户手输不易出错
     */
    private static final String SAFE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int SAFE_BASE = SAFE_CHARS.length(); // 31进制

    private static final int HMAC_SUFFIX_LEN = 4;

    // 多版本HMAC实例缓存
    private final Map<String, HMac> hmacMap = new HashMap<>();

    @PostConstruct
    public void init() {
        // 初始化v1密钥（必须存在）
        if (secretV1 != null && !secretV1.isEmpty()) {
            hmacMap.put("v1", new HMac(HmacAlgorithm.HmacSHA256,
                    secretV1.getBytes(StandardCharsets.UTF_8)));
            log.info("[RedeemCodeGenerator] HMAC v1 初始化完成");
        }
        // 初始化v2密钥（可选，密钥轮换时配置）
        if (secretV2 != null && !secretV2.isEmpty()) {
            hmacMap.put("v2", new HMac(HmacAlgorithm.HmacSHA256,
                    secretV2.getBytes(StandardCharsets.UTF_8)));
            log.info("[RedeemCodeGenerator] HMAC v2 初始化完成（已切换到新密钥）");
        }
        if (!hmacMap.containsKey(activeVersion)) {
            throw new IllegalStateException(
                    "[RedeemCodeGenerator] 活跃版本 " + activeVersion + " 的密钥未配置！");
        }
        log.info("[RedeemCodeGenerator] 当前活跃版本: {}，已加载版本: {}",
                activeVersion, hmacMap.keySet());
    }

    // ======================== 生成码 ========================

    /**
     * 根据DB主键ID生成兑换码（使用当前活跃版本密钥）
     *
     * v1格式（兼容旧格式）：CP-XXXXXX-YYYY     （3段）
     * v2+格式（带版本号）： CP-V2-XXXXXX-YYYY   （4段）
     *
     * @param id  DB雪花ID（全局唯一，是码唯一性的根基）
     * @return    兑换码字符串
     */
    public String generate(Long id) {
        String idPart = toSafeBase(id);
        if ("v1".equals(activeVersion)) {
            // v1：保持原有格式兼容
            String payload = PREFIX + "-" + idPart;
            return payload + "-" + computeHmac("v1", payload);
        } else {
            // v2+：新格式含版本号（便于密钥轮换后区分）
            String versionTag = activeVersion.toUpperCase(); // "V2"
            String payload = PREFIX + "-" + versionTag + "-" + idPart;
            return payload + "-" + computeHmac(activeVersion, payload);
        }
    }

    /**
     * 批量生成兑换码
     */
    public List<String> generateBatch(List<Long> ids) {
        List<String> codes = new ArrayList<>(ids.size());
        for (Long id : ids) codes.add(generate(id));
        return codes;
    }

    // ======================== 验签码 ========================

    /**
     * 验证兑换码格式与签名是否合法（支持多版本密钥）
     *
     * v1格式（3段）：CP-XXXXXX-YYYY
     *   → 用v1密钥验签
     * v2+格式（4段）：CP-V2-XXXXXX-YYYY
     *   → 用对应版本密钥验签
     *
     * 设计亮点：即使密钥已轮换到v2，历史v1码仍然可以验签（只要v1密钥还在）
     *
     * @param code  用户输入的兑换码（大小写不敏感）
     * @return      true=格式+签名均合法（但不代表码可用，还需查DB）
     */
    public boolean verify(String code) {
        if (code == null || code.length() < 8) return false;
        try {
            String[] parts = code.toUpperCase().trim().split("-");

            if (parts.length == 3) {
                // v1格式：CP-XXXXXX-YYYY
                if (!PREFIX.equals(parts[0])) return false;
                String payload = parts[0] + "-" + parts[1];
                return computeHmac("v1", payload).equals(parts[2]);

            } else if (parts.length == 4) {
                // v2+格式：CP-V2-XXXXXX-YYYY
                if (!PREFIX.equals(parts[0])) return false;
                String version = parts[1].toLowerCase(); // "v2"
                if (!hmacMap.containsKey(version)) {
                    log.warn("[验签] 未知密钥版本: {}，可能是更旧的密钥已被删除", version);
                    return false;
                }
                String payload = parts[0] + "-" + parts[1] + "-" + parts[2];
                return computeHmac(version, payload).equals(parts[3]);

            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从兑换码中解析出原始ID（用于加速数据库精确查询）
     * 有了ID可以直接 SELECT WHERE ID=? 而不用 WHERE CODE=?（走主键更快）
     */
    public Long parseId(String code) {
        try {
            String[] parts = code.toUpperCase().trim().split("-");
            if (parts.length == 3) return fromSafeBase(parts[1]); // v1
            if (parts.length == 4) return fromSafeBase(parts[2]); // v2+
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 工具方法 ========================

    /**
     * Long → 安全字符集编码（31进制，去掉易混字符）
     * 比BASE36更友好，用户手输不会把0误输为O
     */
    private String toSafeBase(long value) {
        if (value == 0) return String.valueOf(SAFE_CHARS.charAt(0));
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.insert(0, SAFE_CHARS.charAt((int)(value % SAFE_BASE)));
            value /= SAFE_BASE;
        }
        return sb.toString();
    }

    private Long fromSafeBase(String s) {
        long result = 0;
        for (char c : s.toCharArray()) {
            int idx = SAFE_CHARS.indexOf(c);
            if (idx < 0) throw new IllegalArgumentException("非法字符: " + c);
            result = result * SAFE_BASE + idx;
        }
        return result;
    }

    /** 计算HMAC签名，取十六进制前4位大写 */
    private String computeHmac(String version, String payload) {
        HMac mac = hmacMap.get(version);
        if (mac == null) {
            throw new CouponException("密钥版本 [" + version + "] 未配置，请检查K8s Secret");
        }
        String hex = mac.digestHex(payload).toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (char c : hex.toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            if (sb.length() >= HMAC_SUFFIX_LEN) break;
        }
        return sb.toString();
    }
}

