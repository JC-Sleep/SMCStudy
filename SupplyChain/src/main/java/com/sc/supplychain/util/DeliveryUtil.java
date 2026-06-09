package com.sc.supplychain.util;

import cn.hutool.core.util.RandomUtil;

import java.math.BigDecimal;

/** 配送相关工具 */
public class DeliveryUtil {

    /** Haversine 公式计算两点距离（米） */
    public static int haversineMeters(BigDecimal lng1, BigDecimal lat1, BigDecimal lng2, BigDecimal lat2) {
        if (lng1 == null || lat1 == null || lng2 == null || lat2 == null) return 0;
        double R = 6371000.0;
        double rad1Lat = Math.toRadians(lat1.doubleValue());
        double rad2Lat = Math.toRadians(lat2.doubleValue());
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(rad1Lat) * Math.cos(rad2Lat)
                  * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(R * c);
    }

    /** 6 位数字验证码 */
    public static String genCode6() {
        return RandomUtil.randomNumbers(6);
    }

    /** Redis Keys */
    public static String grabLockKey(Long deliveryId) {
        return "sc:delivery:grab:lock:" + deliveryId;
    }
    public static String riderGeoKey(Long warehouseId) {
        return "sc:rider:geo:" + warehouseId;
    }
    public static String riderOnlineKey(Long warehouseId) {
        return "sc:rider:online:" + warehouseId;
    }
}

