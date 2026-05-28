/* USER: This file enables/disables HAL peripheral modules.
         Only modules listed here will be compiled in. */
#ifndef __PY32F0XX_HAL_CONF_H
#define __PY32F0XX_HAL_CONF_H

#ifdef __cplusplus
extern "C" {
#endif

/* ======================== Module Selection ================================ */
#define HAL_MODULE_ENABLED
#define HAL_RCC_MODULE_ENABLED
#define HAL_FLASH_MODULE_ENABLED
#define HAL_GPIO_MODULE_ENABLED
#define HAL_DMA_MODULE_ENABLED
#define HAL_PWR_MODULE_ENABLED
#define HAL_UART_MODULE_ENABLED
#define HAL_CORTEX_MODULE_ENABLED

/* ======================== Oscillator Values =============================== */
#if !defined(HSI_VALUE)
  #define HSI_VALUE    ((uint32_t)8000000U)   /* 8 MHz internal RC */
#endif
#if !defined(HSE_VALUE)
  #define HSE_VALUE    ((uint32_t)24000000U)  /* 24 MHz external crystal */
#endif
#if !defined(HSE_STARTUP_TIMEOUT)
  #define HSE_STARTUP_TIMEOUT    ((uint32_t)200U)
#endif
#if !defined(LSI_VALUE)
  #define LSI_VALUE    ((uint32_t)32768U)
#endif
#if !defined(LSE_VALUE)
  #define LSE_VALUE    ((uint32_t)32768U)
#endif
#if !defined(LSE_STARTUP_TIMEOUT)
  #define LSE_STARTUP_TIMEOUT    ((uint32_t)5000U)
#endif

/* ======================== System Configuration ============================ */
#define VDD_VALUE                    ((uint32_t)3300U)  /* mV */
#define PRIORITY_HIGHEST             0
#define PRIORITY_HIGH                1
#define PRIORITY_LOW                 2
#define PRIORITY_LOWEST              3
#define TICK_INT_PRIORITY            ((uint32_t)PRIORITY_LOWEST)
#define USE_RTOS                     0
#define PREFETCH_ENABLE              0

/* Uncomment to enable HAL_assert checking (useful while debugging) */
/* #define USE_FULL_ASSERT    1U */

/* ======================== Includes (keep order) =========================== */
#ifdef HAL_MODULE_ENABLED
  #include "py32f0xx_hal.h"
#endif
#ifdef HAL_RCC_MODULE_ENABLED
  #include "py32f0xx_hal_rcc.h"
  #include "py32f0xx_hal_rcc_ex.h"
#endif
#ifdef HAL_GPIO_MODULE_ENABLED
  #include "py32f0xx_hal_gpio.h"
  #include "py32f0xx_hal_gpio_ex.h"
#endif
#ifdef HAL_CORTEX_MODULE_ENABLED
  #include "py32f0xx_hal_cortex.h"
#endif
#ifdef HAL_DMA_MODULE_ENABLED
  #include "py32f0xx_hal_dma.h"
#endif
#ifdef HAL_FLASH_MODULE_ENABLED
  #include "py32f0xx_hal_flash.h"
#endif
#ifdef HAL_PWR_MODULE_ENABLED
  #include "py32f0xx_hal_pwr.h"
#endif
#ifdef HAL_UART_MODULE_ENABLED
  #include "py32f0xx_hal_uart.h"
#endif

/* assert_param: disabled by default to save code space */
#define assert_param(expr)    ((void)0U)

#ifdef USE_FULL_ASSERT
  #undef  assert_param
  #define assert_param(expr)  ((expr) ? (void)0U : assert_failed((uint8_t *)__FILE__, __LINE__))
  void assert_failed(uint8_t *file, uint32_t line);
#endif

#ifdef __cplusplus
}
#endif

#endif /* __PY32F0XX_HAL_CONF_H */
