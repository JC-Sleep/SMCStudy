package sys.smc.coupon.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券使用记录实体
 */
@Data
@TableName("T_COUPON_USE_RECORD")
public class CouponUseRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 记录ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 券ID
     */
    private Long couponId;

    /**
     * 券码
     */
    private String couponCode;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 使用订单号
     */
    private String orderNo;

    /**
     * 订单原金额
     */
    private BigDecimal originalAmount;

    /**
     * 优惠金额
     */
    private BigDecimal discountAmount;

    /**
     * 实付金额
     */
    private BigDecimal actualAmount;

    /**
     * 使用场景(套餐购买/增值服务等)
     */
    private String useScene;

    /**
     * 使用时间
     */
    private LocalDateTime useTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

