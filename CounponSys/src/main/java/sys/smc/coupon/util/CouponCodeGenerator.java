package sys.smc.coupon.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 优惠券码生成器
 * 格式: 前缀(2位) + 日期(6位) + 序列号(8位)
 * 示例: CP260325 00000001
 */
@Component
public class CouponCodeGenerator {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String SEQUENCE_KEY_PREFIX = "coupon:seq:";

    @Value("${coupon.code.prefix:CP}")
    private String prefix;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private final Snowflake snowflake = IdUtil.getSnowflake(1, 1);

    /**
     * 生成优惠券码 - 基于日期+Redis序列
     */
    public String generateCode() {
        String dateStr = LocalDate.now().format(DATE_FORMAT);
        String seqKey = SEQUENCE_KEY_PREFIX + dateStr;
        
        // Redis自增获取序列号
        Long seq = redisTemplate.opsForValue().increment(seqKey);
        if (seq != null && seq == 1) {
            // 首次创建,设置过期时间(2天后过期)
            redisTemplate.expire(seqKey, java.time.Duration.ofDays(2));
        }
        
        // 格式化序列号为8位
        String seqStr = String.format("%08d", seq != null ? seq : 1);
        
        return prefix + dateStr + seqStr;
    }

    /**
     * 生成优惠券码 - 基于雪花算法(备用)
     */
    public String generateCodeBySnowflake() {
        return prefix + snowflake.nextIdStr();
    }

    /**
     * 批量生成券码
     */
    public String[] generateBatch(int count) {
        String[] codes = new String[count];
        for (int i = 0; i < count; i++) {
            codes[i] = generateCode();
        }
        return codes;
    }
}

