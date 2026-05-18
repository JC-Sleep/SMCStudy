package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sys.smc.payment.entity.RefundApplication;

import java.math.BigDecimal;

/**
 * 退款申请 Mapper
 */
@Mapper
public interface RefundApplicationMapper extends BaseMapper<RefundApplication> {

    /**
     * 查询同一笔交易的有效申请数（排除 REJECTED 和 FAILED）
     * 用于判断是否超过退款次数上限
     */
    int countActiveByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 查询同一笔交易的所有有效申请金额之和（排除 REJECTED 和 FAILED）
     * 用于判断是否超过原始金额
     */
    BigDecimal sumActiveAmountByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 检查同一笔交易是否存在进行中的申请（PENDING_REVIEW / APPROVED / EXECUTING）
     * 用于防止同时提交多笔相同退款
     */
    int countInProgressByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 按状态分页查询（财务列表页）
     * status 为 null 时查所有状态
     */
    IPage<RefundApplication> selectPageByStatus(Page<RefundApplication> page,
                                                @Param("status") String status);
}
