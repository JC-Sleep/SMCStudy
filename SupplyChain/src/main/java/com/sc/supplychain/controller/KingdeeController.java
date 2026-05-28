package com.sc.supplychain.controller;

import com.sc.supplychain.dto.response.ApiResponse;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.*;

/**
 * 金蝶云财务对接接口（Phase2预留外壳）
 * TODO Phase2: 实现 HTTP 推送凭证、同步记录查询、失败重试
 * 同步数据映射:
 *   旺生活出库单  → 金蝶销售凭证（借：应收账款  贷：主营业务收入）
 *   旺生活采购入库 → 金蝶应付凭证（借：库存商品  贷：应付账款）
 *   旺生活出库成本 → 金蝶成本凭证（借：主营业务成本  贷：库存商品）
 */
@Api(tags = "金蝶云财务对接【Phase2预留】")
@RestController
@RequestMapping("/api/kingdee")
public class KingdeeController {

    @ApiOperation("手动触发金蝶同步 [TODO Phase2]")
    @PostMapping("/sync/manual")
    public ApiResponse<Void> manualSync(@RequestParam(required = false) String refNo) {
        // TODO Phase2: 调用 KingdeeDataSyncJob 同步指定单据，或全量同步
        return ApiResponse.error(501, "Phase2 待实现：金蝶手动同步");
    }

    @ApiOperation("查询同步记录 [TODO Phase2]")
    @GetMapping("/sync/records")
    public ApiResponse<Void> syncRecords(@RequestParam(required = false) String syncStatus) {
        // TODO Phase2: 查 sc_kingdee_sync 同步记录，支持失败重试
        return ApiResponse.error(501, "Phase2 待实现：同步记录查询");
    }
}
