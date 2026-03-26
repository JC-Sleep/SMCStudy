package sys.smc.coupon.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import sys.smc.coupon.dto.request.CouponTemplateRequest;
import sys.smc.coupon.dto.response.CouponTemplateDTO;
import sys.smc.coupon.entity.CouponTemplate;

import java.util.List;

/**
 * 优惠券模板服务接口
 */
public interface CouponTemplateService extends IService<CouponTemplate> {

    /**
     * 创建模板
     */
    CouponTemplateDTO createTemplate(CouponTemplateRequest request, String operator);

    /**
     * 更新模板
     */
    CouponTemplateDTO updateTemplate(CouponTemplateRequest request, String operator);

    /**
     * 获取模板详情
     */
    CouponTemplateDTO getTemplateDetail(Long templateId);

    /**
     * 分页查询模板
     */
    IPage<CouponTemplateDTO> pageTemplates(int pageNum, int pageSize, Integer status, Integer grantType);

    /**
     * 启用模板
     */
    void enableTemplate(Long templateId);

    /**
     * 停用模板
     */
    void disableTemplate(Long templateId);

    /**
     * 删除模板(逻辑删除)
     */
    void deleteTemplate(Long templateId);

    /**
     * 查询指定发放类型的启用模板
     */
    List<CouponTemplate> listEnabledByGrantType(Integer grantType);
}

