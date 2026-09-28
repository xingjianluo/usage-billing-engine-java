package com.github.xingjianluo.usagebillingenginejava.web.dto;

/** 结算后补差触发请求：period 可空（默认当前周期），batchId 为上报方幂等键（可空）。 */
public record AdjustmentRequest(String period, String batchId) {
}
