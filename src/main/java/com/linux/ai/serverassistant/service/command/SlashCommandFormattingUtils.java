package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.CommandMarkers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown formatters shared by slash-command routes.
 */
final class SlashCommandFormattingUtils {

    private static final String SECTION_GPU = "#GPU";
    private static final String SECTION_GPU_UUID = "#GPU_UUID";
    private static final String SECTION_PROCESSES = "#PROCESSES";
    private static final String SECTION_NVIDIA_SMI_PROCESSES = "#NVIDIA_SMI_PROCESSES";

    private SlashCommandFormattingUtils() {}

    static String formatSystemOverviewAsMarkdown(Map<String, Object> overview, String warningKey, Pattern diskLine) {
        StringBuilder sb = new StringBuilder();
        sb.append("**系統狀態**\n\n");
        sb.append("| 項目 | 值 |\n");
        sb.append("| --- | --- |\n");

        appendRow(sb, "掃描時間", overview != null ? overview.get("timestamp") : null);
        Object warning = overview != null ? overview.get(warningKey) : null;
        if (warning != null && !String.valueOf(warning).isBlank()) {
            appendRow(sb, "警告", warning);
        }
        appendRow(sb, "主機名", overview != null ? overview.get("hostname") : null);
        appendRow(sb, "系統", overview != null ? overview.get("os") : null);
        appendRow(sb, "運行時間", overview != null ? overview.get("uptime") : null);
        appendRow(sb, "CPU", overview != null ? overview.get("cpuModel") : null);
        appendRow(sb, "CPU 核心", formatCpuCores(overview));
        appendRow(sb, "CPU 使用率", overview != null ? overview.get("cpuUsagePercent") : null);
        appendRow(sb, "已用記憶體", overview != null ? overview.get("usedMemoryGB") : null);
        appendRow(sb, "記憶體使用率", overview != null ? overview.get("memoryUsagePercent") : null);
        appendRow(sb, "Swap (已用 / 總共)", overview != null ? overview.get("swapGB") : null);
        appendRow(sb, "CPU 溫度", overview != null ? overview.get("cpuTemp") : null);
        appendRow(sb, "Process / Thread", formatProcThreads(overview));

        Object netObj = (overview != null) ? overview.get("networkIfs") : null;
        if (netObj instanceof List<?> ifs && !ifs.isEmpty()) {
            sb.append("\n**網路**\n\n");
            sb.append("| 介面 | IPv4 | Speed | Rx | Tx |\n");
            sb.append("| --- | --- | --- | --- | --- |\n");
            for (Object o : ifs) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object iface = m.get("interface");
                Object ipv4 = m.get("ipv4");
                Object speed = m.get("speed");
                Object rx = m.get("rx");
                Object tx = m.get("tx");
                sb.append("| ")
                        .append(escapeCell(iface == null ? "-" : String.valueOf(iface)))
                        .append(" | ")
                        .append(escapeCell(ipv4 == null ? "-" : String.valueOf(ipv4)))
                        .append(" | ")
                        .append(escapeCell(speed == null ? "-" : String.valueOf(speed)))
                        .append(" | ")
                        .append(escapeCell(rx == null ? "-" : String.valueOf(rx)))
                        .append(" | ")
                        .append(escapeCell(tx == null ? "-" : String.valueOf(tx)))
                        .append(" |\n");
            }
        }

