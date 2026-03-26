iupackage sys.smc.payment.config;

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
import sys.smc.payment.exception.OptimisticLockException;
import sys.smc.payment.exception.PaymentException;

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
     * 网关异常
     */
    @ExceptionHandler(GatewayException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<?> handleGatewayException(GatewayException e) {
        log.error("网关异常：{}", e.getMessage(), e);
        return ApiResponse.error("支付网关错误：" + e.getMessage());
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

