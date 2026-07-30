package com.zhuque.m1_toolpool;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M1 · 工具池 HTTP 入口。
 *
 * 路由规划（与前端 /tools 页对应）：
 *   POST /api/sources                 导入 OpenAPI（URL 或上传原文）→ 解析 → 生成草稿 → 落库
 *   POST /api/sources/{id}/refetch    重新拉取 spec（条目级 diff，见 SpecSyncService）
 *   POST /api/tools/enrich            批量富化，body 传 toolIds，返回 jobId
 *   GET  /api/jobs/{jobId}            查询长任务进度
 *   POST /api/tools/{id}/review       人工确认 → reviewed
 *   GET  /api/tools?source=&effect=&enrichment=&sensitive=&referenced=   列表 + 筛选
 *
 * 错误响应纪律：全部返回「发生了什么 + 怎么修」两段结构
 * （前端 ErrorState 组件直接渲染 what/fix 两个字段）。
 */
@RestController
@RequestMapping("/api")
public class ToolPoolController {

    private final OpenApiParser parser;
    private final ToolDraftGenerator draftGenerator;
    private final EnrichmentService enrichmentService;
    private final SpecSyncService specSyncService;

    public ToolPoolController(OpenApiParser parser, ToolDraftGenerator draftGenerator,
                              EnrichmentService enrichmentService, SpecSyncService specSyncService) {
        this.parser = parser;
        this.draftGenerator = draftGenerator;
        this.enrichmentService = enrichmentService;
        this.specSyncService = specSyncService;
    }

    /** 导入入口：解析（容错，逐 endpoint 报错）→ 草稿 → output_fields → 静态标注 → 落库 */
    @PostMapping("/sources")
    public Object importSource() {
        throw new UnsupportedOperationException("TODO");
    }
}
