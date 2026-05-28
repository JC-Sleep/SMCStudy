#ifndef __TUYA_PORT_H
#define __TUYA_PORT_H

#include "main.h"

/* ============================================================
 * !! 必须填写 !! 把 YOUR_PID_HERE 替换为你在涂鸦平台的真实 PID
 *
 * 在哪里找 PID：
 *   iot.tuya.com → 产品管理 → 选中你的产品 → 硬件开发 → 产品信息
 *
 * 例如：#define TUYA_PRODUCT_ID  "xxxxxxxxxxxxxx"
 * ============================================================ */
#define TUYA_PRODUCT_ID    "YOUR_PID_HERE"

/* DP ID：与涂鸦平台"功能点"里的 DP ID 保持一致
 *   DP1 → 温度（整数型，单位 ℃）
 *   DP2 → 湿度（整数型，单位 %RH）
 * 如果你的平台上 DP ID 不是 1 和 2，改这里即可 */
#define DP_ID_TEMPERATURE    1U
#define DP_ID_HUMIDITY       2U

/* WiFi/云连接状态（由 WBR3 主动上报） */
typedef enum {
    TUYA_WIFI_SMARTCONFIG    = 0x00U,  /* 等待 EZConfig 配网 */
    TUYA_WIFI_AP             = 0x01U,  /* 等待 AP 配网 */
    TUYA_WIFI_SMARTCONFIG_AP = 0x02U,  /* 双模配网 */
    TUYA_WIFI_CONN_ROUTER    = 0x03U,  /* 已连接路由器，未连云 */
    TUYA_WIFI_CONN_CLOUD     = 0x04U,  /* 已连接涂鸦云 */
    TUYA_WIFI_CLOUD_LINKED   = 0x05U,  /* 已连云且已激活（正常工作） */
    TUYA_WIFI_LOW_POWER      = 0x06U,  /* 低功耗模式 */
} TuyaWifiStatus;

void            TuyaPort_Init(void);
void            TuyaPort_Process(void);   /* 在 main 循环中持续调用 */
void            TuyaPort_ReportTemperature(int32_t temp_c);
void            TuyaPort_ReportHumidity(int32_t humi_pct);
TuyaWifiStatus  TuyaPort_GetWifiStatus(void);

/* 在 USART1_IRQHandler 里调用，将接收到的字节送入解析缓冲区 */
void            TuyaPort_FeedByte(uint8_t byte);

#endif /* __TUYA_PORT_H */
