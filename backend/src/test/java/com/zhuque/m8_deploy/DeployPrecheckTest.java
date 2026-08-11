package com.zhuque.m8_deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.zhuque.common.ApiException;

class DeployPrecheckTest {

    @Test
    void environmentCheckOnlyRequiresNacosMcpAdminApi() {
        DeployPrecheck precheck = configured(new StubNacos(false));

        List<DeployPrecheck.CheckItem> checks = precheck.checkEnvironment();

        assertEquals(1, checks.size());
        assertEquals("Nacos Admin API", checks.get(0).name());
        assertTrue(checks.get(0).ok());
    }

    @Test
    void unreachableNacosBlocksPublishingWithoutProbingHigress() {
        DeployPrecheck precheck = configured(new StubNacos(true));

        DeployPrecheck.CheckItem check = precheck.checkEnvironment().get(0);

        assertFalse(check.ok());
        assertEquals("unreachable", check.current());
    }

    private static DeployPrecheck configured(StubNacos nacos) {
        DeployPrecheck precheck = new DeployPrecheck(null, nacos);
        ReflectionTestUtils.setField(precheck, "defaultNacosVersion", "3.0.1");
        return precheck;
    }

    private static final class StubNacos extends NacosTarget {
        private final boolean fail;

        StubNacos(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String probeVersion() {
            if (fail) {
                throw ApiException.unavailable("Nacos unavailable", "start Nacos");
            }
            return "3.0.1";
        }
    }
}
