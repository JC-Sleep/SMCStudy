/*
 * tuya_port.c — 涂鸦 MCU 串口协议实现（无需官方 SDK）
 *
 * ── 协议帧格式 ─────────────────────────────────────────────────────────────
 *
 *  字节:  55  AA  [VER] [CMD] [LEN_H] [LEN_L] [DATA...] [CHECKSUM]
 *  头部:  固定 55 AA，是帧起始标志
 *  VER:   版本号，发送固定用 0x03
 *  CMD:   命令号（见下方宏定义）
 *  LEN:   DATA 段字节数（2 字节大端）
 *  DATA:  payload
 *  CHECKSUM: VER+CMD+LEN_H+LEN_L+DATA 各字节之和的低 8 位
 *
 * ── 主要命令 ───────────────────────────────────────────────────────────────
 *
 *  CMD_HEARTBEAT  0x00  WBR3 每 ~3s 轮询心跳；MCU 回复确认在线
 *  CMD_PROD_INFO  0x01  WBR3 上电后查询产品 PID
 *  CMD_MCU_VER    0x02  WBR3 查询 MCU 固件版本
 *  CMD_WIFI_STAT  0x03  WBR3 主动通知 WiFi/云连接状态（无需回复）
 *  CMD_REPORT_DP  0x07  MCU → WBR3：上报传感器数据点（DP）到涂鸦云
 *  CMD_CTRL_DP    0x08  WBR3 → MCU：App 下发控制命令（本 Demo 传感器只读，无需处理）
 *
 * ── DP 数据格式（CMD_REPORT_DP 的 DATA 段）──────────────────────────────
 *
 *  [DP_ID 1B] [TYPE 1B] [LEN 2B] [VALUE ...]
 *  TYPE=0x02 (value/integer)，LEN=0x0004，VALUE 为 4 字节大端 int32
 *
 * ── 架构说明 ───────────────────────────────────────────────────────────────
 *
 *  ISR（USART1_IRQHandler）→ TuyaPort_FeedByte() → 环形缓冲区
 *  主循环 → TuyaPort_Process() → 状态机解析 → HandleFrame() → 自动回复
 */
#include "tuya_port.h"
#include "uart.h"

/* ── 协议常量 ────────────────────────────────────────────────────────────── */
#define FRAME_H         0x55U
#define FRAME_L         0xAAU
#define PROTO_VER       0x03U

#define CMD_HEARTBEAT   0x00U
#define CMD_PROD_INFO   0x01U
#define CMD_MCU_VER     0x02U
#define CMD_WIFI_STAT   0x03U
#define CMD_REPORT_DP   0x07U
#define CMD_CTRL_DP     0x08U

#define DP_TYPE_VALUE   0x02U   /* 整数类型 DP */

/* ── 接收环形缓冲区（ISR 写，主循环读，不需要加锁，M0+ 单核安全） ──── */
#define RX_BUF_SIZE     256U
static uint8_t           rxBuf[RX_BUF_SIZE];
static volatile uint16_t rxHead = 0U;   /* ISR 写指针 */
static volatile uint16_t rxTail = 0U;   /* 主循环读指针 */

/* ── 帧解析状态机 ────────────────────────────────────────────────────────── */
typedef enum {
    ST_HEADER1 = 0,
    ST_HEADER2,
    ST_VERSION,
    ST_CMD,
    ST_LEN_H,
    ST_LEN_L,
    ST_DATA,
    ST_CHECKSUM
} ParseState_t;

static ParseState_t pState     = ST_HEADER1;
static uint8_t      pVer       = 0U;
static uint8_t      pCmd       = 0U;
static uint16_t     pLen       = 0U;
static uint16_t     pDataCount = 0U;
static uint8_t      pData[128];

/* ── 模块状态 ────────────────────────────────────────────────────────────── */
static TuyaWifiStatus wifiStatus    = TUYA_WIFI_SMARTCONFIG;
static uint8_t        heartbeatDone = 0U;   /* 首次心跳后置 1 */

/* ── 私有函数 ────────────────────────────────────────────────────────────── */

/*
 * CalcChecksum — 计算涂鸦帧校验和
 * = (ver + cmd + len_H + len_L + data[0..len-1]) & 0xFF
 */
static uint8_t CalcChecksum(uint8_t ver, uint8_t cmd,
                             uint16_t len, const uint8_t *data)
{
    uint32_t sum = (uint32_t)ver + cmd
                   + (uint8_t)(len >> 8U) + (uint8_t)(len & 0xFFU);
    for (uint16_t i = 0U; i < len; i++)
    {
        sum += data[i];
    }
    return (uint8_t)(sum & 0xFFU);
}

