# Plan: PY32F030K28 + WBR3 + DHT11 Keil 工程完整集成指南

> **✅ 代码已修复（2026-05-30）** — 所有源文件已完成修改，可直接在 Keil 中编译。

---

## 一、最终 Keil 工程文件树（目标状态）

```
Project: PY32_WBR3_Demo
└── Target_1
    ├── User              ← 你自己写的业务代码
    │   ├── main.c
    │   ├── dht11.c
    │   ├── uart.c
    │   ├── py32f0xx_hal_msp.c
    │   └── py32f0xx_it.c
    │
    ├── Tuya_SDK          ← 涂鸦官方 SDK（不要改）
    │   ├── system.c
    │   ├── mcu_api.c
    │   └── protocol.c
    │
    ├── Startup           ← 芯片启动文件（已有✅）
    │   └── startup_py32f030x8.s
    │
    ├── CMSIS             ← ARM 内核 + 芯片系统初始化（已有✅）
    │   └── system_py32f0xx.c
    │
    └── HAL_Driver        ← PY32 HAL 库驱动（已有✅）
        ├── py32f0xx_hal.c
        ├── py32f0xx_hal_cortex.c
        ├── py32f0xx_hal_gpio.c
        ├── py32f0xx_hal_uart.c
        ├── py32f0xx_hal_rcc.c
        ├── py32f0xx_hal_rcc_ex.c
        ├── py32f0xx_hal_dma.c
        ├── py32f0xx_hal_flash.c
        └── py32f0xx_hal_pwr.c
```

---

## 二、文件架构详解（每个文件做什么）

### 📁 User 分组 — 你的业务代码

| 文件 | 做什么 |
|------|--------|
| **`main.c`** | 程序入口。初始化时钟 → 初始化串口 → 初始化 DHT11 → 调用 `wifi_protocol_init()`。主循环每 30 秒读一次 DHT11，把温湿度存到全局变量 `g_last_temp / g_last_humi`，再通过 `mcu_dp_value_update()` 推送到涂鸦云。同时不断调用 `wifi_uart_service()` 驱动 WBR3 通信。 |
| **`dht11.c`** | DHT11 单总线驱动。控制 PA0 引脚动态切换输入/输出模式，用 SysTick 做微秒延时，读出 40bit 温湿度原始数据并校验。 |
| **`uart.c`** | USART1 驱动。初始化 9600bps 串口、提供 `UART1_SendByte()` 发送接口。`protocol.c` 的 `uart_transmit_output()` 就调用它发数据给 WBR3。 |
| **`py32f0xx_hal_msp.c`** | HAL 底层硬件初始化回调。`HAL_UART_Init()` 内部会自动调用 `HAL_UART_MspInit()`，在这里配置 PA2(TX)/PA3(RX) 复用为 USART1，并设置中断优先级。把硬件配置集中在这里，`uart.c` 不需要关心引脚细节。 |
| **`py32f0xx_it.c`** | 所有中断服务函数（ISR）。**SysTick_Handler** 给 `HAL_Delay()` 提供 1ms 心跳；**USART1_IRQHandler** 每收到 WBR3 发来的一个字节就调用 `uart_receive_input()` 存入 SDK 环形缓冲区，主循环里再统一处理。 |

> ⚠️ **不要加 `tuya_port.c`** — 它是旧的自实现协议层，已被 WBR3_SDK 取代，加入工程会导致符号重定义错误。

---

### 📁 Tuya_SDK 分组 — 涂鸦官方 SDK（只需填空，不要大改）

| 文件 | 做什么 | 是否需要修改 |
|------|--------|-------------|
| **`system.c`** | SDK 内核：串口帧的组装/解析、校验和计算、环形缓冲区管理。完全不需要看懂，也不需要改。 | ❌ 不修改 |
| **`mcu_api.c`** | 对外 API 层：提供 `wifi_protocol_init()`（初始化）、`wifi_uart_service()`（主循环驱动）、`mcu_dp_value_update()`（上报 DP 数据）等你直接调用的函数。 | ❌ 不修改 |
| **`protocol.c`** | 移植适配层，有两处**你必须填写**的函数（已填好）：①`uart_transmit_output(u8 value)` — 把字节从串口发出去（已实现为 `UART1_SendByte(value)`）；②`all_data_update()` — App 查询时全量上报当前温湿度值。PID `"dkmzkgij8nhff7dh"` 已由涂鸦平台自动生成在 `protocol.h`。 | ✅ 已完成 |
| **`wifi.h`** | 统一包含所有 SDK 头文件的入口，你的 `main.c` 只需 `#include "wifi.h"` 一行即可。 | ❌ 不修改 |
| **`tuya_type.h`** | 类型定义（`u8` / `u16` / `i8` 等），供整个 SDK 使用。 | ❌ 不修改 |

---

### 📁 Startup / CMSIS / HAL_Driver — 芯片底层（已有，不需要动）

