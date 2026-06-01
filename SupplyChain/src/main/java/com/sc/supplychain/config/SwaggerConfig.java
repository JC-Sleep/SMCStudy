package com.sc.supplychain.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / SpringDoc OpenAPI 配置
 * Knife4j UI: http://localhost:8091/doc.html           ← 推荐，界面更好看
 * Swagger UI: http://localhost:8091/swagger-ui/index.html
 * API JSON:   http://localhost:8091/v3/api-docs
 */
@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI supplyChainOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("旺生活 O2O 供应链中台 API")
                        .description("碧桂园旺生活：商品中心 · 分布式库存 · FIFO批次 · 效期预警 · O2O履约\n\n" +
                                "**快速开始**：\n" +
                                "1. 创建分类 + 仓库 + 门店（基础数据）\n" +
                                "2. 商品中心：新建 SPU → 新建 SKU → 上架\n" +
                                "3. 库存管理：入库（自动写 Redis）→ 查询库存\n" +
                                "4. O2O 履约：下单 → 支付 → 出库（FIFO）")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("旺生活供应链团队")
                                .email("dev@wangshenghuo.com")))
                // 定义 Tag 顺序（doc.html 左侧菜单显示顺序）
                .addTagsItem(new Tag().name("商品中心").description("SPU / SKU 管理、上架下架"))
                .addTagsItem(new Tag().name("库存管理").description("入库、出库、Redis库存查询、缓存预热"))
                .addTagsItem(new Tag().name("O2O履约").description("下单、支付、出库、取消"))
                .addTagsItem(new Tag().name("补货管理").description("补货申请、审批、自动触发"))
                .addTagsItem(new Tag().name("效期预警").description("临期批次查询、预警列表"))
                .addTagsItem(new Tag().name("数据对账").description("Redis与DB库存对账、差异修复"));
    }
}
