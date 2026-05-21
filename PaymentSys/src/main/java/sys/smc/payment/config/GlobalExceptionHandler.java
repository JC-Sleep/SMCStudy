package sys.smc.payment.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import sys.smc.payment.dto.ApiResponse;
import sys.smc.payment.exception.GatewayException;
import sys.smc.payment.exception.IllegalStateTransitionException;
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.exception.PaymentException;
import sys.smc.payment.exception.ServiceUnavailableException;
import sys.smc.payment.exception.UnauthorizedException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 支付异常
     */
    @ExceptionHandler(PaymentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handlePaymentException(PaymentException e) {
        log.error("支付异常：{}", e.getMessage(), e);
        return ApiResponse.error(e.getMessage());
    }

    /**
     * 未授权异常（401 未登录 / 403 无权限）
     */
    @ExceptionHandler(UnauthorizedException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleUnauthorizedException(
            UnauthorizedException e) {
        log.warn("权限异常：{}", e.getMessage());
        ApiResponse<?> body = ApiResponse.error(e.getHttpStatus(), e.getMessage());
        return org.springframework.http.ResponseEntity
                .status(e.getHttpStatus())
                .body(body);
    }

    /**
     * 网关异常
     */
    @ExceptionHandler(GatewayException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleGatewayException(GatewayException e) {
        log.error("网关异常：{}", e.getMessage(), e);
        return ApiResponse.error("支付网关错误：" + e.getMessage());
    }

    /**
     * 渠道熔断异常（HTTP 503）
     * 当 GatewayHealthMonitor 检测到渠道连续失败并熔断时抛出
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<?>> handleServiceUnavailableException(
            ServiceUnavailableException e) {
        log.warn("[熔断拦截] 渠道 {} 暂时不可用: {}", e.getChannelCode(), e.getMessage());
        ApiResponse<?> body = ApiResponse.error(
                "当前支付渠道暂时不可用，请稍后重试或选择其他支付方式");
        return org.springframework.http.ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    /**
     * 非法支付状态转换异常（HTTP 409 Conflict）
     * 当支付状态机拒绝非法状态转换时抛出（如 TIMEOUT→SUCCESS 绕过对账）
     * 此异常是业务逻辑拒绝，不应映射为 500 系统错误
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleIllegalStateTransitionException(IllegalStateTransitionException e) {
        log.error("[状态机] 非法状态转换被拒绝: {} → {} 原因: {}",
                e.getFromStatus(), e.getToStatus(), e.getMessage());
        return ApiResponse.error("支付状态异常，操作被拒绝：" + e.getMessage());
    }

    /**
     * 乐观锁异常
     */
    @ExceptionHandler(OptimisticLockException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleOptimisticLockException(OptimisticLockException e) {
        log.error("乐观锁冲突：{}", e.getMessage(), e);
        return ApiResponse.error("系统繁忙，请稍后重试");
    }

    /**
     * 参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        log.error("参数校验失败：{}", message);
        return ApiResponse.error("参数错误：" + message);
    }

    /**
     * 绑定异常
     */
    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
        log.error("参数绑定失败：{}", message);
        return ApiResponse.error("参数错误：" + message);
    }

    /**
     * 通用异常
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.error("系统错误，请联系管理员");
    }
}

