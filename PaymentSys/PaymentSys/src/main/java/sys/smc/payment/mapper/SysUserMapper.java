package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import sys.smc.payment.entity.SysUser;

/**
 * 用户 Mapper
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM SYS_USER WHERE USERNAME = #{username}")
    SysUser selectByUsername(@Param("username") String username);

    /** 登录成功：重置失败次数，记录最后登录时间 */
    @Update("UPDATE SYS_USER SET FAILED_ATTEMPTS = 0, LAST_LOGIN_TIME = SYSDATE, " +
            "UPDATE_TIME = SYSDATE WHERE USER_ID = #{userId}")
    void resetFailedAttempts(@Param("userId") String userId);

    /** 登录失败：累加失败次数，达到5次自动锁定 */
    @Update("UPDATE SYS_USER SET " +
            "FAILED_ATTEMPTS = FAILED_ATTEMPTS + 1, " +
            "LOCKED = CASE WHEN FAILED_ATTEMPTS + 1 >= 5 THEN 1 ELSE 0 END, " +
            "LOCK_TIME = CASE WHEN FAILED_ATTEMPTS + 1 >= 5 THEN SYSDATE ELSE LOCK_TIME END, " +
            "UPDATE_TIME = SYSDATE " +
            "WHERE USER_ID = #{userId}")
    void incrementFailedAttempts(@Param("userId") String userId);
}