        Object ioByDiskObj = (overview != null) ? overview.get("diskIoByDisk") : null;
        if (ioByDiskObj instanceof List<?> ioList && !ioList.isEmpty()) {
            sb.append("\n**磁碟 I/O (每顆硬碟)**\n\n");
            sb.append("| 硬碟 | 型號 | 讀取 | 寫入 |\n");
            sb.append("| --- | --- | --- | --- |\n");
            for (Object o : ioList) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object disk = m.get("disk");
                Object model = m.get("model");
                Object read = m.get("readMBps");
                Object write = m.get("writeMBps");
                sb.append("| ")
                        .append(escapeCell(disk == null ? "-" : String.valueOf(disk)))
                        .append(" | ")
                        .append(escapeCell(model == null ? "-" : String.valueOf(model)))
                        .append(" | ")
                        .append(escapeCell(read == null ? "-" : String.valueOf(read)))
                        .append(" | ")
                        .append(escapeCell(write == null ? "-" : String.valueOf(write)))
                        .append(" |\n");
            }
        }

        Object diskMountsObj = (overview != null) ? overview.get("diskMounts") : null;
        if (diskMountsObj instanceof List<?> mounts && !mounts.isEmpty()) {
            sb.append("\n**磁碟**\n\n");
            sb.append("| 掛載點 | 總共(GB) | 剩餘(GB) | 使用率 | Inode |\n");
            sb.append("| --- | --- | --- | --- | --- |\n");

            for (Object o : mounts) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object mount = m.get("mount");
                Object total = m.get("totalGB");
                Object free = m.get("freeGB");
                Object use = m.get("usePct");
                Object inode = m.get("inodeUsePct");
                sb.append("| ")
                        .append(escapeCell(mount == null ? "-" : String.valueOf(mount)))
                        .append(" | ")
                        .append(escapeCell(total == null ? "-" : String.valueOf(total)))
                        .append(" | ")
                        .append(escapeCell(free == null ? "-" : String.valueOf(free)))
                        .append(" | ")
                        .append(escapeCell(use == null ? "-" : String.valueOf(use)))
                        .append(" | ")
                        .append(escapeCell(inode == null ? "-" : String.valueOf(inode)))
                        .append(" |\n");
            }
        } else {
            Object disksObj = (overview != null) ? overview.get("disks") : null;
            if (disksObj instanceof List<?> disks && !disks.isEmpty()) {
                sb.append("\n**磁碟**\n\n");
                sb.append("| 掛載點 | 總共(GB) | 剩餘(GB) | 使用率 |\n");
                sb.append("| --- | --- | --- | --- |\n");

                boolean anyParsed = false;
                StringBuilder fallback = new StringBuilder();
                for (Object o : disks) {
                    String line = (o == null) ? "" : String.valueOf(o).trim();
                    if (line.isEmpty()) continue;

                    Matcher m = diskLine.matcher(line);
                    if (m.matches()) {
                        anyParsed = true;
                        sb.append("| ")
                                .append(escapeCell(m.group(1).trim()))
                                .append(" | ")
                                .append(escapeCell(m.group(2).trim()))
                                .append(" | ")
                                .append(escapeCell(m.group(3).trim()))
                                .append(" | ")
                                .append(escapeCell(m.group(4).trim()))
                                .append("% |\n");
                    } else {
                        if (fallback.length() > 0) fallback.append('\n');
                        fallback.append(line);
                    }
                }

                if (!anyParsed && fallback.length() > 0) {
                    sb.append("\n");
                    for (String l : fallback.toString().split("\\r?\\n")) {
                        if (!l.isBlank()) sb.append("- ").append(l.trim()).append('\n');
                    }
                }
            }
        }

        return sb.toString().trim();
    }

    static String formatTopAsMarkdown(String title, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append("| PID | User | CPU% | Mem(GB) | Cmd |\n");
        sb.append("| --- | --- | --- | --- | --- |\n");
        if (rows == null || rows.isEmpty()) {
            sb.append("| - | - | - | - | - |\n");
            return sb.toString().trim();
        }
        for (Map<String, Object> r : rows) {
            Object pid = r.get("pid");
            Object user = r.get("user");
            Object cpu = r.get("cpuPercent");
            Object mem = r.get("memGB");
            if (mem == null) {
                Object memMb = r.get("memMB");
                mem = toGbString(memMb);
            }
            Object cmd = r.get("cmd");
            sb.append("| ")
                    .append(escapeCell(pid == null ? "-" : String.valueOf(pid)))
                    .append(" | ")
                    .append(escapeCell(user == null ? "-" : String.valueOf(user)))
                    .append(" | ")
                    .append(escapeCell(cpu == null ? "-" : String.valueOf(cpu)))
                    .append(" | ")
                    .append(escapeCell(mem == null ? "-" : String.valueOf(mem)))
                    .append(" | ")
                    .append(escapeCell(cmd == null ? "-" : String.valueOf(cmd)))
                    .append(" |\n");
        }
        return sb.toString().trim();
    }

    static String formatDockerSnapshotAsMarkdown(Map<String, Object> snap) {
        if (snap == null) return "**Docker 狀態**\n\n- -";

        StringBuilder sb = new StringBuilder();
        sb.append("**Docker 狀態**\n\n");

        String clientV = snap.get("clientVersion") == null ? "-" : String.valueOf(snap.get("clientVersion"));
        String serverV = snap.get("serverVersion") == null ? "-" : String.valueOf(snap.get("serverVersion"));
        String err = snap.get("error") == null ? null : String.valueOf(snap.get("error"));

        sb.append("| 項目 | 值 |\n");
        sb.append("| --- | --- |\n");
        sb.append("| client version | ").append(escapeCell(clientV)).append(" |\n");
        sb.append("| server version | ").append(escapeCell(serverV)).append(" |\n");
        if (err != null && !err.isBlank()) {
            sb.append("| error | ").append(escapeCell(err)).append(" |\n");
        }

        Object containersObj = snap.get("containers");
        if (containersObj instanceof List<?> containers) {
            sb.append("\n**containers**\n\n");
            if (containers.isEmpty()) {
                sb.append("- (none)\n");
            } else {
                sb.append("| Name | Image | Status | Ports |\n");
                sb.append("| --- | --- | --- | --- |\n");
                for (Object o : containers) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object name = m.get("name");
                    Object image = m.get("image");
                    Object status = m.get("status");
                    Object ports = m.get("ports");
                    sb.append("| ")
                            .append(escapeCell(name == null ? "-" : String.valueOf(name)))
                            .append(" | ")
                            .append(escapeCell(image == null ? "-" : String.valueOf(image)))
                            .append(" | ")
                            .append(escapeCell(status == null ? "-" : String.valueOf(status)))
                            .append(" | ")
                            .append(escapeCell(ports == null ? "-" : String.valueOf(ports)))
                            .append(" |\n");
                }
            }
        }

        Object statsObj = snap.get("stats");
        if (statsObj instanceof List<?> stats) {
            sb.append("\n**stats**\n\n");
            if (stats.isEmpty()) {
                sb.append("- (none)\n");
            } else {
                sb.append("| Name | CPU | Mem | Mem% |\n");
                sb.append("| --- | --- | --- | --- |\n");
                for (Object o : stats) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Object name = m.get("name");
                    Object cpu = m.get("cpu");
                    Object memUsage = m.get("memUsage");
                    Object memPercent = m.get("memPercent");
                    sb.append("| ")
                            .append(escapeCell(name == null ? "-" : String.valueOf(name)))
                            .append(" | ")
                            .append(escapeCell(cpu == null ? "-" : String.valueOf(cpu)))
                            .append(" | ")
                            .append(escapeCell(memUsage == null ? "-" : String.valueOf(memUsage)))
                            .append(" | ")
                            .append(escapeCell(memPercent == null ? "-" : String.valueOf(memPercent)))
                            .append(" |\n");
                }
            }
        }

        return sb.toString().trim();
    }

    static String formatPortsAsMarkdown(Map<String, Object> snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Port 狀態**\n\n");

        if (snap == null) {
            sb.append("- -\n");
            return sb.toString().trim();
        }

        Object errObj = snap.get("error");
        Object sourceObj = snap.get("source");
        String err = errObj == null ? null : String.valueOf(errObj);
        String source = sourceObj == null ? "-" : String.valueOf(sourceObj);

        sb.append("| 項目 | 值 |\n");
        sb.append("| --- | --- |\n");
        sb.append("| source | ").append(escapeCell(source)).append(" |\n");
        if (err != null && !err.isBlank()) {
            sb.append("| error | ").append(escapeCell(err)).append(" |\n");
        }

        Object rowsObj = snap.get("rows");
        sb.append("\n**listening**\n\n");
        sb.append("| 協定 | 狀態 | 位址 | Port |\n");
        sb.append("| --- | --- | --- | --- |\n");

        if (!(rowsObj instanceof List<?> rows) || rows.isEmpty()) {
            sb.append("| - | - | - | - |\n");
            return sb.toString().trim();
        }

        int limit = Math.min(rows.size(), 100);
        for (int i = 0; i < limit; i++) {
            Object o = rows.get(i);
            if (!(o instanceof Map<?, ?> m)) continue;
            Object proto = m.get("proto");
            Object state = m.get("state");
            Object addr = m.get("addr");
            Object port = m.get("port");
            sb.append("| ")
                    .append(escapeCell(proto == null ? "-" : String.valueOf(proto)))
                    .append(" | ")
                    .append(escapeCell(state == null ? "-" : String.valueOf(state)))
                    .append(" | ")
                    .append(escapeCell(addr == null ? "-" : String.valueOf(addr)))
                    .append(" | ")
                    .append(escapeCell(port == null ? "-" : String.valueOf(port)))
                    .append(" |\n");
        }

        if (rows.size() > limit) {
            sb.append("\n- (顯示前 ").append(limit).append(" 筆，共 ").append(rows.size()).append(" 筆)\n");
        }

        return sb.toString().trim();
    }

    static String formatGpuStatusAsMarkdown(String raw, Pattern nvidiaSmiHeaderPattern) {
        if (raw == null) return "未取得 GPU 資訊。";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "未取得 GPU 資訊。";

        if (trimmed.startsWith("[ERROR]") || trimmed.startsWith(CommandMarkers.SECURITY_VIOLATION) || trimmed.startsWith("未偵測到")) {
            return trimmed;
        }

        List<String> lines = trimmed.lines()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        if (lines.isEmpty()) return "未取得 GPU 資訊。";
        for (String line : lines) {
            if (line.startsWith("[ERROR]") || line.startsWith(CommandMarkers.SECURITY_VIOLATION)) {
                return line;
            }
        }

        boolean hasMarkers = lines.stream().anyMatch(l ->
                l.equalsIgnoreCase(SECTION_GPU)
                        || l.equalsIgnoreCase(SECTION_GPU_UUID)
                        || l.equalsIgnoreCase(SECTION_PROCESSES)
                        || l.equalsIgnoreCase(SECTION_NVIDIA_SMI_PROCESSES));

        if (hasMarkers) {
            StringBuilder sb = new StringBuilder();
            sb.append("**GPU 狀態**\n\n");

            String headerLine = lines.stream()
                    .filter(l -> l.toUpperCase(Locale.ROOT).contains("NVIDIA-SMI") && l.contains("Driver Version:") && l.contains("CUDA Version:"))
                    .findFirst()
                    .orElse(null);
            if (headerLine != null) {
                appendNvidiaHeaderTable(sb, headerLine, nvidiaSmiHeaderPattern);
                sb.append("\n");
            }

            Sections sec = splitSections(lines);

            if (!sec.gpu().isEmpty()) {
                sb.append(formatNvidiaGpuQueryAsTable(sec.gpu())).append("\n\n");
            }
            sb.append(formatNvidiaProcessesAsTable(sec.uuidMap(), sec.procs(), sec.nvidiaSmiProcesses()));
            return sb.toString().trim();
        }

        if (lines.get(0).toLowerCase(Locale.ROOT).startsWith("name,vendor,vram")) {
            return formatCsvWithHeaderAsTable("**GPU 狀態**", lines);
        }

        boolean looksLikeNvidia = lines.stream().anyMatch(l -> l.contains(",") && l.split("\\s*,\\s*").length >= 8);
        if (looksLikeNvidia) {
            return formatNvidiaGpuQueryAsTable(lines);
        }

        return "**GPU 狀態**\n\n```text\n" + trimmed + "\n```";
    }

    private static String toGbString(Object memMb) {
        if (memMb == null) return "-";
        try {
            double mb = Double.parseDouble(String.valueOf(memMb).trim());
            double gb = mb / 1024d;
            return String.format("%.2f", gb);
        } catch (Exception ignored) {
            return String.valueOf(memMb);
        }
    }

    private static String formatCpuCores(Map<String, Object> overview) {
        if (overview == null) return null;
        Object logical = overview.get("cpuCoresLogical");
        Object physical = overview.get("cpuCoresPhysical");
        if (logical == null && physical == null) return null;
        if (logical != null && physical != null) {
            return String.valueOf(physical) + " physical / " + String.valueOf(logical) + " logical";
        }
        return (logical != null) ? (String.valueOf(logical) + " logical") : (String.valueOf(physical) + " physical");
    }

    private static String formatProcThreads(Map<String, Object> overview) {
        if (overview == null) return null;
        Object p = overview.get("processCount");
        Object t = overview.get("threadCount");
        if (p == null && t == null) return null;
        if (p != null && t != null) return String.valueOf(p) + " / " + String.valueOf(t);
        return (p != null) ? String.valueOf(p) : String.valueOf(t);
    }

    private static void appendRow(StringBuilder sb, String key, Object value) {
        String v = (value == null) ? "-" : String.valueOf(value);
        sb.append("| ").append(escapeCell(key)).append(" | ").append(escapeCell(v)).append(" |\n");
    }

    private static String escapeCell(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", "<br/>");
    }

    private static String formatCsvWithHeaderAsTable(String title, List<String> lines) {
        String[] header = lines.get(0).split("\\s*,\\s*");
        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");
        sb.append("| ");
        for (int i = 0; i < header.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(escapeCell(header[i]));
        }
        sb.append(" |\n| ");
        for (int i = 0; i < header.length; i++) {
            if (i > 0) sb.append(" | ");
            sb.append("---");
        }
        sb.append(" |\n");

        for (int li = 1; li < lines.size(); li++) {
            String[] cols = lines.get(li).split("\\s*,\\s*");
            if (cols.length == 0) continue;
            sb.append("| ");
            for (int i = 0; i < header.length; i++) {
                if (i > 0) sb.append(" | ");
                String v = (i < cols.length) ? cols[i] : "-";
                sb.append(escapeCell(v));
            }
            sb.append(" |\n");
        }
        return sb.toString().trim();
    }

    private static String formatNvidiaGpuQueryAsTable(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("**GPU**\n\n");
        sb.append(formatNvidiaGpuQueryAsTableWithoutTitle(lines));
        return sb.toString().trim();
    }

    private static String formatNvidiaGpuQueryAsTableWithoutTitle(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        sb.append("| GPU | Temp (°C) | GPU% | Mem% | VRAM | Power |\n");
        sb.append("| --- | --- | --- | --- | --- | --- |\n");

        for (String line : lines) {
            String[] cols = line.split("\\s*,\\s*");
            if (cols.length < 2) continue;

            String idx = cols[0];
            String name = cols[1];
            String temp = cols.length > 2 ? withCelsius(cols[2]) : "-";
            String util = cols.length > 3 ? cols[3] : "-";
            String memUtil = cols.length > 4 ? cols[4] : "-";
            String memUsed = cols.length > 5 ? cols[5] : "-";
            String memTotal = cols.length > 6 ? cols[6] : "-";
            String pDraw = cols.length > 7 ? cols[7] : "-";
            String pLimit = cols.length > 8 ? cols[8] : "-";

            String vram = memUsed + " / " + memTotal;
            String power = pDraw + " / " + pLimit;

            sb.append("| ")
                    .append(escapeCell(idx + " " + name))
                    .append(" | ")
                    .append(escapeCell(temp))
                    .append(" | ")
                    .append(escapeCell(util))
                    .append(" | ")
                    .append(escapeCell(memUtil))
                    .append(" | ")
                    .append(escapeCell(vram))
                    .append(" | ")
                    .append(escapeCell(power))
                    .append(" |\n");
        }

        return sb.toString().trim();
    }

    private record Sections(List<String> gpu, List<String> uuidMap, List<String> procs, List<String> nvidiaSmiProcesses) {}

    private static Sections splitSections(List<String> rest) {
        ArrayList<String> gpu = new ArrayList<>();
        ArrayList<String> uuid = new ArrayList<>();
        ArrayList<String> procs = new ArrayList<>();
        ArrayList<String> smiProcs = new ArrayList<>();

        String cur = null;
        for (String l : rest) {
            if (l.equalsIgnoreCase(SECTION_GPU)) { cur = SECTION_GPU; continue; }
            if (l.equalsIgnoreCase(SECTION_GPU_UUID)) { cur = SECTION_GPU_UUID; continue; }
            if (l.equalsIgnoreCase(SECTION_PROCESSES)) { cur = SECTION_PROCESSES; continue; }
            if (l.equalsIgnoreCase(SECTION_NVIDIA_SMI_PROCESSES)) { cur = SECTION_NVIDIA_SMI_PROCESSES; continue; }

            if (cur == null) continue;
            if (cur.equals(SECTION_GPU)) gpu.add(l);
            else if (cur.equals(SECTION_GPU_UUID)) uuid.add(l);
            else if (cur.equals(SECTION_PROCESSES)) procs.add(l);
            else if (cur.equals(SECTION_NVIDIA_SMI_PROCESSES)) smiProcs.add(l);
        }

        return new Sections(gpu, uuid, procs, smiProcs);
    }

    private static void appendNvidiaHeaderTable(StringBuilder sb, String headerLine, Pattern nvidiaSmiHeaderPattern) {
        Matcher hm = nvidiaSmiHeaderPattern.matcher(headerLine);
        if (hm.matches()) {
            sb.append("| 項目 | 值 |\n");
            sb.append("| --- | --- |\n");
            sb.append("| NVIDIA-SMI | ").append(escapeCell(hm.group(1))).append(" |\n");
            sb.append("| Driver Version | ").append(escapeCell(hm.group(2))).append(" |\n");
            sb.append("| CUDA Version | ").append(escapeCell(hm.group(3))).append(" |\n");
        } else {
            sb.append("```text\n").append(headerLine).append("\n```");
        }
    }

    private static String withCelsius(String rawTemp) {
        if (rawTemp == null) return "-";
        String t = rawTemp.trim();
        if (t.isEmpty()) return "-";
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.contains("c") || lower.contains("°")) return t;
        if (t.matches("^-?\\d+(\\.\\d+)?$")) return t + "°C";
        return t;
    }

    private static String formatNvidiaProcessesAsTable(List<String> uuidLines, List<String> procLines, List<String> nvidiaSmiProcBlockLines) {
        HashMap<String, String> uuidToGpu = new HashMap<>();
        for (String line : uuidLines) {
            String[] c = line.split("\\s*,\\s*");
            if (c.length < 2) continue;
            String uuid = c[0];
            String idx = c[1];
            String name = (c.length > 2) ? c[2] : "";
            String label = idx + (name.isBlank() ? "" : (" " + name));
            uuidToGpu.put(uuid, label.trim());
        }

        ArrayList<String[]> rows = new ArrayList<>();
        for (String line : procLines) {
            String[] c = line.split("\\s*,\\s*");
            if (c.length < 2) continue;
            String gpuUuid = c[0];
            String pid = c[1];
            String pname = c.length > 2 ? c[2] : "-";
            String mem = c.length > 3 ? c[3] : "-";
            String gpu = uuidToGpu.getOrDefault(gpuUuid, gpuUuid);
            rows.add(new String[]{gpu, pid, pname, mem});
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Processes**\n\n");
        if (rows.isEmpty()) {
            if (procLines != null && !procLines.isEmpty()) {
                sb.append("```text\n").append(String.join("\n", procLines)).append("\n```\n");
                return sb.toString().trim();
            }

            String fallback = formatNvidiaSmiProcessesBlockAsTable(nvidiaSmiProcBlockLines);
            if (fallback != null) {
                sb.append(fallback);
                return sb.toString().trim();
            }

            sb.append("- (none)\n");
            return sb.toString().trim();
        }

        int limit = Math.min(rows.size(), 30);
        sb.append("| GPU | PID | Process | VRAM |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (int i = 0; i < limit; i++) {
            String[] r = rows.get(i);
            sb.append("| ")
                    .append(escapeCell(r[0]))
                    .append(" | ")
                    .append(escapeCell(r[1]))
                    .append(" | ")
                    .append(escapeCell(r[2]))
                    .append(" | ")
                    .append(escapeCell(r[3]))
                    .append(" |\n");
        }
        if (rows.size() > limit) {
            sb.append("\n").append("- (顯示前 ").append(limit).append(" 筆，共 ").append(rows.size()).append(" 筆)\n");
        }
        return sb.toString().trim();
    }

    private static String formatNvidiaSmiProcessesBlockAsTable(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;

        ArrayList<String[]> rows = new ArrayList<>();

        Pattern withGiCi = Pattern.compile(
                "^\\|\\s*(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+([A-Za-z])\\s+(.+?)\\s+(\\d+)\\s*MiB\\s*\\|\\s*$");
        Pattern withoutGiCi = Pattern.compile(
                "^\\|\\s*(\\d+)\\s+(\\d+)\\s+([A-Za-z])\\s+(.+?)\\s+(\\d+)\\s*MiB\\s*\\|\\s*$");

        for (String raw : lines) {
            if (raw == null) continue;
            String t = raw.trim();
            if (!t.startsWith("|")) continue;
            if (t.contains("Process name") || t.contains("GPU Memory") || t.contains("Processes:")) continue;
            if (t.startsWith("+")) continue;

            Matcher m1 = withGiCi.matcher(t);
            if (m1.matches()) {
                String gpu = m1.group(1);
                String pid = m1.group(4);
                String pname = m1.group(6).trim();
                String mem = m1.group(7) + " MiB";
                rows.add(new String[]{gpu, pid, pname, mem});
                continue;
            }

            Matcher m2 = withoutGiCi.matcher(t);
            if (m2.matches()) {
                String gpu = m2.group(1);
                String pid = m2.group(2);
                String pname = m2.group(4).trim();
                String mem = m2.group(5) + " MiB";
                rows.add(new String[]{gpu, pid, pname, mem});
            }
        }

        if (rows.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(rows.size(), 30);
        sb.append("| GPU | PID | Process | VRAM |\n");
        sb.append("| --- | --- | --- | --- |\n");
        for (int i = 0; i < limit; i++) {
            String[] r = rows.get(i);
            sb.append("| ")
                    .append(escapeCell(r[0]))
                    .append(" | ")
                    .append(escapeCell(r[1]))
                    .append(" | ")
                    .append(escapeCell(r[2]))
                    .append(" | ")
                    .append(escapeCell(r[3]))
                    .append(" |\n");
        }
        if (rows.size() > limit) {
            sb.append("\n").append("- (顯示前 ").append(limit).append(" 筆，共 ").append(rows.size()).append(" 筆)\n");
        }
        return sb.toString().trim();
    }
}
