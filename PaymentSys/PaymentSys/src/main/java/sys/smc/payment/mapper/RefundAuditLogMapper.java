package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import sys.smc.payment.entity.RefundAuditLog;

import java.util.List;

/**
 * 退款审计日志 Mapper（只 INSERT，不 UPDATE/DELETE）
 */
@Mapper
public interface RefundAuditLogMapper extends BaseMapper<RefundAuditLog> {

    /**
     * 查询某申请的全部审计日志（按时间升序）
     */
    List<RefundAuditLog> selectByApplicationId(@Param("applicationId") Long applicationId);
}

