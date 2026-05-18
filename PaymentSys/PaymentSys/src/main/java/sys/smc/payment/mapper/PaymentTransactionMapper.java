package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sys.smc.payment.entity.PaymentTransaction;

/**
 * 支付交易Mapper
 */
@Mapper
public interface PaymentTransactionMapper extends BaseMapper<PaymentTransaction> {

    /**
     * 根据幂等键查询交易
     * @param idempotencyKey 幂等键
     * @return 支付交易
     */
    PaymentTransaction selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * 根据银行交易ID查询
     * @param gatewayTransactionId 银行交易ID
     * @return 支付交易
     */
    PaymentTransaction selectByGatewayTransactionId(@Param("gatewayTransactionId") String gatewayTransactionId);

    /**
     * 根据订单编号查询
     * @param orderReference 订单编号
     * @return 支付交易
     */
    PaymentTransaction selectByOrderReference(@Param("orderReference") String orderReference);
}

