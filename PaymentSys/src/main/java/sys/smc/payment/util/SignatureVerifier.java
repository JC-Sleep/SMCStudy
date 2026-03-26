package sys.smc.payment.util;

import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 签名验证器
 */
@Component
@Slf4j
public class SignatureVerifier {

    @Value("${scb.api.secret}")
    private String apiSecret;

    /**
     * 验证签名
     * @param data 原始数据
     * @param signature 签名
     * @return 是否有效
     */
    public boolean verify(String data, String signature) {
        if (data == null || signature == null) {
            log.warn("数据或签名为空");
            return false;
        }

        try {
            // 使用HMAC-SHA256计算签名
            String calculated = SecureUtil.hmacSha256(apiSecret).digestHex(data);

            boolean valid = calculated.equalsIgnoreCase(signature);

            if (!valid) {
                log.warn("签名验证失败，期望：{}，实际：{}", calculated, signature);
            }

            return valid;

        } catch (Exception e) {
            log.error("签名验证异常", e);
            return false;
        }
    }
}

