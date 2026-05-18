package sys.smc.payment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import sys.smc.payment.entity.SysGroup;

/**
 * 用户组 Mapper
 */
@Mapper
public interface SysGroupMapper extends BaseMapper<SysGroup> {

    /**
     * 根据 GroupId 查 ParentGroupId（角色判断核心查询）
     * 在用户登录时调用，把真实的 parentGroupId 写入 JWT
     */
    @Select("SELECT PARENT_GROUP_ID FROM SYS_GROUP WHERE GROUP_ID = #{groupId} AND ENABLED = 1")
    Integer selectParentGroupId(@Param("groupId") Integer groupId);
}