| 分组 | 文件 | 做什么 |
|------|------|--------|
| **Startup** | `startup_py32f030x8.s` | 汇编启动文件。芯片上电第一句代码，把中断向量表放到 Flash 开头，初始化 BSS/DATA 段，最后跳到 `main()`。**没有它芯片上电不会运行任何代码**。 |
| **CMSIS** | `system_py32f0xx.c` | 实现 `SystemInit()`，配置内部 Flash 时序，`HAL_Init()` 内部会自动调用它。 |
| **HAL_Driver** | `py32f0xx_hal_*.c` (9个) | PY32 的 HAL 库驱动，封装了 GPIO/UART/RCC/DMA 等寄存器操作。你的代码调 `HAL_GPIO_Init()`、`HAL_UART_Transmit()` 等函数都来自这里。 |

---

## 三、头文件包含路径（Options → C/C++ → Include Paths）

必须添加这 5 条，否则编译时找不到 `.h` 文件：

```
① D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Inc
   → 包含 main.h / uart.h / dht11.h / py32f0xx_hal_conf.h

② D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\WBR3_SDK
   → 包含 wifi.h / protocol.h / mcu_api.h / system.h / tuya_type.h

③ ...\PY32F0xx_Firmware-master\Drivers\PY32F0xx_HAL_Driver\Inc
   → HAL 函数声明（py32f0xx_hal_uart.h 等）

④ ...\PY32F0xx_Firmware-master\Drivers\CMSIS\Device\PY32F0xx\Include
   → 芯片寄存器定义（USART1->SR、GPIOA->ODR 等地址）

⑤ ...\PY32F0xx_Firmware-master\Drivers\CMSIS\Include
   → ARM 内核操作（SysTick->VAL、__disable_irq() 等）
```

---

## 四、预处理宏（Options → C/C++ → Define）

```
PY32F030x8
```

---

## 五、Keil 操作步骤

### Step 1 — 整理现有分组

- 右键 `New Group` → **Rename** → 输入 `Tuya_SDK`
- 右键 `User` 分组中多余/旧版文件 → **Remove**（只移除引用，不会删除磁盘文件）

### Step 2 — 往 User 分组加文件

右键 `User` → **Add Existing Files**，选：

```
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Src\main.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Src\dht11.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Src\uart.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Src\py32f0xx_hal_msp.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\Src\py32f0xx_it.c
```

> ⚠️ **`tuya_port.c` 不要加**，已被官方 SDK 取代。

### Step 3 — 往 Tuya_SDK 分组加文件

右键 `Tuya_SDK` → **Add Existing Files**，选：

```
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\WBR3_SDK\system.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\WBR3_SDK\mcu_api.c
D:\workAreaTools\a_program\SMCStudy\py32_web3_demo\WBR3_SDK\protocol.c
```

### Step 4 — 添加头文件路径

**Options for Target** → **C/C++** → **Include Paths** → 点 `...` 按钮，把第三节的 5 条路径全部加入。

### Step 5 — 添加预处理宏

同一页面 **Define** 框里输入：`PY32F030x8`

### Step 6 — 配置烧录器（DAPLink / CMSIS-DAP）

