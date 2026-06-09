package com.sc.supplychain.websocket;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 骑手 App WebSocket 长连接处理器
 *
 * 路径：/ws/rider/{riderId}
 *
 * 服务端→骑手 推送场景：
 *   - ASSIGN：智能派单到该骑手
 *   - GRAB_BROADCAST：抢单池广播
 *   - REASSIGN：原骑手被强制改派
 *   - CANCEL：客户取消订单
 *
 * 多实例部署：本类只维护本地连接，跨实例需配合 Redis Pub/Sub（TODO Phase 2.2）
 */
@Slf4j
@Component
public class RiderWebSocketHandler extends TextWebSocketHandler {

    private static final Pattern PATH_PATTERN = Pattern.compile("/ws/rider/(\\d+)");

    /** riderId → WebSocketSession */
    private static final ConcurrentHashMap<Long, WebSocketSession> SESSIONS = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long riderId = parseRiderId(session.getUri());
        if (riderId == null) { try { session.close(CloseStatus.BAD_DATA); } catch (IOException ignored) {} return; }
        // 关闭旧连接（同一骑手只允许一处在线）
        WebSocketSession old = SESSIONS.put(riderId, session);
        if (old != null && old.isOpen()) {
            try { old.close(CloseStatus.POLICY_VIOLATION.withReason("kicked by new login")); } catch (IOException ignored) {}
        }
        log.info("[WS] rider {} connected, total={}", riderId, SESSIONS.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 骑手 App 上行消息（位置/心跳/确认到达）— 当前由 HTTP 接口承担，留作扩展点
        log.debug("[WS] msg from {} = {}", session.getId(), message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long riderId = parseRiderId(session.getUri());
        if (riderId != null) SESSIONS.remove(riderId, session);
        log.info("[WS] rider {} closed status={} total={}", riderId, status, SESSIONS.size());
    }

    /** 推送给单个骑手 */
    public boolean pushTo(Long riderId, Object payload) {
        WebSocketSession s = SESSIONS.get(riderId);
        if (s == null || !s.isOpen()) return false;
        try {
            s.sendMessage(new TextMessage(JSON.toJSONString(payload)));
            return true;
        } catch (IOException e) {
            log.warn("[WS] push to {} failed: {}", riderId, e.getMessage());
            return false;
        }
    }

    /** 广播给某仓库所有在线骑手（抢单池广播）TODO 改用 Redis Pub/Sub 跨实例 */
    public int broadcast(Iterable<Long> riderIds, Object payload) {
        int count = 0;
        for (Long rid : riderIds) {
            if (pushTo(rid, payload)) count++;
        }
        return count;
    }

    private Long parseRiderId(URI uri) {
        if (uri == null) return null;
        Matcher m = PATH_PATTERN.matcher(uri.getPath());
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }
}

