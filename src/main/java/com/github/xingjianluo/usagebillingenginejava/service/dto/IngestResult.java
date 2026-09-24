package com.github.xingjianluo.usagebillingenginejava.service.dto;

import com.github.xingjianluo.usagebillingenginejava.domain.IngestStatus;

/**
 * 用量上报结果。replaySafe=true 表示对同一输入重复调用结论一致。
 */
public record IngestResult(String eventId, IngestStatus status, String reason, boolean replaySafe) {
}
