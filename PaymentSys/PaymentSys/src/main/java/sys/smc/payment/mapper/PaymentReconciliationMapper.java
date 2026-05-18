package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import sys.smc.payment.entity.PaymentReconciliation;

/**
 * 对账记录Mapper
 */
@Mapper
public interface PaymentReconciliationMapper extends BaseMapper<PaymentReconciliation> {
}

