package sys.smc.payment.exception;

/**
 * 未授权异常（403 Forbidden）
 * 用于身份验证通过但权限不足的场景（如非财务人员访问财务接口）
 */
public class UnauthorizedException extends RuntimeException {

    private final int httpStatus;

    /** 403 — 有身份但无权限 */
    public UnauthorizedException(String message) {
        super(message);
        this.httpStatus = 403;
    }

    /** 401 — 未提供身份凭证 */
    public static UnauthorizedException unauthenticated(String message) {
        return new UnauthorizedException(message, 401);
    }

    private UnauthorizedException(String message, int httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}

