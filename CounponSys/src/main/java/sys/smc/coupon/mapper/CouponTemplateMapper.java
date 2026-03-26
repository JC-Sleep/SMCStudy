package sys.smc.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import sys.smc.coupon.entity.CouponTemplate;

/**
 * 优惠券模板Mapper
 */
@Mapper
public interface CouponTemplateMapper extends BaseMapper<CouponTemplate> {

    /**
     * 扣减库存(乐观锁)
     */
    @Update("UPDATE T_COUPON_TEMPLATE SET ISSUED_QUANTITY = ISSUED_QUANTITY + #{count}, " +
            "UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{templateId} AND ISSUED_QUANTITY + #{count} <= TOTAL_QUANTITY")
    int deductStock(@Param("templateId") Long templateId, @Param("count") int count);

    /**
     * 回滚库存
     */
    @Update("UPDATE T_COUPON_TEMPLATE SET ISSUED_QUANTITY = ISSUED_QUANTITY - #{count}, " +
            "UPDATE_TIME = SYSTIMESTAMP " +
            "WHERE ID = #{templateId} AND ISSUED_QUANTITY >= #{count}")
    int rollbackStock(@Param("templateId") Long templateId, @Param("count") int count);
}

