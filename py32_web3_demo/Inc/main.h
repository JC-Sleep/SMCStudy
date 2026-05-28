#ifndef __MAIN_H
#define __MAIN_H

#include "py32f0xx_hal.h"
#include <stdint.h>
#include <string.h>

/* Called on unrecoverable errors: disables IRQ and hangs */
void APP_ErrorHandler(void);

#endif /* __MAIN_H */
