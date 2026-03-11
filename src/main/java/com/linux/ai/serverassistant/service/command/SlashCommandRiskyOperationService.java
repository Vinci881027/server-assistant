package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UsernameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SlashCommandRiskyOperationService {

    private static final int OFFLOAD_SUBMOUNT_HINT_LIMIT = 5;
    private static final Pattern OFFLOAD_CONFIRM_COMMAND = Pattern.compile(
            "^offload-confirm\\s+--source\\s+(/\\S+)\\s+--target\\s+(/\\S+)\\s*$");
    private static final Pattern MOUNT_CONFIRM_COMMAND = Pattern.compile(
            "^mount-confirm\\s+--device\\s+(/\\S+)\\s+--target\\s+(/\\S+)"
                    + "\\s+--fstype\\s+([a-zA-Z0-9._-]{1,32})"
                    + "\\s+--options\\s+([a-zA-Z0-9,=._:-]{1,120})\\s*$");
    private static final Pattern MOUNT_FSTYPE_TOKEN = Pattern.compile("^[a-zA-Z0-9._-]{1,32}$");
    private static final Pattern MOUNT_OPTIONS_TOKEN = Pattern.compile("^[a-zA-Z0-9,=._:-]{1,120}$");

    private final PendingConfirmationManager pendingConfirmationManager;

    @Autowired
    public SlashCommandRiskyOperationService(PendingConfirmationManager pendingConfirmationManager) {
        this.pendingConfirmationManager = Objects.requireNonNull(pendingConfirmationManager, "pendingConfirmationManager");
    }

    public String handleMount(String argsRaw, String sessionUser) {
        String args = argsRaw == null ? "" : argsRaw.trim();
        String username = UsernameUtils.normalizeUsernameOrAnonymous(sessionUser);
        if ("anonymous".equals(username)) {
            return "無法識別使用者，請重新登入後再試。";
        }
        if (args.isEmpty() || args.equalsIgnoreCase("help")) {
            return mountHelpText();
        }

        String[] parts = args.split("\\s+");
        if (parts.length < 2 || parts.length > 4) {
            return "指令格式：`/mount <device_abs_path> <target_mount_point_abs_path> [fstype] [options]`";
        }
        if (!isAbsolutePathToken(parts[0]) || !isAbsolutePathToken(parts[1])) {
            return "安全限制：`device` 與 `target` 都必須是以 `/` 開頭的絕對路徑。";
        }

        Path device = normalizeAbsolutePath(parts[0]);
        Path target = normalizeAbsolutePath(parts[1]);
        if (device == null || target == null) {
            return "指令格式：`/mount <device_abs_path> <target_mount_point_abs_path> [fstype] [options]`";
        }
        if (!device.startsWith(Paths.get("/dev"))) {
            return "安全限制：`device` 必須位於 `/dev/...`。";
        }
        if (looksLikeNonDiskMountDevice(device)) {
            return "安全限制：`/mount` 僅允許整顆磁碟路徑（例如 `/dev/sdc`），不接受分割區或邏輯卷：`%s`"
                    .formatted(device);
        }
        if (target.toString().equals("/")) {
            return "安全限制：不允許直接掛載到 `/`。";
        }

        try {
            if (!Files.exists(device)) return "裝置不存在：`%s`".formatted(device);
            if (Files.isSymbolicLink(target)) {
                return "安全限制：掛載點不可為 symbolic link：`%s`".formatted(target);
            }
            if (Files.exists(target) && !Files.isDirectory(target)) {
                return "掛載點存在但不是資料夾：`%s`".formatted(target);
            }
        } catch (Exception e) {
            return "路徑檢查失敗：" + e.getMessage();
        }

        String fstype = (parts.length >= 3) ? parts[2].trim() : "ext4";
        if (!MOUNT_FSTYPE_TOKEN.matcher(fstype).matches()) {
            return "fstype 格式錯誤（僅支援英數、`.`、`_`、`-`）。";
        }
        String options = (parts.length >= 4) ? parts[3].trim() : "defaults,nofail";
        if (!MOUNT_OPTIONS_TOKEN.matcher(options).matches()) {
            return "options 格式錯誤（僅支援英數、`,`、`=`、`.`、`_`、`:`、`-`）。";
        }

        String confirmCommand = buildMountConfirmCommand(device, target, fstype, options);
        storeMountPendingConfirmation(username, confirmCommand);
        return buildMountConfirmPrompt(device, target, fstype, options, confirmCommand);
    }

    public String handleOffload(String argsRaw, String sessionUser) {
        String args = argsRaw == null ? "" : argsRaw.trim();
        String displayUser = (sessionUser == null || sessionUser.isBlank() || "anonymous".equalsIgnoreCase(sessionUser))
                ? "your-user"
                : sessionUser.trim();
        if (args.isEmpty()) {
            return offloadHelpText(displayUser);
        }

        String[] parts = args.split("\\s+");
        if (parts.length != 2) {
            return "指令格式：`/offload <source_abs_path> <target_disk_root_abs_path>`";
        }
        if (!isAbsolutePathToken(parts[0]) || !isAbsolutePathToken(parts[1])) {
            return "安全限制：不接受相對路徑，請使用以 `/` 開頭的絕對路徑。";
        }

        String username = UsernameUtils.normalizeUsernameOrAnonymous(sessionUser);
        if ("anonymous".equals(username)) {
            return "無法識別使用者，請重新登入後再試。";
        }

        Path source = normalizeAbsolutePath(parts[0]);
        Path targetRoot = normalizeAbsolutePath(parts[1]);
        if (source == null || targetRoot == null) {
            return "請提供兩個絕對路徑，例如：`/offload /home/%s/work /mnt/archive`".formatted(username);
        }

        if (source.getFileName() == null) {
            return "來源路徑格式不支援（請指定具體資料夾，而非根路徑）。";
        }

        boolean rootOwned;
        Path sourceReal;
        Path targetRootReal;
        try {
            if (!Files.exists(source)) return "來源不存在：`%s`".formatted(source);
            if (!Files.isDirectory(source)) return "來源不是資料夾：`%s`".formatted(source);
            if (Files.isSymbolicLink(source)) return "來源已經是 symlink，無需再次 offload：`%s`".formatted(source);

            if (!Files.exists(targetRoot)) return "目標磁碟路徑不存在：`%s`".formatted(targetRoot);
            if (!Files.isDirectory(targetRoot)) return "目標磁碟路徑不是資料夾：`%s`".formatted(targetRoot);
            if (Files.isSymbolicLink(targetRoot)) return "目標磁碟路徑不可為 symlink：`%s`".formatted(targetRoot);

            sourceReal = source.toRealPath();
            targetRootReal = targetRoot.toRealPath();
            rootOwned = OffloadExecutionSupport.isRootOwned(source);
        } catch (Exception e) {
            return "路徑檢查失敗：" + e.getMessage();
        }

        if (targetRoot.startsWith(source) || targetRootReal.startsWith(sourceReal)) {
            return "目標路徑不能在來源資料夾內，避免遞迴複製。";
        }

        Path destination = targetRoot.resolve(source.getFileName()).normalize();
        if (destination.equals(source)) {
            return "來源與目標不能相同。";
        }
        if (Files.exists(destination)) {
            return "目標位置已存在同名資料夾：`%s`（請先手動處理）".formatted(destination);
        }

        List<Path> subMounts = OffloadExecutionSupport.detectSubMountPoints(source);
        if (!subMounts.isEmpty()) {
            return OffloadExecutionSupport.formatSubMountBlockedMessage(source, subMounts, OFFLOAD_SUBMOUNT_HINT_LIMIT);
        }

        List<String> confirmReasons = new ArrayList<>();
        confirmReasons.add("此操作將以背景 Job 執行（可於前端查看進度）");
        if (rootOwned) {
            confirmReasons.add("來源資料夾擁有者為 `root`");
        }
        String confirmCommand = buildOffloadConfirmCommand(source, targetRoot);
        storeOffloadPendingConfirmation(username, confirmCommand);
        return buildOffloadConfirmPrompt(source, targetRoot, confirmReasons, confirmCommand);
    }

    public static String buildOffloadConfirmCommand(Path source, Path targetRoot) {
        if (source == null || targetRoot == null) return null;
        return "offload-confirm --source " + source.normalize() + " --target " + targetRoot.normalize();
    }

    public static Optional<OffloadConfirmPayload> parseOffloadConfirmCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return Optional.empty();
        Matcher m = OFFLOAD_CONFIRM_COMMAND.matcher(rawCommand.trim());
        if (!m.matches()) return Optional.empty();

        Path source = normalizeAbsolutePath(m.group(1));
        Path targetRoot = normalizeAbsolutePath(m.group(2));
        if (source == null || targetRoot == null) return Optional.empty();
        return Optional.of(new OffloadConfirmPayload(source, targetRoot));
    }

    public static String buildMountConfirmCommand(Path device, Path target, String fstype, String options) {
        if (device == null || target == null) return null;
        String safeFsType = (fstype == null || fstype.isBlank()) ? "ext4" : fstype.trim();
        String safeOptions = (options == null || options.isBlank()) ? "defaults,nofail" : options.trim();
        return "mount-confirm --device " + device.normalize()
                + " --target " + target.normalize()
                + " --fstype " + safeFsType
                + " --options " + safeOptions;
    }

    public static Optional<MountConfirmPayload> parseMountConfirmCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return Optional.empty();
        Matcher m = MOUNT_CONFIRM_COMMAND.matcher(rawCommand.trim());
        if (!m.matches()) return Optional.empty();

        Path device = normalizeAbsolutePath(m.group(1));
        Path target = normalizeAbsolutePath(m.group(2));
        if (device == null || target == null) return Optional.empty();
        if (!device.startsWith(Paths.get("/dev"))) return Optional.empty();

        String fstype = m.group(3);
        if (fstype == null || !MOUNT_FSTYPE_TOKEN.matcher(fstype).matches()) return Optional.empty();
        String options = m.group(4);
        if (options == null || !MOUNT_OPTIONS_TOKEN.matcher(options).matches()) return Optional.empty();
        return Optional.of(new MountConfirmPayload(device, target, fstype, options));
    }

    public void storeMountPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return;
        pendingConfirmationManager.store(PendingConfirmationScope.MOUNT, username, command.trim());
    }

    public boolean consumeMountPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return false;
        return pendingConfirmationManager.consume(PendingConfirmationScope.MOUNT, username, command.trim());
    }

    public void clearMountPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return;
        pendingConfirmationManager.clear(PendingConfirmationScope.MOUNT, username, command.trim());
    }

    public void clearMountPendingConfirmations(String username) {
        pendingConfirmationManager.clearScopesForUser(username, PendingConfirmationScope.MOUNT);
    }

    public void storeOffloadPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return;
        pendingConfirmationManager.store(PendingConfirmationScope.OFFLOAD, username, command.trim());
    }

    public boolean consumeOffloadPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return false;
        return pendingConfirmationManager.consume(PendingConfirmationScope.OFFLOAD, username, command.trim());
    }

    public void clearOffloadPendingConfirmation(String username, String command) {
        if (command == null || command.isBlank()) return;
        pendingConfirmationManager.clear(PendingConfirmationScope.OFFLOAD, username, command.trim());
    }

    public void clearOffloadPendingConfirmations(String username) {
        pendingConfirmationManager.clearScopesForUser(username, PendingConfirmationScope.OFFLOAD);
    }

    private static String buildMountConfirmPrompt(Path device, Path target, String fstype, String options, String confirmCommand) {
        return """
                ⚠️ `/mount` 為高風險操作（包含格式化），需要二次確認。
                - device：`%s`
                - target：`%s`
                - fstype：`%s`
                - fstab options：`%s`

                執行步驟：
                1. 建立掛載點目錄（若不存在）
                2. `mkfs -t <fstype> <device>` 格式化
                3. `blkid` 取得 UUID
                4. 備份並更新 `/etc/fstab`
                5. 掛載並驗證

                %s
                """.formatted(
                device.normalize(),
                target.normalize(),
                fstype,
                options,
                CommandMarkers.cmdMarker(confirmCommand)
        ).trim();
    }

    private static String mountHelpText() {
        return """
                **/mount**
                - 用法：`/mount <device_abs_path> <target_mount_point_abs_path> [fstype] [options]`
                - 範例：`/mount /dev/sdc /mnt/train-data-1-hdd`
                - 範例：`/mount /dev/sdc /mnt/train-data-1-hdd ext4 defaults,nofail`
                - 會執行：建立 mount point → 格式化 → 取得 UUID → 寫入 `/etc/fstab` → 掛載驗證
                - 限制：僅允許整顆磁碟裝置（`lsblk TYPE=disk`，例如 `/dev/sdc`）
                - 預設：`fstype=ext4`，`options=defaults,nofail`
                - 僅限管理員
                - 需二次確認後才會執行
                """.trim();
    }

    private static String buildOffloadConfirmPrompt(Path source, Path targetRoot, List<String> reasons, String confirmCommand) {
        StringBuilder reasonBlock = new StringBuilder();
        for (String reason : reasons) {
            reasonBlock.append("- ").append(reason).append("\n");
        }
        return """
                ⚠️ `/offload` 需要二次確認，請點擊按鈕執行。
                - source：`%s`
                - target_root：`%s`

                觸發條件：
                %s
                確認後將執行：`rsync -a` → 檢查成功 → 刪除原資料夾 → 建立 symlink。

                %s
                """.formatted(source, targetRoot, reasonBlock.toString().trim(), CommandMarkers.cmdMarker(confirmCommand)).trim();
    }

    private static String offloadHelpText(String username) {
        return """
                **/offload**
                - 用法：`/offload <source_abs_path> <target_disk_root_abs_path>`
                - 範例：`/offload /home/%s/projects /mnt/archive`
                - 使用 `rsync -a` 複製（不使用 `cp` / `mv`）

                **執行步驟**
                1. 先執行：`rsync -a -- <source> <target_root>`
                2. 先確認複製是否成功且目的資料夾存在，若失敗立即終止
                3. 複製成功後，刪除原始資料夾
                4. 建立 soft link：`<source> -> <target_root>/<source_name>`

                **限制**
                - 僅限管理員操作
                - 所有 `/offload` 操作都需先點擊確認按鈕，確認後才會啟動背景 Job
                - 若來源擁有者為 `root`，會額外顯示風險原因
                - 若來源目錄內有子掛載點（例如 bind/NFS/其他磁碟掛載），會拒絕執行
                - `target_root` 必須為已存在的絕對路徑資料夾
                """.formatted(username).trim();
    }

    private static Path normalizeAbsolutePath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path p = Paths.get(raw.trim()).normalize();
            return p.isAbsolute() ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAbsolutePathToken(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String trimmed = raw.trim();
        if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.startsWith("/");
    }

    private static boolean looksLikeNonDiskMountDevice(Path device) {
        if (device == null || device.getFileName() == null) return false;
        String name = device.getFileName().toString();
        if (name.isBlank()) return false;
        if (device.normalize().toString().startsWith("/dev/mapper/")) return true;
        if (name.matches("^sd[a-z]+\\d+$")) return true;
        if (name.matches("^vd[a-z]+\\d+$")) return true;
        if (name.matches("^xvd[a-z]+\\d+$")) return true;
        if (name.matches("^nvme\\d+n\\d+p\\d+$")) return true;
        if (name.matches("^mmcblk\\d+p\\d+$")) return true;
        if (name.matches("^dm-\\d+$")) return true;
        if (name.matches("^md\\d+$")) return true;
        if (name.matches("^loop\\d+$")) return true;
        return false;
    }
}
