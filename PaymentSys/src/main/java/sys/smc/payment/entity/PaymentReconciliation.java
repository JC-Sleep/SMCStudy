package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 对账记录实体
 * 对应表：PAYMENT_RECONCILIATION
 */
@Data
@TableName("PAYMENT_RECONCILIATION")
public class PaymentReconciliation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;

    /**
     * 对账日期
     */
    @TableField("RECONCILIATION_DATE")
    private Date reconciliationDate;

    /**
     * 对账类型（DAILY, MANUAL, AUTO_RETRY）
     */
    @TableField("RECONCILIATION_TYPE")
    private String reconciliationType;

    /**
     * 总交易数
     */
    @TableField("TOTAL_TRANSACTIONS")
    private Integer totalTransactions;

    /**
     * 匹配数量
     */
    @TableField("MATCHED_COUNT")
    private Integer matchedCount;

    /**
     * 不匹配数量
     */
    @TableField("MISMATCH_COUNT")
    private Integer mismatchCount;

    /**
     * 超时数量
     */
    @TableField("TIMEOUT_COUNT")
    private Integer timeoutCount;

    /**
     * 待处理数量
     */
    @TableField("PENDING_COUNT")
    private Integer pendingCount;

    /**
     * 对账状态（IN_PROGRESS, COMPLETED, FAILED）
     */
    @TableField("RECONCILIATION_STATUS")
    private String reconciliationStatus;

    /**
     * 开始时间
     */
    @TableField("START_TIME")
    private Date startTime;

    /**
     * 结束时间
     */
    @TableField("END_TIME")
    private Date endTime;

    /**
     * 不匹配交易ID列表（JSON）
     */
    @TableField("MISMATCH_TRANSACTION_IDS")
    private String mismatchTransactionIds;

    /**
     * 错误信息
     */
    @TableField("ERROR_MESSAGE")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField("CREATE_TIME")
    private Date createTime;

    /**
     * 创建用户
     */
    @TableField("CREATE_USER")
    private String createUser;
}

