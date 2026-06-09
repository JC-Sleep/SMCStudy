package com.sc.supplychain.service;

import com.sc.supplychain.entity.Rider;

public interface DispatchService {

    /**
     * 智能派单：从仓库可用骑手中按 距离*权重 + 评分*权重 + 负载*权重 评分，取最高
     * @return 派给的骑手；返回 null 表示无可用骑手 → 转入抢单池
     */
    Rider pickRider(Long deliveryId);

    /** 派单：找到候选 → 绑定 → WebSocket 推送 */
    boolean dispatch(Long deliveryId);
}

