package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 用户组实体
 * 父子层级结构：通过 PARENT_GROUP_ID 决定角色
 *
 * 关键规则：
 *   parentGroupId = 345 → 该用户所属组是"普通财务组的子组" → 普通财务权限
 *   parentGroupId = 59  → 该用户所属组是"经理组的子组"    → 经理权限
 */
@Data
@TableName("SYS_GROUP")
public class SysGroup {

    @TableId(value = "GROUP_ID", type = IdType.INPUT)
    private Integer groupId;

    @TableField("GROUP_NAME")
    private String groupName;

    /** 父组ID（角色判断关键字段） */
    @TableField("PARENT_GROUP_ID")
    private Integer parentGroupId;

    @TableField("DESCRIPTION")
    private String description;

    @TableField("ENABLED")
    private Integer enabled;

    @TableField("CREATE_TIME")
    private Date createTime;

    @TableField("UPDATE_TIME")
    private Date updateTime;
}
