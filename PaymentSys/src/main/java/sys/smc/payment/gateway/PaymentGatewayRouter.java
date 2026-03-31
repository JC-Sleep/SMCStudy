package sys.smc.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.exception.GatewayException;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付网关路由器
 * 基于策略模式，根据支付渠道自动选择对应的网关实现
 */
@Component
@Slf4j
public class PaymentGatewayRouter {

    /**
     * 所有网关实现（Spring自动注入）
     */
    @Autowired
    private List<PaymentGateway> gateways;

    /**
     * 渠道 -> 网关映射
     */
    private final Map<PaymentChannel, PaymentGateway> gatewayMap = new ConcurrentHashMap<>();

    /**
     * 支付方式 -> 可用网关列表映射
     */
    private final Map<String, List<PaymentGateway>> paymentMethodGatewayMap = new ConcurrentHashMap<>();

    /**
     * 初始化路由表
     */
    @PostConstruct
    public void init() {
        log.info("初始化支付网关路由器...");
        
        for (PaymentGateway gateway : gateways) {
            PaymentChannel channel = gateway.getChannel();
            
            if (gateway.isAvailable()) {
                gatewayMap.put(channel, gateway);
                log.info("注册支付网关: {} - {}", channel.getCode(), gateway.getChannelName());
            } else {
                log.warn("支付网关不可用（配置缺失）: {}", channel.getCode());
            }
        }
        
        log.info("支付网关路由器初始化完成，已注册 {} 个网关", gatewayMap.size());
    }

    /**
     * 根据渠道获取网关
     * @param channel 支付渠道
     * @return 网关实例
     */
    public PaymentGateway getGateway(PaymentChannel channel) {
        PaymentGateway gateway = gatewayMap.get(channel);
        
        if (gateway == null) {
            throw new GatewayException("不支持的支付渠道: " + channel.getName());
        }
        
        if (!gateway.isAvailable()) {
            throw new GatewayException("支付渠道暂不可用: " + channel.getName());
        }
        
        return gateway;
    }

    /**
     * 根据渠道代码获取网关
     * @param channelCode 渠道代码
     * @return 网关实例
     */
    public PaymentGateway getGateway(String channelCode) {
        PaymentChannel channel = PaymentChannel.fromCode(channelCode);
        return getGateway(channel);
    }

    /**
     * 根据支付方式智能选择网关
     * @param paymentMethod 支付方式（如：CARD, ALIPAY, WECHAT等）
     * @return 最优网关
     */
    public PaymentGateway selectGateway(String paymentMethod) {
        List<PaymentGateway> availableGateways = new ArrayList<>();
        
        for (PaymentGateway gateway : gatewayMap.values()) {
            if (gateway.isAvailable() && gateway.supportsPaymentMethod(paymentMethod)) {
                availableGateways.add(gateway);
            }
        }
        
        if (availableGateways.isEmpty()) {
            throw new GatewayException("没有支持该支付方式的可用网关: " + paymentMethod);
        }
        
        // 按优先级排序，选择优先级最高的
        availableGateways.sort(Comparator.comparingInt(PaymentGateway::getPriority));
        
        PaymentGateway selected = availableGateways.get(0);
        log.debug("为支付方式 {} 选择网关: {}", paymentMethod, selected.getChannelName());
        
        return selected;
    }

    /**
     * 根据回调路径获取网关
     * @param callbackPath 回调路径标识
     * @return 网关实例
     */
    public PaymentGateway getGatewayByCallbackPath(String callbackPath) {
        PaymentChannel channel = PaymentChannel.fromCallbackPath(callbackPath);
        return getGateway(channel);
    }

    /**
     * 获取所有可用网关
     * @return 网关列表
     */
    public List<PaymentGateway> getAvailableGateways() {
        List<PaymentGateway> available = new ArrayList<>();
        for (PaymentGateway gateway : gatewayMap.values()) {
            if (gateway.isAvailable()) {
                available.add(gateway);
            }
        }
        return available;
    }

    /**
     * 检查渠道是否可用
     * @param channel 支付渠道
     * @return 是否可用
     */
    public boolean isChannelAvailable(PaymentChannel channel) {
        PaymentGateway gateway = gatewayMap.get(channel);
        return gateway != null && gateway.isAvailable();
    }

    /**
     * 获取所有已注册渠道
     * @return 渠道列表
     */
    public Set<PaymentChannel> getRegisteredChannels() {
        return gatewayMap.keySet();
    }
}

