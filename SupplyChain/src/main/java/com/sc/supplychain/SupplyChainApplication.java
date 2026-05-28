package com.sc.supplychain;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 旺生活 O2O 供应链中台 启动类
 *
 * <p>模块职责：
 * <ul>
 *   <li>商品中心（SPU/SKU 多级属性，上下架状态机）</li>
 *   <li>分布式库存（Redis 预扣 + Lua 原子操作，Kafka 异步落库）</li>
 *   <li>批次 FIFO（入库时间排序出库，跨批次分配）</li>
 *   <li>生鲜效期预警（NORMAL/NEAR_EXPIRY/URGENT/EXPIRED 四级）</li>
 *   <li>自动补货（阈值触发，Kafka 异步生成补货单）</li>
 *   <li>O2O 履约（下单预扣 → 支付 → 出库确认，全链路流水）</li>
 *   <li>骑手配送 + 金蝶云对接（Phase2 接口外壳预留）</li>
 * </ul>
 *
 * @author SupplyChain
 */
@SpringBootApplication
@MapperScan("com.sc.supplychain.mapper")
@EnableAsync
@EnableScheduling
public class SupplyChainApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplyChainApplication.class, args);
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║   旺生活 O2O 供应链中台 Started Successfully ！   ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
    }
}
