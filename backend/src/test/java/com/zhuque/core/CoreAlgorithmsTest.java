package com.zhuque.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.zhuque.common.ApiException;
import com.zhuque.common.CanonicalJson;
import com.zhuque.m4_closure.ClosureChecker;
import com.zhuque.m4_closure.FieldNormalizer;
import com.zhuque.m5_release.ReleaseStateMachine;
import com.zhuque.m5_release.VersionSuggester;

class CoreAlgorithmsTest {

    @Test
    void canonicalJsonIsStableAndNormalizesNumbers() {
        String left = CanonicalJson.sha256(Map.of("b", 1.0, "a", Map.of("x", 2)));
        String right = CanonicalJson.sha256(Map.of("a", Map.of("x", 2), "b", 1));
        assertEquals(left, right);
    }

    @Test
    void fieldNormalizerSeparatesExactFromFuzzyAliases() {
        FieldNormalizer normalizer = new FieldNormalizer();
        assertEquals("order_id", normalizer.normalize("orders[].orderId"));
        assertEquals(1, normalizer.matches("order_id", "data.orders[].order_id").confidence());
        assertEquals(0.82, normalizer.matches("order_no", "order_id").confidence());
        assertTrue(normalizer.matches("customer_id", "user_id").matched());
    }

    @Test
    void closureUsesFixedPointAndDoesNotSilentlyAcceptFuzzyMatches() {
        FieldNormalizer normalizer = new FieldNormalizer();
        ClosureChecker checker = new ClosureChecker(normalizer);
        UUID lookup = UUID.randomUUID();
        UUID refund = UUID.randomUUID();
        var tools = List.of(
                new ClosureChecker.ToolNode(lookup, "lookup", List.of("phone"), List.of("order_id")),
                new ClosureChecker.ToolNode(refund, "refund", List.of("order_id"), List.of("refund_id")));
        var closed = checker.check(new ClosureChecker.ClosureInput(tools, Set.of("phone"), Set.of()), tools);
        assertEquals("CLOSED", closed.conclusion());
        assertTrue(closed.unreachableTools().isEmpty());

        var fuzzyOnly = List.of(new ClosureChecker.ToolNode(refund, "refund", List.of("order_no"), List.of()));
        var blocked = checker.check(new ClosureChecker.ClosureInput(fuzzyOnly, Set.of("order_id"), Set.of()), tools);
        assertEquals("BLOCKED", blocked.conclusion());
        assertFalse(blocked.fuzzyMatches().isEmpty());
    }

    @Test
    void releaseStateMachineRejectsSkippingHumanApproval() {
        ReleaseStateMachine machine = new ReleaseStateMachine();
        machine.assertTransition("tested", "approved");
        assertTrue(machine.isFrozen("candidate"));
        assertThrows(ApiException.class, () -> machine.assertTransition("tested", "released"));
        assertThrows(ApiException.class, () -> machine.assertTransition("rolled_back", "released"));
    }

    @Test
    void semanticVersionUsesHighestChangeSeverity() {
        VersionSuggester suggester = new VersionSuggester();
        Map<String, Object> previous = manifest(tool("read_order", List.of("id"), Map.of("id", Map.of("type", "string")), "old"));
        Map<String, Object> added = manifest(
                tool("read_order", List.of("id"), Map.of("id", Map.of("type", "string")), "old"),
                tool("list_orders", List.of(), Map.of(), "list"));
        assertEquals("minor", suggester.suggest(previous, added, "v1.0.0").bumpLevel());

        Map<String, Object> changedType = manifest(tool("read_order", List.of("id"),
                Map.of("id", Map.of("type", "integer")), "old"));
        assertEquals("major", suggester.suggest(previous, changedType, "v1.0.0").bumpLevel());
    }

    private static Map<String, Object> manifest(Map<String, Object>... tools) {
        return Map.of("tools", List.of(tools));
    }

    private static Map<String, Object> tool(String name, List<String> required,
                                             Map<String, Object> properties, String description) {
        return Map.of("name", name, "description", description,
                "inputSchema", Map.of("type", "object", "required", required, "properties", properties));
    }
}
