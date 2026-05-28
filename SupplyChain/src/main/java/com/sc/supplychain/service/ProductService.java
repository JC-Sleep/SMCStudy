package com.sc.supplychain.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sc.supplychain.dto.request.SkuRequest;
import com.sc.supplychain.dto.request.SpuRequest;
import com.sc.supplychain.entity.Sku;
import com.sc.supplychain.entity.Spu;

import java.util.List;

/** 商品中心服务 */
public interface ProductService {

    /** 新建 SPU（状态为 DRAFT） */
    Long createSpu(SpuRequest req);

    /** 上架: DRAFT/OFF_SALE → ON_SALE（前置校验：至少一个启用SKU + 有库存） */
    void onSale(Long spuId);

    /** 下架: ON_SALE → OFF_SALE */
    void offSale(Long spuId);

    /** 分页查询 SPU */
    IPage<Spu> pageSpus(Page<Spu> page, String keyword, String status);

    /** 查 SPU 详情 */
    Spu getSpuById(Long spuId);

    /** 新增 SKU */
    Long createSku(SkuRequest req);

    /** 查询 SPU 下所有 SKU */
    List<Sku> listSkuBySpuId(Long spuId);

    /** 禁用/启用 SKU */
    void toggleSkuStatus(Long skuId, boolean enable);
}
