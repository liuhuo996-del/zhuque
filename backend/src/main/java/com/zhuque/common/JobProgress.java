package com.zhuque.common;

/**
 * 长任务进度（M1 批量富化、M6 测试运行、M9 手动扫描共用）。
 * 前端约定：长任务显示「百分比 + 当前步骤名」，不是转圈——所以 currentStep 必填。
 *
 * @param jobId       任务 id
 * @param total       总条数
 * @param done        已完成条数
 * @param currentStep 当前正在处理的对象名（如 "正在富化 get_customer_profile (12/183)"）
 * @param state       running | done | failed
 * @param error       失败时的「发生了什么 + 怎么修」
 */
public record JobProgress(String jobId, int total, int done, String currentStep, String state, String error) {
}
