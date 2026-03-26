package sys.smc.coupon.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sys.smc.coupon.dto.request.CouponTemplateRequest;
import sys.smc.coupon.dto.response.CouponTemplateDTO;
import sys.smc.coupon.entity.CouponTemplate;
import sys.smc.coupon.enums.CouponType;
import sys.smc.coupon.enums.GrantType;
import sys.smc.coupon.exception.CouponException;
import sys.smc.coupon.mapper.CouponTemplateMapper;
import sys.smc.coupon.service.CouponTemplateService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 优惠券模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponTemplateServiceImpl extends ServiceImpl<CouponTemplateMapper, CouponTemplate> 
        implements CouponTemplateService {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateDTO createTemplate(CouponTemplateRequest request, String operator) {
        // 参数校验
        validateRequest(request);

        CouponTemplate template = new CouponTemplate();
        BeanUtils.copyProperties(request, template);
        template.setIssuedQuantity(0);
        template.setStatus(0); // 草稿状态
        template.setCreateBy(operator);
        template.setDeleted(0);

        save(template);
        log.info("创建优惠券模板: id={}, name={}, operator={}", template.getId(), template.getName(), operator);

        return convertToDTO(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CouponTemplateDTO updateTemplate(CouponTemplateRequest request, String operator) {
        if (request.getId() == null) {
            throw new CouponException(400, "模板ID不能为空");
        }

        CouponTemplate existing = getById(request.getId());
        if (existing == null) {
            throw CouponException.templateNotFound();
        }

        // 已发放的模板不能修改关键字段
        if (existing.getIssuedQuantity() > 0) {
            if (!existing.getCouponType().equals(request.getCouponType()) ||
                !existing.getFaceValue().equals(request.getFaceValue())) {
                throw new CouponException(400, "已发放的模板不能修改券类型和面值");
            }
        }

        validateRequest(request);

        CouponTemplate template = new CouponTemplate();
        BeanUtils.copyProperties(request, template);
        updateById(template);

        log.info("更新优惠券模板: id={}, operator={}", template.getId(), operator);

        return convertToDTO(getById(template.getId()));
    }

    @Override
    public CouponTemplateDTO getTemplateDetail(Long templateId) {
        CouponTemplate template = getById(templateId);
        if (template == null || template.getDeleted() == 1) {
            throw CouponException.templateNotFound();
        }
        return convertToDTO(template);
    }

    @Override
    public IPage<CouponTemplateDTO> pageTemplates(int pageNum, int pageSize, Integer status, Integer grantType) {
        Page<CouponTemplate> page = new Page<>(pageNum, pageSize);
        
        LambdaQueryWrapper<CouponTemplate> query = new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getDeleted, 0)
                .eq(status != null, CouponTemplate::getStatus, status)
                .eq(grantType != null, CouponTemplate::getGrantType, grantType)
                .orderByDesc(CouponTemplate::getCreateTime);

        IPage<CouponTemplate> result = page(page, query);

        // 转换为DTO
        Page<CouponTemplateDTO> dtoPage = new Page<>(pageNum, pageSize, result.getTotal());
        dtoPage.setRecords(result.getRecords().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList()));

        return dtoPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableTemplate(Long templateId) {
        CouponTemplate template = getById(templateId);
        if (template == null) {
            throw CouponException.templateNotFound();
        }

        update(new LambdaUpdateWrapper<CouponTemplate>()
                .set(CouponTemplate::getStatus, 1)
                .eq(CouponTemplate::getId, templateId));

        log.info("启用优惠券模板: id={}", templateId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void disableTemplate(Long templateId) {
        update(new LambdaUpdateWrapper<CouponTemplate>()
                .set(CouponTemplate::getStatus, 2)
                .eq(CouponTemplate::getId, templateId));

        log.info("停用优惠券模板: id={}", templateId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long templateId) {
        CouponTemplate template = getById(templateId);
        if (template != null && template.getIssuedQuantity() > 0) {
            throw new CouponException(400, "已发放优惠券的模板不能删除");
        }

        update(new LambdaUpdateWrapper<CouponTemplate>()
                .set(CouponTemplate::getDeleted, 1)
                .eq(CouponTemplate::getId, templateId));

        log.info("删除优惠券模板: id={}", templateId);
    }

    @Override
    public List<CouponTemplate> listEnabledByGrantType(Integer grantType) {
        return list(new LambdaQueryWrapper<CouponTemplate>()
                .eq(CouponTemplate::getStatus, 1)
                .eq(CouponTemplate::getGrantType, grantType)
                .eq(CouponTemplate::getDeleted, 0));
    }

    /**
     * 校验请求参数
     */
    private void validateRequest(CouponTemplateRequest request) {
        // 现金券/满减券必须有面值
        if ((request.getCouponType() == 1 || request.getCouponType() == 3) 
                && request.getFaceValue() == null) {
            throw new CouponException(400, "现金券/满减券必须设置面值");
        }

        // 折扣券必须有折扣率
        if (request.getCouponType() == 2 && request.getDiscountRate() == null) {
            throw new CouponException(400, "折扣券必须设置折扣比例");
        }

        // 绝对时间必须有开始结束时间
        if (request.getValidityType() == 1) {
            if (request.getValidStartTime() == null || request.getValidEndTime() == null) {
                throw new CouponException(400, "绝对时间有效期必须设置开始和结束时间");
            }
            if (request.getValidEndTime().isBefore(request.getValidStartTime())) {
                throw new CouponException(400, "结束时间不能早于开始时间");
            }
        }

        // 相对时间必须有天数
        if (request.getValidityType() == 2 && request.getValidDays() == null) {
            throw new CouponException(400, "相对时间有效期必须设置有效天数");
        }
    }

    /**
     * 转换为DTO
     */
    private CouponTemplateDTO convertToDTO(CouponTemplate template) {
        CouponTemplateDTO dto = new CouponTemplateDTO();
        BeanUtils.copyProperties(template, dto);

        // 设置类型名称
        dto.setCouponTypeName(CouponType.fromCode(template.getCouponType()).getName());
        dto.setGrantTypeName(GrantType.fromCode(template.getGrantType()).getName());
        dto.setStackable(template.getStackable() == 1);
        
        // 有效期类型名称
        dto.setValidityTypeName(template.getValidityType() == 1 ? "绝对时间" : "相对时间");

        // 状态名称
        String statusName;
        switch (template.getStatus()) {
            case 0: statusName = "草稿"; break;
            case 1: statusName = "启用"; break;
            case 2: statusName = "停用"; break;
            default: statusName = "未知";
        }
        dto.setStatusName(statusName);

        // 计算剩余数量
        dto.setRemainQuantity(template.getTotalQuantity() - template.getIssuedQuantity());

        return dto;
    }
}

