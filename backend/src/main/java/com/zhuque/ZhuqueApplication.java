package com.zhuque;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GateForge 后端入口（Java 包名保留 zhuque 以兼容历史迁移与部署）。
 *
 * 边界纪律（违背即错，见项目根 CLAUDE.md）：
 * - GateForge 是纯控制面：不在任何调用链上，不收发 MCP 协议消息，只生产静态 JSON 配置 + 证据记录
 * - 不实现：MCP 协议服务端、API 网关、服务注册表、配置版本存储、开发者门户
 * - 模块编号 M1~M10 对应 P2 规格，包名 m1_toolpool ... m10_org
 */
@SpringBootApplication
@EnableScheduling // M9 漂移检测的定时扫描
public class ZhuqueApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZhuqueApplication.class, args);
    }
}
