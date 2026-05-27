package com.sc.supplychain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** FIFO 批次分配结果（单批次出库量） */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchAllocation {

    private Long batchId;
    private String batchNo;
    private int allocateQty;
}