/*
 * SendFrame — 构造并发送一帧
 * 格式：55 AA VER CMD LEN_H LEN_L [data...] CHECKSUM
 */
static void SendFrame(uint8_t cmd, const uint8_t *data, uint16_t len)
{
    uint8_t header[6];
    header[0] = FRAME_H;
    header[1] = FRAME_L;
    header[2] = PROTO_VER;
    header[3] = cmd;
    header[4] = (uint8_t)(len >> 8U);
    header[5] = (uint8_t)(len & 0xFFU);
    UART1_SendBytes(header, 6U);
    if (len > 0U)
    {
        UART1_SendBytes(data, len);
    }
    uint8_t cs = CalcChecksum(PROTO_VER, cmd, len, data);
    UART1_SendByte(cs);
}

/*
 * HandleFrame — 处理一帧完整的协议数据
 * 由 TuyaPort_Process() 在校验通过后调用
 */
static void HandleFrame(uint8_t cmd, const uint8_t *data, uint16_t len)
{
    switch (cmd)
    {
        /* ── 心跳（WBR3 每隔 ~3 秒发一次，MCU 必须回复） ─────────────── */
        case CMD_HEARTBEAT:
        {
            /*
             * 握手规则：
             *   第一次回复 data=0x00（MCU 刚上电，告知模组"我还没准备好"）
             *   之后每次回复 data=0x01（MCU 正常运行）
             * WBR3 收到 0x01 才会开始配网流程。
             */
            uint8_t reply = (heartbeatDone == 0U) ? 0x00U : 0x01U;
            heartbeatDone = 1U;
            SendFrame(CMD_HEARTBEAT, &reply, 1U);
            break;
        }

        /* ── 产品信息查询（WBR3 上电后发一次） ─────────────────────────── */
        case CMD_PROD_INFO:
        {
            /*
             * 回复 JSON 格式：{"p":"<PID>","v":"1.0.0","m":0}
             *   p = Product ID（在 Inc/tuya_port.h 中定义 TUYA_PRODUCT_ID）
             *   v = MCU 软件版本（任意填，保持格式即可）
             *   m = 0（标准模式）
             *
             * !! 记得把 TUYA_PRODUCT_ID 改为你的真实 PID !!
             */
            static const char info[] =
                "{\"p\":\"" TUYA_PRODUCT_ID "\",\"v\":\"1.0.0\",\"m\":0}";
            SendFrame(CMD_PROD_INFO, (const uint8_t *)info,
                      (uint16_t)(sizeof(info) - 1U));
            break;
        }

        /* ── MCU 版本查询 ─────────────────────────────────────────────── */
        case CMD_MCU_VER:
        {
            static const char ver[] = "{\"ver\":\"1.0.0\"}";
            SendFrame(CMD_MCU_VER, (const uint8_t *)ver,
                      (uint16_t)(sizeof(ver) - 1U));
            break;
        }

        /* ── WiFi 状态上报（WBR3 主动发，MCU 不需要回复） ──────────────── */
        case CMD_WIFI_STAT:
        {
            if (len >= 1U)
            {
                wifiStatus = (TuyaWifiStatus)data[0];
                /*
                 * 状态值含义：
                 *   0x00 = 等待 EZConfig 配网
                 *   0x03 = 已连路由器
                 *   0x04 = 已连涂鸦云
                 *   0x05 = 已激活（手机 App 可看到设备）← 正常工作态
                 */
            }
            break;
        }

        /* ── App 下发控制命令（本 Demo 只上报数据，此处留空） ──────────── */
        case CMD_CTRL_DP:
        {
            /* 若将来添加可控 DP（如开关、设定值），在此解析 data 并执行 */
            (void)data;
            (void)len;
            break;
        }

        default:
            break;
    }
}

/* ── 公共 API ────────────────────────────────────────────────────────────── */

void TuyaPort_Init(void)
{
    memset(rxBuf,  0, sizeof(rxBuf));
    memset(pData,  0, sizeof(pData));
    rxHead        = 0U;
    rxTail        = 0U;
    pState        = ST_HEADER1;
    heartbeatDone = 0U;
    wifiStatus    = TUYA_WIFI_SMARTCONFIG;
}

