package com.github.xingjianluo.usagebillingenginejava.domain;

/**
 * 用量上报的判定结果，保证重放时结论一致、可解释。
 */
public enum IngestStatus {
    ACCEPTED,
    DUPLICATE,
    REJECTED
}
