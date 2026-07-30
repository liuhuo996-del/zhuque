package com.zhuque.m5_release;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * M5 · 版本号建议。语义写死（不许项目内各自发挥）：
 *
 *   major：删工具 / 删必填参数 / 改工具名 / 改参数语义
 *   minor：加工具 / 加可选参数
 *   patch：改描述 / 改错误信息
 *
 * 冻结时自动与上一版 manifest 比对给出建议版本号，人可覆盖
 * （覆盖要记录：建议是什么、人改成了什么）。
 */
@Component
public class VersionSuggester {

    public record VersionSuggestion(String suggested, String bumpLevel, java.util.List<String> reasons) {}

    /**
     * 功能：diff 两版 manifest，按上表判级并给出建议版本号。
     * 首个版本固定 v1。reasons 逐条列出触发判级的变更
     * （如 "删除了工具 cancel_order → major"）。
     */
    public VersionSuggestion suggest(Map<String, Object> previousManifest, Map<String, Object> nextManifest,
                                     String previousVersion) {
        throw new UnsupportedOperationException("TODO");
    }
}
