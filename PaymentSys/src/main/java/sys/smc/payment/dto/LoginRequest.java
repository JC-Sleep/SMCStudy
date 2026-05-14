package sys.smc.payment.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 登录请求 DTO
 *
 * ⚠️ 注意：这里只有 username + password，
 *    绝对没有 groupId / parentGroupId 字段！
 *    groupId 从服务端 DB 查，客户端无权指定自己的角色。
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 100, message = "用户名过长")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度不合法")
    private String password;
}

