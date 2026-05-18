package sys.smc.payment.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 系统用户实体
 *
 * ⚠️ 安全设计要点：
 *   1. PASSWORD_HASH 用 BCrypt 哈希，绝不存明文
 *   2. GROUP_ID 是关联到 SYS_GROUP 的外键，登录时从 DB 查
 *      客户端传来的任何 groupId 都不可信，一律忽略
 *   3. LOCKED/FAILED_ATTEMPTS 防止暴力破解
 */
@Data
@TableName("SYS_USER")
public class SysUser {

    @TableId(value = "USER_ID", type = IdType.INPUT)
    private String userId;

    @TableField("USERNAME")
    private String username;

    /** BCrypt 哈希值（永远不返回给前端！） */
    @TableField("PASSWORD_HASH")
    private String passwordHash;

    /**
     * 所属组ID（关键！）
     * 通过 SYS_GROUP 表查 PARENT_GROUP_ID 判断角色
     * 这个值来自 DB，不接受客户端传入
     */
    @TableField("GROUP_ID")
    private Integer groupId;

    @TableField("REAL_NAME")
    private String realName;

    @TableField("EMAIL")
    private String email;

    /** 0=禁用, 1=启用 */
    @TableField("ENABLED")
    private Integer enabled;

    /** 0=正常, 1=锁定（连续失败5次） */
    @TableField("LOCKED")
    private Integer locked;

    /** 连续登录失败次数（达到5次自动锁定） */
    @TableField("FAILED_ATTEMPTS")
    private Integer failedAttempts;

    @TableField("LAST_LOGIN_TIME")
    private Date lastLoginTime;

    @TableField("LOCK_TIME")
    private Date lockTime;

    @TableField("CREATE_TIME")
    private Date createTime;

    @TableField("UPDATE_TIME")
    private Date updateTime;
}