/*
 * TuyaPort_FeedByte — 在 USART1_IRQHandler 中调用
 * 将接收到的字节放入环形缓冲区，主循环中由 TuyaPort_Process() 消费
 * ISR 上下文：尽量简短，禁止调用阻塞函数
 */
void TuyaPort_FeedByte(uint8_t byte)
{
    uint16_t next = (rxHead + 1U) % RX_BUF_SIZE;
    if (next != rxTail)   /* 缓冲区未满才写入（满时静默丢弃） */
    {
        rxBuf[rxHead] = byte;
        rxHead        = next;
    }
}

/*
 * TuyaPort_Process — 在 main() 主循环中持续调用
 * 消费环形缓冲区里的字节，驱动状态机解析完整帧
 */
void TuyaPort_Process(void)
{
    while (rxHead != rxTail)
    {
        uint8_t b = rxBuf[rxTail];
        rxTail    = (rxTail + 1U) % RX_BUF_SIZE;

        switch (pState)
        {
            case ST_HEADER1:
                /* 在字节流中寻找帧头 0x55 */
                pState = (b == FRAME_H) ? ST_HEADER2 : ST_HEADER1;
                break;

            case ST_HEADER2:
                /* 确认第二字节 0xAA */
                pState = (b == FRAME_L) ? ST_VERSION : ST_HEADER1;
                break;

            case ST_VERSION:
                pVer   = b;
                pState = ST_CMD;
                break;

            case ST_CMD:
                pCmd   = b;
                pState = ST_LEN_H;
                break;

            case ST_LEN_H:
                pLen   = (uint16_t)b << 8U;
                pState = ST_LEN_L;
                break;

            case ST_LEN_L:
                pLen      |= (uint16_t)b;
                pDataCount = 0U;
                /* 没有 payload 就直接等校验字节 */
                pState = (pLen > 0U) ? ST_DATA : ST_CHECKSUM;
                break;

            case ST_DATA:
                if (pDataCount < sizeof(pData))
                {
                    pData[pDataCount] = b;
                }
                if (++pDataCount >= pLen)
                {
                    pState = ST_CHECKSUM;
                }
                break;

            case ST_CHECKSUM:
            {
                uint8_t expected = CalcChecksum(pVer, pCmd, pLen, pData);
                if (b == expected)
                {
                    HandleFrame(pCmd, pData, pLen);   /* 校验通过，处理帧 */
                }
                /* 校验失败时静默丢弃，继续寻找下一帧头 */
                pState = ST_HEADER1;
                break;
            }

            default:
                pState = ST_HEADER1;
                break;
        }
    }
}

/*
 * TuyaPort_ReportTemperature — 上报温度 DP（DP_ID=1，整数型）
 *
 * DP 帧 DATA 格式（8 字节）：
 *   [01] [02] [00 04] [00 00 00 19]
 *    ↑    ↑     ↑          ↑
 *   DPID TYPE  长度4   大端 int32 = 25（°C）
 */
void TuyaPort_ReportTemperature(int32_t temp_c)
{
    uint8_t payload[8];
    payload[0] = DP_ID_TEMPERATURE;
    payload[1] = DP_TYPE_VALUE;
    payload[2] = 0x00U;
    payload[3] = 0x04U;
    payload[4] = (uint8_t)((temp_c >> 24) & 0xFF);
    payload[5] = (uint8_t)((temp_c >> 16) & 0xFF);
    payload[6] = (uint8_t)((temp_c >>  8) & 0xFF);
    payload[7] = (uint8_t)( temp_c        & 0xFF);
    SendFrame(CMD_REPORT_DP, payload, 8U);
}

/*
 * TuyaPort_ReportHumidity — 上报湿度 DP（DP_ID=2，整数型）
 */
void TuyaPort_ReportHumidity(int32_t humi_pct)
{
    uint8_t payload[8];
    payload[0] = DP_ID_HUMIDITY;
    payload[1] = DP_TYPE_VALUE;
    payload[2] = 0x00U;
    payload[3] = 0x04U;
    payload[4] = (uint8_t)((humi_pct >> 24) & 0xFF);
    payload[5] = (uint8_t)((humi_pct >> 16) & 0xFF);
    payload[6] = (uint8_t)((humi_pct >>  8) & 0xFF);
    payload[7] = (uint8_t)( humi_pct        & 0xFF);
    SendFrame(CMD_REPORT_DP, payload, 8U);
}

TuyaWifiStatus TuyaPort_GetWifiStatus(void)
{
    return wifiStatus;
}
