package com.linux.ai.serverassistant.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
// CORS configuration centralized in SecurityConfig (whitelist mode)
public class SystemController {
    private static final Logger log = LoggerFactory.getLogger(SystemController.class);
    private static final String FALLBACK_LOOPBACK = "127.0.0.1";

    @GetMapping("/info")
    public Map<String, String> getSystemInfo() {
        String ip = resolveLocalIpv4();
        return Map.of("ip", ip);
    }

    String resolveLocalIpv4() {
        String primaryIp = resolvePrimaryRouteIpv4();
        if (primaryIp != null) {
            return primaryIp;
        }

        String scannedIp = scanNetworkInterfacesForIpv4();
        if (scannedIp != null) {
            return scannedIp;
        }

        return FALLBACK_LOOPBACK;
    }

    String resolvePrimaryRouteIpv4() {
        try {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.connect(InetAddress.getByName("1.1.1.1"), 53);
                InetAddress localAddress = socket.getLocalAddress();
                if (isUsableIpv4(localAddress)) {
                    return localAddress.getHostAddress();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve primary route IPv4, falling back to interface scan", e);
        }
        return null;
    }

    String scanNetworkInterfacesForIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (!isCandidateInterface(ni)) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (isUsableIpv4(address)) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to scan network interfaces for IPv4 address, fallback to loopback", e);
        }
        return null;
    }

    private boolean isCandidateInterface(NetworkInterface networkInterface) {
        try {
            return isCandidateInterface(
                    networkInterface.getName(),
                    networkInterface.isLoopback(),
                    networkInterface.isUp(),
                    networkInterface.isVirtual());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCandidateInterface(String interfaceName, boolean loopback, boolean up, boolean virtualInterface) {
        if (loopback || !up || virtualInterface) {
            return false;
        }

        return interfaceName != null
                && !interfaceName.startsWith("docker")
                && !interfaceName.startsWith("veth")
                && !interfaceName.startsWith("cni")
                && !interfaceName.startsWith("br-");
    }

    private boolean isUsableIpv4(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress();
    }
}
