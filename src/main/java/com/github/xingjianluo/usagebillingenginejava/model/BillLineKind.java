package com.github.xingjianluo.usagebillingenginejava.model;

/** 账单行类型：基础费按比例分摊、阶梯用量费、变更信用（负）。 */
public enum BillLineKind {
    PRORATED_BASE,
    TIERED_USAGE,
    CHANGE_CREDIT
}
