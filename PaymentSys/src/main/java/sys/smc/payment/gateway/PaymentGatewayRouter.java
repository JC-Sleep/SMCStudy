package sys.smc.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sys.smc.payment.enums.PaymentChannel;
import sys.smc.payment.exception.GatewayException;
import sys.smc.payment.exception.ServiceUnavailableException;

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
     * 网关健康监控 / 熔断器
     * 在 selectGateway() 路由新支付请求时进行熔断检查（拦截因渠道故障导致的用户请求）。
     * getGateway() / getGatewayByCallbackPath() 故意不检查熔断，因为：
     *   - 回调处理（verifyCallback/parseCallbackData）是本地操作，不发网络请求
     *   - 对账查询是后台恢复作业，不应被熔断阻断
     */
    @Autowired
    private GatewayHealthMonitor healthMonitor;

    /**
     * 渠道 -> 网关映射
     */
    private final Map<PaymentChannel, PaymentGateway> gatewayMap = new ConcurrentHashMap<>();

    /**
     * 支付方式 -> 可用网关列表映射
     * @deprecated 已删除：此 Map 从未被填充也从未被使用，是死代码。
     *             路由逻辑统一在 selectGateway() 实时计算。
     */
    // private final Map<String, List<PaymentGateway>> paymentMethodGatewayMap = new ConcurrentHashMap<>();

    /**
     * 初始化路由表
     *
     * Bug修复：原代码跳过 isAvailable()=false 的网关，导致配置补全后必须重启才能路由。
     * 修复方案：始终注册所有网关，isAvailable() 检查移到请求时实时判断。
     */
    @PostConstruct
    public void init() {
        log.info("初始化支付网关路由器...");

        for (PaymentGateway gateway : gateways) {
            PaymentChannel channel = gateway.getChannel();
            // 无论 isAvailable() 是否为 true，都注册进 Map
            // isAvailable() 状态可能随配置动态变化（如运营时补充了 API Key）
            gatewayMap.put(channel, gateway);
            log.info("注册支付网关: {} - {} (available={})",
                    channel.getCode(), gateway.getChannelName(), gateway.isAvailable());
        }

        long availableCount = gatewayMap.values().stream().filter(PaymentGateway::isAvailable).count();
        log.info("支付网关路由器初始化完成，共注册 {} 个网关，其中 {} 个当前可用",
                gatewayMap.size(), availableCount);
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
     * 根据支付方式智能选择网关（含熔断检查）
     *
     * 熔断逻辑：
     *   - 遍历时跳过熔断中的渠道（OPEN 状态）
     *   - 处于半开（HALF_OPEN）的渠道被允许通过一次探测请求
     *   - 所有可用渠道均熔断时抛 ServiceUnavailableException（HTTP 503）
     *
     * @param paymentMethod 支付方式（如：CARD, ALIPAY, WECHAT等）
     * @return 最优网关
     */
    public PaymentGateway selectGateway(String paymentMethod) {
        List<PaymentGateway> candidates = new ArrayList<>();

        for (PaymentGateway gateway : gatewayMap.values()) {
            if (!gateway.isAvailable()) continue;
            if (!gateway.supportsPaymentMethod(paymentMethod)) continue;

            // 熔断检查：HALF_OPEN 时 isCircuitOpen() 内部 CAS 只放行一个线程探测
            if (healthMonitor.isCircuitOpen(gateway.getChannel())) {
                log.warn("[路由跳过] 渠道 {} 熔断中，已从候选列表排除（paymentMethod={}）",
                        gateway.getChannel().getCode(), paymentMethod);
                continue;
            }
            candidates.add(gateway);
        }

        if (candidates.isEmpty()) {
            StringBuilder diagnosis = new StringBuilder("各渠道状态：");
            gatewayMap.forEach((ch, gw) -> diagnosis
                    .append(ch.getCode()).append("=")
                    .append(!gw.isAvailable() ? "配置不可用" : healthMonitor.isCircuitOpen(ch) ? "熔断中" : "OK")
                    .append(" "));
            log.error("没有支持 [{}] 的可用网关。{}", paymentMethod, diagnosis);
            throw new ServiceUnavailableException(
                    "当前支付渠道暂时不可用，请稍后重试（paymentMethod=" + paymentMethod + "）");
        }

        // 按优先级排序，选优先级最高（数值最小）的
        candidates.sort(Comparator.comparingInt(PaymentGateway::getPriority));
        PaymentGateway selected = candidates.get(0);
        log.debug("为支付方式 {} 选择网关: {}", paymentMethod, selected.getChannelName());
        return selected;
    }

    /**
     * 根据支付方式智能选择网关（支持渠道强制覆盖，含熔断检查）
     *
     * @param paymentMethod  支付方式，channelOverride 为 null 时生效
     * @param channelOverride 渠道代码覆盖（如 "SCB"），非 null 时强制走指定渠道
     * @return 对应的网关实例
     */
    public PaymentGateway selectGateway(String paymentMethod, String channelOverride) {
        if (channelOverride != null && !channelOverride.isEmpty()) {
            log.debug("渠道强制覆盖: {}，跳过自动路由", channelOverride);
            PaymentChannel channel = PaymentChannel.fromCode(channelOverride);
            if (healthMonitor.isCircuitOpen(channel)) {
                throw new ServiceUnavailableException(channelOverride,
                        "支付渠道 " + channel.getName() + " 暂时不可用，请稍后重试");
            }
            return getGateway(channel);
        }
        return selectGateway(paymentMethod);
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

