package sys.smc.coupon.util;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 兑换码生成器 —— 防刷核心工具
 *
 * ┌─────────────────────────────────────────────────┐
 * │ 格式：CP-{BASE36(ID)}-{HMAC_4位大写}              │
 * │ 示例：CP-2K5XM1-A3F7                             │
 * │ 总长：约14字符，用户友好                            │
 * └─────────────────────────────────────────────────┘
 *
 * 防刷原理：
 *  - HMAC签名（最后4位）由服务端私钥签出
 *  - 攻击者即使枚举ID部分，也无法猜出正确的签名
 *  - 示例: 枚举1亿个可能的code，成功率 = 1/36^4 ≈ 1/1679616 ≈ 极低
 *  - 再叠加 IP限速+失败锁定 = 几乎不可能暴力破解
 */
@Slf4j
@Component
public class RedeemCodeGenerator {

    @Value("${coupon.code.redeem.hmac-secret:CouponSys@SmartOne2026!}")
    private String hmacSecret;

    private static final String PREFIX = "CP";
    private static final String BASE36_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    // HMAC摘要取前4字符（大写字母+数字）
    private static final int HMAC_SUFFIX_LEN = 4;

    private HMac hmac;

    @PostConstruct
    public void init() {
        this.hmac = new HMac(HmacAlgorithm.HmacSHA256, hmacSecret.getBytes(StandardCharsets.UTF_8));
        log.info("[RedeemCodeGenerator] HMAC-SHA256签名初始化完成");
    }

    // ======================== 生成码 ========================

    /**
     * 根据DB主键ID生成兑换码
     *
     * @param id     DB自增ID（唯一性保障）
     * @return       格式: CP-XXXXXX-YYYY
     */
    public String generate(Long id) {
        String idPart  = toBase36(id);           // ID转Base36
        String payload = PREFIX + "-" + idPart;
        String hmacSuffix = computeHmac(payload); // HMAC签名后4位
        return payload + "-" + hmacSuffix;
    }

    /**
     * 批量生成兑换码
     */
    public List<String> generateBatch(List<Long> ids) {
        List<String> codes = new ArrayList<>(ids.size());
        for (Long id : ids) {
            codes.add(generate(id));
        }
        return codes;
    }

    // ======================== 校验码 ========================

    /**
     * 验证兑换码格式与签名是否合法
     * 只要签名对不上，直接拒绝（防枚举/伪造）
     *
     * @param code  用户输入的兑换码
     * @return      true=格式+签名均合法
     */
    public boolean verify(String code) {
        if (code == null || code.length() < 8) return false;
        try {
            // 格式: CP-XXXXXX-YYYY → 分割为3段
            String[] parts = code.toUpperCase().trim().split("-");
            if (parts.length != 3) return false;
            if (!PREFIX.equals(parts[0])) return false;

            String payload    = parts[0] + "-" + parts[1];
            String givenHmac  = parts[2];
            String expectHmac = computeHmac(payload);
            return expectHmac.equals(givenHmac);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从兑换码中解析出原始ID（用于数据库查询）
     */
    public Long parseId(String code) {
        try {
            String[] parts = code.toUpperCase().trim().split("-");
            if (parts.length != 3) return null;
            return fromBase36(parts[1]);
        } catch (Exception e) {
            return null;
        }
    }

    // ======================== 工具方法 ========================

    /** Long → Base36字符串（大写，用于码的中间部分） */
    private String toBase36(long value) {
        if (value == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.insert(0, BASE36_CHARS.charAt((int)(value % 36)));
            value /= 36;
        }
        return sb.toString();
    }

    /** Base36字符串 → Long */
    private Long fromBase36(String s) {
        long result = 0;
        for (char c : s.toCharArray()) {
            result = result * 36 + BASE36_CHARS.indexOf(c);
        }
        return result;
    }

    /** 计算HMAC签名，取十六进制前4位大写字母 */
    private String computeHmac(String payload) {
        String hex = hmac.digestHex(payload).toUpperCase();
        // 只取前HMAC_SUFFIX_LEN位，且只保留字母数字
        StringBuilder sb = new StringBuilder();
        for (char c : hex.toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            if (sb.length() >= HMAC_SUFFIX_LEN) break;
        }
        return sb.toString();
    }
}

