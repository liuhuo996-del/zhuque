package com.zhuque.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AgentNamesTest {

    @Test
    void buildsHigressSseEndpointFromNormalizedBaseUrl() {
        assertEquals(
                "http://localhost:8080/mcp/mcp-finance-refund-assistant/sse",
                AgentNames.mcpUrl(" http://localhost:8080/mcp/// ", "finance", "refund-assistant"));
        assertEquals(
                "mcp-finance-refund-assistant/sse",
                AgentNames.mcpUrl(null, "finance", "refund-assistant"));
    }

    @Test
    void extractsServiceNameFromNewSseEndpoint() {
        assertEquals(
                "mcp-finance-refund-assistant",
                AgentNames.serviceNameFromUrl(
                        "http://localhost:8080/mcp/mcp-finance-refund-assistant/sse/"));
        assertEquals(
                "mcp-finance-refund-assistant",
                AgentNames.serviceNameFromUrl(
                        "http://localhost:8080/mcp/mcp-finance-refund-assistant/sse?tenant=finance#tools"));
    }

    @Test
    void remainsCompatibleWithLegacyEndpoint() {
        assertEquals(
                "mcp-finance-refund-assistant",
                AgentNames.serviceNameFromUrl(
                        "http://localhost:8080/mcp/mcp-finance-refund-assistant/"));
        assertEquals(
                "mcp-finance-refund-assistant",
                AgentNames.serviceNameFromUrl("mcp-finance-refund-assistant"));
    }

    @Test
    void rejectsBlankEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> AgentNames.serviceNameFromUrl("  "));
    }
}
