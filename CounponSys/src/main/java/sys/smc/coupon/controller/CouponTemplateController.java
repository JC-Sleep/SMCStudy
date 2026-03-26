package sys.smc.coupon.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import sys.smc.coupon.dto.request.CouponTemplateRequest;
import sys.smc.coupon.dto.response.ApiResponse;
import sys.smc.coupon.dto.response.CouponTemplateDTO;
import sys.smc.coupon.service.CouponTemplateService;

/**
 * 优惠券模板管理Controller
 */
@Api(tags = "优惠券模板管理")
@RestController
@RequestMapping("/api/coupon/template")
@RequiredArgsConstructor
public class CouponTemplateController {

    private final CouponTemplateService templateService;

    @ApiOperation("创建模板")
    @PostMapping("/create")
    public ApiResponse<CouponTemplateDTO> createTemplate(
            @Validated @RequestBody CouponTemplateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String operator) {
        CouponTemplateDTO template = templateService.createTemplate(request, operator);
        return ApiResponse.success("创建成功", template);
    }

    @ApiOperation("更新模板")
    @PutMapping("/update")
    public ApiResponse<CouponTemplateDTO> updateTemplate(
            @Validated @RequestBody CouponTemplateRequest request,
            @RequestHeader(value = "X-User-Id", defaultValue = "system") String operator) {
        CouponTemplateDTO template = templateService.updateTemplate(request, operator);
        return ApiResponse.success("更新成功", template);
    }

    @ApiOperation("获取模板详情")
    @GetMapping("/{templateId}")
    public ApiResponse<CouponTemplateDTO> getTemplateDetail(
            @ApiParam("模板ID") @PathVariable Long templateId) {
        CouponTemplateDTO template = templateService.getTemplateDetail(templateId);
        return ApiResponse.success(template);
    }

    @ApiOperation("分页查询模板")
    @GetMapping("/page")
    public ApiResponse<IPage<CouponTemplateDTO>> pageTemplates(
            @ApiParam("页码") @RequestParam(defaultValue = "1") int pageNum,
            @ApiParam("每页数量") @RequestParam(defaultValue = "10") int pageSize,
            @ApiParam("状态:0草稿,1启用,2停用") @RequestParam(required = false) Integer status,
            @ApiParam("发放类型") @RequestParam(required = false) Integer grantType) {
        IPage<CouponTemplateDTO> page = templateService.pageTemplates(pageNum, pageSize, status, grantType);
        return ApiResponse.success(page);
    }

    @ApiOperation("启用模板")
    @PostMapping("/{templateId}/enable")
    public ApiResponse<Void> enableTemplate(@PathVariable Long templateId) {
        templateService.enableTemplate(templateId);
        return ApiResponse.success("启用成功", null);
    }

    @ApiOperation("停用模板")
    @PostMapping("/{templateId}/disable")
    public ApiResponse<Void> disableTemplate(@PathVariable Long templateId) {
        templateService.disableTemplate(templateId);
        return ApiResponse.success("停用成功", null);
    }

    @ApiOperation("删除模板")
    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> deleteTemplate(@PathVariable Long templateId) {
        templateService.deleteTemplate(templateId);
        return ApiResponse.success("删除成功", null);
    }
}

