package com.linux.ai.serverassistant.service.system;

import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Network Monitoring Service
 *
 * Responsibilities:
 * - Monitor network interface status
 * - Provide network traffic statistics
 * - Query network interface information
 *
 * @author Claude Code - Phase 2 Refactoring
 */
@Service
public class NetworkMonitorService {

    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();

    /**
     * Gets the status of all network interfaces.
     *
     * @return List of network interface statuses
     */
    public List<Map<String, String>> getNetworkStatus() {
        return hal.getNetworkIFs().stream().map(nif -> {
            Map<String, String> map = new HashMap<>();
            map.put("interface", nif.getName());
            map.put("speed", String.format("%.1f Mbps", nif.getSpeed() / 1e6));
            map.put("received", String.format("%.2f MB", nif.getBytesRecv() / 1e6));
            map.put("sent", String.format("%.2f MB", nif.getBytesSent() / 1e6));
            return map;
        }).collect(Collectors.toList());
    }

    /**
     * Gets detailed information for a specific network interface.
     *
     * @param interfaceName Interface name (e.g., "eth0", "wlan0")
     * @return Detailed interface information, or null if not found
     */
    public Map<String, Object> getInterfaceDetails(String interfaceName) {
        return hal.getNetworkIFs().stream()
                .filter(nif -> nif.getName().equals(interfaceName))
                .findFirst()
                .map(nif -> {
                    Map<String, Object> details = new HashMap<>();
                    details.put("name", nif.getName());
                    details.put("displayName", nif.getDisplayName());
                    details.put("macAddress", nif.getMacaddr());
                    details.put("ipv4", String.join(", ", nif.getIPv4addr()));
                    details.put("ipv6", String.join(", ", nif.getIPv6addr()));
                    details.put("speed", nif.getSpeed());
                    details.put("mtu", nif.getMTU());
                    details.put("bytesReceived", nif.getBytesRecv());
                    details.put("bytesSent", nif.getBytesSent());
                    details.put("packetsReceived", nif.getPacketsRecv());
                    details.put("packetsSent", nif.getPacketsSent());
                    details.put("inErrors", nif.getInErrors());
                    details.put("outErrors", nif.getOutErrors());
                    return details;
                })
                .orElse(null);
    }

    /**
     * Gets a list of names for all network interfaces.
     *
     * @return List of interface names
     */
    public List<String> getInterfaceNames() {
        return hal.getNetworkIFs().stream()
                .map(NetworkIF::getName)
                .collect(Collectors.toList());
    }

    /**
     * Checks if a network interface exists.
     *
     * @param interfaceName Interface name
     * @return Returns true if it exists
     */
    public boolean interfaceExists(String interfaceName) {
        return hal.getNetworkIFs().stream()
                .anyMatch(nif -> nif.getName().equals(interfaceName));
    }

    /**
     * Gets total network traffic statistics.
     *
     * @return Map containing total received and sent bytes
     */
    public Map<String, Long> getTotalNetworkTraffic() {
        long totalReceived = 0;
        long totalSent = 0;

        for (NetworkIF nif : hal.getNetworkIFs()) {
            totalReceived += nif.getBytesRecv();
            totalSent += nif.getBytesSent();
        }

        Map<String, Long> traffic = new HashMap<>();
        traffic.put("totalReceived", totalReceived);
        traffic.put("totalSent", totalSent);
        traffic.put("totalReceivedMB", totalReceived / 1_048_576);
        traffic.put("totalSentMB", totalSent / 1_048_576);

        return traffic;
    }
}
