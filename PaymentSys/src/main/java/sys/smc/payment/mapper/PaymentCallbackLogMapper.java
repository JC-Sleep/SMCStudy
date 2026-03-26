package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import sys.smc.payment.entity.PaymentCallbackLog;

/**
 * 回调日志Mapper
 */
@Mapper
public interface PaymentCallbackLogMapper extends BaseMapper<PaymentCallbackLog> {
}

