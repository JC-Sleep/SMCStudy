#ifndef __DHT11_H
#define __DHT11_H

#include "main.h"

/*
 * DHT11 single-wire data pin.
 *
 *   PY32 PA0 ──[4.7kΩ 上拉到 3.3V]──┬── DHT11 DATA
 *                                     └── 3.3V
 *
 * PA0 会在驱动内部动态切换输出/输入模式。
 * 上拉电阻必不可少，否则无法读取数据！
 */
#define DHT11_GPIO_PORT    GPIOA
#define DHT11_GPIO_PIN     GPIO_PIN_0

typedef enum {
    DHT11_OK    = 0,
    DHT11_ERROR = 1
} DHT11_Status;

typedef struct {
    uint8_t temperature;   /* 摄氏度整数部分，范围 0~50 */
    uint8_t humidity;      /* 相对湿度整数部分，范围 20~90 */
} DHT11_Data_t;

void         DHT11_Init(void);
DHT11_Status DHT11_Read(DHT11_Data_t *data);
uint8_t      DHT11_TestIdleLevel(void);   /* 返回 0~10，期望 10（持续高电平） */
void         DHT11_SampleWaveform(uint8_t start_ms, char buf[101]);  /* 抓 1ms 波形 */

#endif /* __DHT11_H */