1. 从 [OpenPuya GitHub Releases](https://github.com/OpenPuya/PY32F0xx_Firmware) 下载 `Puya.PY32F0xx_DFP.pack`，双击安装（若 Device 已有 `PY32F030` 可跳过）。
2. **Options → Device** → 选 `PY32F030K28`。
3. **Options → Utilities → Flash Download** → 添加 Flash 算法：`Start: 0x08000000`，`Size: 0x10000`（64 KB）。
4. **Options → Debug** → 选 **CMSIS-DAP Debugger**。
5. DAPLink 连接：PA13(SWDIO) / PA14(SWDCK) / GND。

### Step 7 — 编译和烧录

```
按 F7 编译 → 应显示 0 Errors, 0 Warnings
上电 → 点击 Download（或 Flash 菜单 → Download）烧录
```

---

## 六、已修复的 Bug 列表

| 文件 | 问题 | 修复 |
|------|------|------|
| `WBR3_SDK/mcu_api.c` | `uart_receive_input / wifi_uart_service / wifi_protocol_init` 含 `#error` 阻止编译 | 删除 3 处 `#error` 行 |
| `WBR3_SDK/protocol.c` | `uart_transmit_output()` 空函数体含 `#error` | 实现为 `UART1_SendByte(value)` |
| `WBR3_SDK/protocol.c` | `all_data_update()` 空函数体含 `#error` | 实现为两次 `mcu_dp_value_update()` 调用 |
| `WBR3_SDK/protocol.h` | `WIFI_TEST_ENABLE` 开启，要求实现 `wifi_test_result()` | 注释关闭，Demo 无需产测 |
| `Src/uart.c` | `#include "tuya_port.h"` 引用已废弃的旧协议层 | 删除该行 |
| `Inc/uart.h` | 波特率 115200 与 WBR3 出厂 9600 不匹配 | 改为 `9600U` |
| `Src/py32f0xx_it.c` | `TuyaPort_FeedByte()` 旧 API | 改为 `uart_receive_input()` |
| `Src/main.c` | 全部使用 `TuyaPort_*` 旧 API | 切换为 `wifi_protocol_init / wifi_uart_service / mcu_dp_value_update` |

---

## 七、数据流总结（各文件如何协作）

```
WBR3 发来字节
   │
   ▼ USART1 中断 (py32f0xx_it.c)
uart_receive_input(byte)        ← SDK 环形缓冲区存入 (mcu_api.c)
   │
   ▼ 主循环 (main.c)
wifi_uart_service()             ← 解析帧，处理心跳/PID/WiFi状态 (system.c + protocol.c)
   │
   ▼ 每30秒
DHT11_Read() (dht11.c)
   │
   ▼
mcu_dp_value_update()           ← 组装 DP 帧 (mcu_api.c)
   │
   ▼
uart_transmit_output()          ← 调 UART1_SendByte (protocol.c → uart.c)
   │
   ▼ USART1 TX（PA2，由 py32f0xx_hal_msp.c 配置）
WBR3 收到 → WiFi → 涂鸦云 → 手机 App 显示温湿度
```

---

## 八、注意事项

1. **UART 波特率两端必须一致**：WBR3 出厂默认 **9600bps**，`uart.c` 的 `BaudRate` 已设为 `9600U`，不要改成 115200。

2. **WBR3 无需烧录任何固件**：出厂已刷好涂鸦固件，只需 PY32 烧录成功后，通过"涂鸦智能" App 完成 2.4GHz WiFi 配网（**WBR3 不支持 5GHz**），设备即可上线显示温湿度，每 30 秒刷新一次。

3. **硬件接线确认**：

   | PY32 引脚 | 连接到 | 说明 |
   |-----------|--------|------|
   | PA0 | DHT11 DATA | 需 4.7kΩ 上拉到 3.3V |
   | PA2 (USART1_TX) | WBR3 **RX** | 交叉连接 |
   | PA3 (USART1_RX) | WBR3 **TX** | 交叉连接 |
   | PA13 (SWDIO) | DAPLink SWDIO | 烧录用 |
   | PA14 (SWDCK) | DAPLink SWDCK | 烧录用 |
   | 3.3V | WBR3 VCC | ⚠️ 绝对不能接 5V |

4. **配网步骤**：
   - 下载"涂鸦智能"或"Smart Life" App
   - 手机连接 2.4GHz WiFi
   - PY32 + WBR3 上电，等约 3 秒握手完成
   - App → 添加设备 → 自动发现
   - 输入 WiFi 密码，等待配网完成
   - 设备出现在 App 首页，显示温湿度

5. **DP ID 定义**（来自涂鸦平台自动生成）：

   | DP ID | 宏名 | 含义 |
   |-------|------|------|
   | `0x01` | `DPID_TEMP_CURRENT` | 当前温度（°C，整数） |
   | `0x02` | `DPID_HUMIDITY_VALUE` | 当前湿度（%RH，整数） |

---

## 九、调试模式 vs 运行模式（2026-06-01 增补）

### 三种模式必须切换

PY32F030K28 的 USART1 在 **PA2/PA3** 上，被三个角色共用：
ISP 烧录的 USB-TTL / 运行时的 WBR3 / 调试时的 XCOM 串口监视器。
**任意时刻只能接其中一个**，靠 BOOT0(PF4) 状态 + 拔插跳线切换。

| 模式 | BOOT0 (PF4) | PA2/PA3 接谁 | 用途 |
|------|-------------|--------------|------|
| ① ISP 烧录 | 短接 3V3，再重新上电 | USB-TTL | 用 PY32 ISP Tool 下载 hex |
| ② 调试运行 | 悬空 / GND，重新上电 | USB-TTL | XCOM 看 `[BOOT]` 横幅 + `[DHT11] T=.. H=..` |
| ③ 联网运行 | 悬空 / GND | WBR3（交叉接） | 涂鸦 App 看温湿度 |

> ❗ `ISP Tool` 显示 `Connect PASS` **不等于程序在跑**。必须断电 → 拆 PF4↔3V3 短接 → 再上电，否则永远在 BootROM 里。

### 心跳 LED 是"程序在跑"的物理证据

- 引脚由 [`Inc/main.h`](Inc/main.h) 顶部的 `LED_GPIO_PORT/LED_GPIO_PIN` 控制，默认 **PB2**。
- 若你的板子 LED 是 PC13/PA15，改一行宏即可。
- LED 不闪 → 100% 是 BOOT0 没拔 / LED 引脚配错 / 卡死在 `APP_ErrorHandler`。

### XCOM 调试日志重定向

- `DEBUG_UART_PRINTF=1` 时，[`User/uart.c`](User/uart.c) 末尾的 `fputc`(Keil) / `_write`(GCC) 把 `printf` 重定向到 USART1。
- Keil 用户必须勾选 **Options → Target → Use MicroLIB**，否则 `__use_no_semihosting` 报错。
- XCOM 配置：**9600 8-N-1 无流控**。看到下面就成功：
  ```
  [BOOT] PY32 + WBR3 + DHT11 demo, baud=9600, build=Jun  1 2026 12:34:56
  [DHT11] T=25 C, H=60 %RH
  ```
- XCOM 里偶尔夹杂 `55 AA 03 07 …` 的二进制字节是涂鸦 DP 帧，正常现象。
