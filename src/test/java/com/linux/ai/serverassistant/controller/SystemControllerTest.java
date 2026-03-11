package com.linux.ai.serverassistant.controller;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemControllerTest {

    private final SystemController controller = new SystemController();

    @Test
    void getSystemInfo_shouldAlwaysReturnIpField() {
        Map<String, String> info = controller.getSystemInfo();

        assertNotNull(info);
        assertTrue(info.containsKey("ip"));
        assertNotNull(info.get("ip"));
        assertFalse(info.get("ip").isBlank());
    }

    @Test
    void isUsableIpv4_shouldOnlyAcceptRoutableIpv4() throws Exception {
        assertTrue(invokeBoolean(
                "isUsableIpv4",
                new Class[]{InetAddress.class},
                InetAddress.getByName("192.0.2.10")));

        assertFalse(invokeBoolean(
                "isUsableIpv4",
                new Class[]{InetAddress.class},
                InetAddress.getByName("127.0.0.1")));

        assertFalse(invokeBoolean(
                "isUsableIpv4",
                new Class[]{InetAddress.class},
                InetAddress.getByName("169.254.10.2")));

        assertFalse(invokeBoolean(
                "isUsableIpv4",
                new Class[]{InetAddress.class},
                InetAddress.getByName("::1")));
    }

    @Test
    void isCandidateInterface_withNullInterface_shouldReturnFalse() throws Exception {
        boolean candidate = invokeBoolean("isCandidateInterface", new Class[]{java.net.NetworkInterface.class}, (Object) null);
        assertFalse(candidate);
    }

    @Test
    void resolveLocalIpv4_whenPrimaryRouteExists_shouldPreferPrimaryRoute() {
        TestableSystemController testable = new TestableSystemController("10.0.0.9", "10.0.0.10");
        assertEquals("10.0.0.9", testable.resolveLocalIpv4());
    }

    @Test
    void resolveLocalIpv4_whenPrimaryRouteMissing_shouldUseScannedInterface() {
        TestableSystemController testable = new TestableSystemController(null, "10.0.0.10");
        assertEquals("10.0.0.10", testable.resolveLocalIpv4());
    }

    @Test
    void resolveLocalIpv4_whenBothResolversMissing_shouldFallbackLoopback() {
        TestableSystemController testable = new TestableSystemController(null, null);
        assertEquals("127.0.0.1", testable.resolveLocalIpv4());
    }

    @Test
    void getSystemInfo_shouldExposeResolvedPrimaryIp() {
        TestableSystemController testable = new TestableSystemController("192.0.2.8", "198.51.100.3");

        Map<String, String> info = testable.getSystemInfo();

        assertEquals("192.0.2.8", info.get("ip"));
    }

    @Test
    void isCandidateInterface_byAttributes_shouldApplyPrefixAndStateRules() throws Exception {
        assertFalse(invokeCandidateByAttributes(null, false, true, false));
        assertFalse(invokeCandidateByAttributes("eth0", true, true, false));
        assertFalse(invokeCandidateByAttributes("eth0", false, false, false));
        assertFalse(invokeCandidateByAttributes("eth0", false, true, true));
        assertFalse(invokeCandidateByAttributes("docker0", false, true, false));
        assertFalse(invokeCandidateByAttributes("vethabc", false, true, false));
        assertFalse(invokeCandidateByAttributes("cni0", false, true, false));
        assertFalse(invokeCandidateByAttributes("br-123", false, true, false));
        assertTrue(invokeCandidateByAttributes("eth0", false, true, false));
    }

    @Test
    void resolveLocalIpv4_withRealEnvironment_shouldProvideTrimmedNonBlankIp() {
        String ip = controller.resolveLocalIpv4();
        assertNotNull(ip);
        assertFalse(ip.isBlank());
        assertEquals(ip, ip.trim());
    }

    private boolean invokeBoolean(String name, Class<?>[] argTypes, Object... args) throws Exception {
        Method method = SystemController.class.getDeclaredMethod(name, argTypes);
        method.setAccessible(true);
        return (boolean) method.invoke(controller, args);
    }

    private boolean invokeCandidateByAttributes(String name, boolean loopback, boolean up, boolean virtualInterface) throws Exception {
        Method method = SystemController.class.getDeclaredMethod(
                "isCandidateInterface",
                String.class,
                boolean.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(controller, name, loopback, up, virtualInterface);
    }

    private static final class TestableSystemController extends SystemController {
        private final String primary;
        private final String scanned;

        private TestableSystemController(String primary, String scanned) {
            this.primary = primary;
            this.scanned = scanned;
        }

        @Override
        String resolvePrimaryRouteIpv4() {
            return primary;
        }

        @Override
        String scanNetworkInterfacesForIpv4() {
            return scanned;
        }
    }
}
