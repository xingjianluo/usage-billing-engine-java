package com.github.xingjianluo.usagebillingenginejava.model;

/** 上报结果：ACCEPTED 新接受；DUPLICATE 幂等命中；IGNORED 落在当前周期窗口外（迟到但保留记录）。 */
public enum IngestResult {
    ACCEPTED,
    DUPLICATE,
    REJECTED
}
