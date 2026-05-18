package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 退款审计日志实体（不可变流水）
 * 对应表：REFUND_AUDIT_LOG
 *
 * ⚠️ 此表只允许 INSERT，严禁 UPDATE / DELETE
 * 每次状态变更（申请/审批/拒绝/执行成功/执行失败）都追加一条记录
 */
@Data
@Builder
@TableName("REFUND_AUDIT_LOG")
public class RefundAuditLog {

    @TableId(value = "ID", type = IdType.INPUT)
    private Long id;

    /** 退款申请ID */
    @TableField("APPLICATION_ID")
    private Long applicationId;

    /** 关联支付交易ID */
    @TableField("TRANSACTION_ID")
    private String transactionId;

    /**
     * 操作动作：
     *   APPLY           用户提交申请
     *   APPROVE         财务批准
     *   REJECT          财务拒绝
     *   EXECUTE_SUCCESS 退款执行成功
     *   EXECUTE_FAILED  退款执行失败
     */
    @TableField("ACTION")
    private String action;

    /** 操作人用户ID */
    @TableField("OPERATOR_USER_ID")
    private String operatorUserId;

    /** 操作人 GroupId */
    @TableField("OPERATOR_GROUP_ID")
    private Integer operatorGroupId;

    /** 操作人 ParentGroupId（用于角色追溯） */
    @TableField("OPERATOR_PARENT_GROUP_ID")
    private Integer operatorParentGroupId;

    /** 操作人 IP（防抵赖） */
    @TableField("OPERATOR_IP")
    private String operatorIp;

    /** 备注（审批意见、失败原因等） */
    @TableField("REMARK")
    private String remark;

    /** 创建时间（此表无 UPDATE_TIME） */
    @TableField("CREATE_TIME")
    private Date createTime;
}
