package com.sc.supplychain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sc.supplychain.dto.request.SkuRequest;
import com.sc.supplychain.dto.request.SpuRequest;
import com.sc.supplychain.entity.Sku;
import com.sc.supplychain.entity.Spu;
import com.sc.supplychain.enums.ProductStatus;
import com.sc.supplychain.exception.SupplyChainException;
import com.sc.supplychain.mapper.SkuMapper;
import com.sc.supplychain.mapper.SpuMapper;
import com.sc.supplychain.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;

    @Override
    public Long createSpu(SpuRequest req) {
        Spu spu = new Spu();
        spu.setSpuName(req.getSpuName());
        spu.setCategoryId(req.getCategoryId());
        spu.setBrand(req.getBrand());
        spu.setFreshType(req.getFreshType() != null ? req.getFreshType() : "NORMAL");
        spu.setStatus(ProductStatus.DRAFT.getCode());
        spu.setDescription(req.getDescription());
        spuMapper.insert(spu);
        log.info("创建SPU id={} name={}", spu.getId(), spu.getSpuName());
        return spu.getId();
    }

    @Override
    public void onSale(Long spuId) {
        Spu spu = requireSpu(spuId);
        if (ProductStatus.ON_SALE.getCode().equals(spu.getStatus())) {
            throw SupplyChainException.illegalStatus("已经是上架状态");
        }
        // 前置校验：至少一个启用的 SKU
        long skuCount = skuMapper.selectCount(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .eq(Sku::getStatus, "ENABLED"));
        if (skuCount == 0) {
            throw SupplyChainException.of("上架失败：SPU 下没有启用的 SKU");
        }
        spu.setStatus(ProductStatus.ON_SALE.getCode());
        spuMapper.updateById(spu);
        log.info("SPU[{}] 上架成功", spuId);
    }

    @Override
    public void offSale(Long spuId) {
        Spu spu = requireSpu(spuId);
        if (!ProductStatus.ON_SALE.getCode().equals(spu.getStatus())) {
            throw SupplyChainException.illegalStatus("只有上架状态才能下架");
        }
        spu.setStatus(ProductStatus.OFF_SALE.getCode());
        spuMapper.updateById(spu);
        log.info("SPU[{}] 下架成功", spuId);
    }

    @Override
    public IPage<Spu> pageSpus(Page<Spu> page, String keyword, String status) {
        LambdaQueryWrapper<Spu> wrapper = new LambdaQueryWrapper<Spu>()
                .like(StringUtils.hasText(keyword), Spu::getSpuName, keyword)
                .eq(StringUtils.hasText(status), Spu::getStatus, status)
                .orderByDesc(Spu::getCreateTime);
        return spuMapper.selectPage(page, wrapper);
    }

    @Override
    public Spu getSpuById(Long spuId) {
        return requireSpu(spuId);
    }

    @Override
    public Long createSku(SkuRequest req) {
        requireSpu(req.getSpuId()); // 校验 SPU 存在
        Sku sku = new Sku();
        sku.setSpuId(req.getSpuId());
        sku.setSkuName(req.getSkuName());
        sku.setSkuAttrs(req.getSkuAttrs());
        sku.setPrice(req.getPrice());
        sku.setWeight(req.getWeight());
        sku.setImgUrl(req.getImgUrl());
        sku.setStatus("ENABLED");
        skuMapper.insert(sku);
        log.info("创建SKU id={} spuId={}", sku.getId(), sku.getSpuId());
        return sku.getId();
    }

    @Override
    public List<Sku> listSkuBySpuId(Long spuId) {
        return skuMapper.selectList(new LambdaQueryWrapper<Sku>()
                .eq(Sku::getSpuId, spuId)
                .orderByAsc(Sku::getCreateTime));
    }

    @Override
    public void toggleSkuStatus(Long skuId, boolean enable) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) throw SupplyChainException.notFound("SKU", skuId);
        sku.setStatus(enable ? "ENABLED" : "DISABLED");
        skuMapper.updateById(sku);
    }

    private Spu requireSpu(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) throw SupplyChainException.notFound("SPU", spuId);
        return spu;
    }
}
