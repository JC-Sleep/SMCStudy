package sys.smc.coupon.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 核销结果
 */
@Data
@Builder
public class RedeemResult {

    /** 是否成功 */
    private Boolean success;
    
    /** 订单原金额 */
    private BigDecimal originalAmount;
    
    /** 优惠金额 */
    private BigDecimal discountAmount;
    
    /** 实付金额 */
    private BigDecimal actualAmount;
    
    /** 消息 */
    private String message;

    public static RedeemResult success(BigDecimal originalAmount, BigDecimal discountAmount) {
        return RedeemResult.builder()
                .success(true)
                .originalAmount(originalAmount)
                .discountAmount(discountAmount)
                .actualAmount(originalAmount.subtract(discountAmount))
                .message("核销成功")
                .build();
    }

    public static RedeemResult fail(String message) {
        return RedeemResult.builder()
                .success(false)
                .message(message)
                .build();
    }
}

