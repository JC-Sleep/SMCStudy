package sys.smc.coupon.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 秒杀抢购结果
 */
@Data
@Builder
public class SeckillGrabResult {

    /** 是否成功 */
    private Boolean success;
    
    /** 订单号 */
    private String orderNo;
    
    /** 消息 */
    private String message;
    
    /** 券码(成功时返回) */
    private String couponCode;
    
    /** 消耗积分 */
    private Integer pointsUsed;
    
    /** 消耗金额 */
    private BigDecimal amountPaid;

    public static SeckillGrabResult success(String orderNo, String couponCode) {
        return SeckillGrabResult.builder()
                .success(true)
                .orderNo(orderNo)
                .couponCode(couponCode)
                .message("抢购成功")
                .build();
    }

    public static SeckillGrabResult fail(String message) {
        return SeckillGrabResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}

