package com.linux.ai.serverassistant.service.file;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.AuditLogPersistenceService;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.FileOperationRateLimiter;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UsernameUtils;

import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

/**
 * File Operation Service
 *
 * Provides file system operation capabilities including:
 * - Directory listing and navigation
 * - File content reading
 * - Directory creation
 * - File content writing
 *
 * Deletion is handled by executeLinuxCommand (rm) via CommandExecutionService.
 *
 * All operations include audit logging for security and compliance.
 */
@Service
public class FileOperationService {

    private static final Logger log = LoggerFactory.getLogger(FileOperationService.class);

    /** Allowed directory prefixes for read operations (allowlist approach). */
    private static final List<String> READ_ALLOWED_PREFIXES = List.of(
            "/home/", "/tmp/", "/var/log/", "/var/www/", "/opt/", "/srv/",
            "/usr/local/", "/etc/nginx/", "/etc/apache2/"
    );

    /** Allowed directory prefixes for write/create operations (stricter). */
    private static final List<String> WRITE_ALLOWED_PREFIXES = List.of(
            "/home/", "/tmp/", "/var/www/", "/opt/", "/srv/"
    );

    /** Maximum content size for write operations (1 MB). */
    private static final int MAX_WRITE_SIZE = 1024 * 1024;
    private static final int MAX_READ_SIZE = 50 * 1024;
    private static final List<Path> FD_BASE_PATH_CANDIDATES = List.of(
            Path.of("/proc/self/fd"),
            Path.of("/dev/fd")
    );

    private final CommandLogRepository commandLogRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final FileOperationRateLimiter fileOperationRateLimiter;

    public FileOperationService(CommandLogRepository commandLogRepository,
                                AdminAuthorizationService adminAuthorizationService,
                                FileOperationRateLimiter fileOperationRateLimiter) {
        this.commandLogRepository = commandLogRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.fileOperationRateLimiter = fileOperationRateLimiter;
    }

    /**
     * Validates that the resolved path is not in a restricted area.
     * @param path the path to check
     * @param isWrite true for write/create operations (stricter checks)
     * @return error message if blocked, null if allowed
     */
    private String validatePath(String path, boolean isWrite, String actor) {
        try {
            Path requested = Paths.get(path).toAbsolutePath().normalize();
            String policyViolation = validatePathPolicy(requested, isWrite, path, actor);
            if (policyViolation != null) return policyViolation;

            if (!Files.exists(requested)) {
                return formatExecutionResult("錯誤：路徑不存在 " + path);
            }

            Path realBase = requested.toRealPath();
            List<String> allowed = isWrite ? WRITE_ALLOWED_PREFIXES : READ_ALLOWED_PREFIXES;
            boolean realAllowed = isUnderAllowedPrefixes(realBase.toString(), allowed);
            if (!realAllowed) {
                log.warn("[FileOps] Symlink escape attempt: '{}' resolves outside allowlist", path);
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑（解析符號連結後）不在允許範圍內: " + path);
            }

            return null; // allowed
        } catch (Exception e) {
            return formatExecutionResult("路徑無效: " + path);
        }
    }

    private String validatePathPolicy(Path requested, boolean isWrite, String rawPath, String actor) {
        String normalized = requested.toString();
        List<String> allowed = isWrite ? WRITE_ALLOWED_PREFIXES : READ_ALLOWED_PREFIXES;
        boolean isAllowed = isUnderAllowedPrefixes(normalized, allowed);
        if (!isAllowed) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑不在允許範圍內: " + rawPath
                    + " (允許的目錄: " + String.join(", ", allowed) + ")");
        }
        return validateActorScope(normalized, actor);
    }

    public String listDirectory(String path, String actor) {
        String effectiveActor = resolveActor(actor);
        String rateLimitExceeded = enforceReadRateLimit(effectiveActor, "listDirectory", path);
        if (rateLimitExceeded != null) return rateLimitExceeded;
        String blocked = validatePath(path, false, effectiveActor);
        if (blocked != null) return blocked;
        try {
            Path dir = Paths.get(path);
            if (!Files.exists(dir)) return formatExecutionResult("錯誤：目錄不存在 " + path);
            if (!Files.isDirectory(dir)) return formatExecutionResult("錯誤：路徑不是目錄 " + path);

            StringBuilder sb = new StringBuilder();
            sb.append("Directory: ").append(path).append("\n");
            sb.append(String.format("%-10s %-10s %s\n", "Size", "Owner", "Name"));
            sb.append("-".repeat(40)).append("\n");

            try (var stream = Files.list(dir)) {
                stream.limit(50).forEach(p -> {
                    try {
                        String name = p.getFileName().toString();
                        if (Files.isDirectory(p)) name += "/";
                        long size = Files.size(p);
                        String owner = Files.getOwner(p).getName();
                        sb.append(String.format("%-10d %-10s %s\n", size, owner, name));
                    } catch (Exception e) {
                        sb.append("Error reading ").append(p.getFileName()).append("\n");
                    }
                });
            }
            String result = sb.toString();
            saveAuditLog("[File] List Directory: " + path, result, true, effectiveActor);
            return formatExecutionResult(result);
        } catch (Exception e) {
            saveAuditLog("[File] List Directory: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("列出目錄失敗: " + e.getMessage());
        }
    }

    public String readFileContent(String path, String actor) {
        String effectiveActor = resolveActor(actor);
        String rateLimitExceeded = enforceReadRateLimit(effectiveActor, "readFileContent", path);
        if (rateLimitExceeded != null) return rateLimitExceeded;
        String blocked = validatePath(path, false, effectiveActor);
        if (blocked != null) return blocked;
        try {
            Path requested = Paths.get(path).toAbsolutePath().normalize();
            Path canonicalTarget = requested.toRealPath();
            String canonicalBlocked = validatePath(canonicalTarget.toString(), false, effectiveActor);
            if (canonicalBlocked != null) return canonicalBlocked;
            if (Files.isDirectory(canonicalTarget, LinkOption.NOFOLLOW_LINKS)) {
                return formatExecutionResult("錯誤：這是目錄，請使用 listDirectory");
            }
            if (!Files.isReadable(canonicalTarget)) {
                return formatExecutionResult("錯誤：無讀取權限");
            }
            if (Files.size(canonicalTarget) > MAX_READ_SIZE) {
                return formatExecutionResult("錯誤：檔案過大 (>50KB)，請改用 executeLinuxCommand 搭配 head/tail 查看");
            }

            Path fileName = canonicalTarget.getFileName();
            if (fileName == null) {
                return formatExecutionResult("錯誤：檔案不存在 " + path);
            }
            Path canonicalParent = canonicalTarget.getParent();
            if (canonicalParent == null) {
                return formatExecutionResult("錯誤：檔案不存在 " + path);
            }

            String content;
            try {
                try (SecureDirectoryStream<Path> secureParent = openSecureDirectory(canonicalParent)) {
                    content = readContentFromSecureEntry(secureParent, fileName, path);
                }
            } catch (UnsupportedOperationException unsupportedFs) {
                content = readContentFromPathNoFollow(canonicalTarget, path);
            }

            saveAuditLog("[File] Read File: " + path, "(Content length: " + content.length() + ")", true, effectiveActor);
            return formatExecutionResult(content);
        } catch (NoSuchFileException e) {
            saveAuditLog("[File] Read File: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("錯誤：檔案不存在 " + path);
        } catch (AccessDeniedException e) {
            saveAuditLog("[File] Read File: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("錯誤：無讀取權限");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            saveAuditLog("[File] Read File: " + path, message, false, effectiveActor);
            if (message.startsWith(CommandMarkers.SECURITY_VIOLATION)) {
                return formatExecutionResult(message);
            }
            if (message.contains("這是目錄")) {
                return formatExecutionResult("錯誤：這是目錄，請使用 listDirectory");
            }
            if (message.contains("檔案過大")) {
                return formatExecutionResult("錯誤：檔案過大 (>50KB)，請改用 executeLinuxCommand 搭配 head/tail 查看");
            }
            return formatExecutionResult("讀取檔案失敗: " + message);
        } catch (Exception e) {
            saveAuditLog("[File] Read File: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("讀取檔案失敗: " + e.getMessage());
        }
    }

    /**
     * Creates a new directory
     */
    public String createDirectory(String path, String username) {
        String effectiveActor = resolveActor(username);
        try {
            Path requested = Paths.get(path).toAbsolutePath().normalize();
            String requestedPolicyViolation = validatePathPolicy(requested, true, path, effectiveActor);
            if (requestedPolicyViolation != null) return requestedPolicyViolation;

            Path existingAncestor = findExistingAncestor(requested);
            if (existingAncestor == null) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑無效: " + path);
            }

            String ancestorBlocked = validatePath(existingAncestor.toString(), true, effectiveActor);
            if (ancestorBlocked != null) return ancestorBlocked;

            Path realAncestor = existingAncestor.toRealPath();
            Path relativeToCreate = existingAncestor.relativize(requested);
            Path canonicalTarget = realAncestor.resolve(relativeToCreate).normalize();
            String canonicalPolicyViolation = validatePathPolicy(canonicalTarget, true, path, effectiveActor);
            if (canonicalPolicyViolation != null) return canonicalPolicyViolation;

            boolean created;
            try {
                created = createDirectoriesWithDescriptorPath(realAncestor, relativeToCreate);
            } catch (UnsupportedOperationException noFdSupport) {
                log.warn("[FileOps] fd-relative mkdir unavailable, using NOFOLLOW fallback: {}", noFdSupport.getMessage());
                created = createDirectoriesFallback(realAncestor, relativeToCreate);
            }
            if (!created) return formatExecutionResult("錯誤：路徑已存在 " + path);

            String chownResult;
            try {
                chownResult = changeOwnership(canonicalTarget, effectiveActor);
            } catch (IOException chownException) {
                String message = "目錄已建立，但設定擁有者失敗: " + chownException.getMessage();
                saveAuditLog("[File] Create Directory: " + path, message, false, effectiveActor);
                return formatExecutionResult(message);
            }

            saveAuditLog("[File] Create Directory: " + path, "Success" + chownResult, true, effectiveActor);
            return formatExecutionResult("成功建立目錄: " + path + chownResult);
        } catch (Exception e) {
            saveAuditLog("[File] Create Directory: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("建立目錄失敗: " + e.getMessage());
        }
    }

    /**
     * Writes content to a file
     */
    public String writeFileContent(String path, String content, String username) {
        String effectiveActor = resolveActor(username);
        String safeContent = content == null ? "" : content;
        if (safeContent.length() > MAX_WRITE_SIZE) {
            return formatExecutionResult("錯誤：寫入內容過大（超過 1MB），已阻止寫入。");
        }
        try {
            Path requested = Paths.get(path).toAbsolutePath().normalize();
            String requestedPolicyViolation = validatePathPolicy(requested, true, path, effectiveActor);
            if (requestedPolicyViolation != null) return requestedPolicyViolation;

            Path fileName = requested.getFileName();
            if (fileName == null) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑無效: " + path);
            }
            Path requestedParent = requested.getParent();
            if (requestedParent == null) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑無效: " + path);
            }

            String parentBlocked = validatePath(requestedParent.toString(), true, effectiveActor);
            if (parentBlocked != null) return parentBlocked;

            Path realParent = requestedParent.toRealPath();
            Path canonicalTarget = realParent.resolve(fileName).normalize();
            if (Files.exists(canonicalTarget)) {
                String canonicalBlocked = validatePath(canonicalTarget.toString(), true, effectiveActor);
                if (canonicalBlocked != null) return canonicalBlocked;
            } else {
                String canonicalPolicyViolation = validatePathPolicy(canonicalTarget, true, path, effectiveActor);
                if (canonicalPolicyViolation != null) return canonicalPolicyViolation;
            }

            boolean exists;
            try {
                try (SecureDirectoryStream<Path> secureParent = openSecureDirectory(realParent)) {
                    BasicFileAttributes existingAttrs = readEntryAttributes(secureParent, fileName);
                    if (existingAttrs != null && existingAttrs.isSymbolicLink()) {
                        return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 拒絕寫入符號連結目標: " + path);
                    }
                    exists = existingAttrs != null;
                    writeContentToSecureEntry(secureParent, fileName, safeContent);
                }
            } catch (UnsupportedOperationException unsupportedFs) {
                try {
                    try (DirectoryHandle parentHandle = openDirectoryHandle(realParent)) {
                        Path fdTarget = parentHandle.resolve(fileName);
                        BasicFileAttributes existingAttrs = readPathAttributesNoFollow(fdTarget);
                        if (existingAttrs != null && existingAttrs.isSymbolicLink()) {
                            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 拒絕寫入符號連結目標: " + path);
                        }
                        exists = existingAttrs != null;
                        writeContentToPathNoFollow(fdTarget, safeContent);
                    }
                } catch (UnsupportedOperationException noFdSupport) {
                    log.warn("[FileOps] fd-relative write unavailable, using NOFOLLOW fallback: {}", noFdSupport.getMessage());
                    BasicFileAttributes existingAttrs = readPathAttributesNoFollow(canonicalTarget);
                    if (existingAttrs != null && existingAttrs.isSymbolicLink()) {
                        return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 拒絕寫入符號連結目標: " + path);
                    }
                    exists = existingAttrs != null;
                    writeContentToPathNoFollow(canonicalTarget, safeContent);
                }
            }

            String chownResult;
            try {
                chownResult = changeOwnership(canonicalTarget, effectiveActor);
            } catch (IOException chownException) {
                String message = "檔案已寫入，但設定擁有者失敗: " + chownException.getMessage();
                saveAuditLog("[File] Write File: " + path, message, false, effectiveActor);
                return formatExecutionResult(message);
            }

            saveAuditLog("[File] Write File: " + path, "Success (Length: " + safeContent.length() + ")" + chownResult, true, effectiveActor);
            return formatExecutionResult("成功寫入檔案: " + path + " (" + safeContent.length() + " bytes)" + (exists ? " [已覆蓋舊檔]" : " [新建立]") + chownResult);
        } catch (Exception e) {
            saveAuditLog("[File] Write File: " + path, e.getMessage(), false, effectiveActor);
            return formatExecutionResult("寫入檔案失敗: " + e.getMessage());
        }
    }

    private String validateActorScope(String rawPath, String resolvedActor) {
        if (resolvedActor == null || resolvedActor.isBlank() || "anonymous".equalsIgnoreCase(resolvedActor)) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入後再試。");
        }

        if (adminAuthorizationService.isAdmin(resolvedActor)) {
            return null;
        }

        try {
            Path userHome = resolveActorHomeDirectory(resolvedActor);
            Path requested = Paths.get(rawPath).toAbsolutePath().normalize();
            if (!isUnderPath(requested, userHome)) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 非管理員檔案操作僅允許在自己的家目錄下：`" + userHome + "`");
            }

            if (!Files.exists(userHome)) {
                return null;
            }
            Path realHome = userHome.toRealPath();
            if (Files.exists(requested)) {
                Path realRequested = requested.toRealPath();
                if (!isUnderPath(realRequested, realHome)) {
                    return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 非管理員檔案操作僅允許在自己的家目錄下：`" + realHome + "`");
                }
                return null;
            }

            Path ancestor = requested;
            while (ancestor != null && !Files.exists(ancestor)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑無效: " + rawPath);
            }
            Path realAncestor = ancestor.toRealPath();
            if (!isUnderPath(realAncestor, realHome)) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 非管理員檔案操作僅允許在自己的家目錄下：`" + realHome + "`");
            }
            return null;
        } catch (Exception e) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 路徑檢查失敗: " + e.getMessage());
        }
    }

    private Path resolveActorHomeDirectory(String actor) {
        String configuredRoot = System.getProperty("app.user-home-root");
        if (configuredRoot != null && !configuredRoot.isBlank()) {
            return Paths.get(configuredRoot.trim()).resolve(actor).toAbsolutePath().normalize();
        }

        String currentHome = System.getProperty("user.home");
        if (currentHome != null && !currentHome.isBlank()) {
            Path currentHomePath = Paths.get(currentHome.trim()).toAbsolutePath().normalize();
            Path parent = currentHomePath.getParent();
            Path userRoot = (parent != null) ? parent : currentHomePath;
            return userRoot.resolve(actor).toAbsolutePath().normalize();
        }

        Path filesystemRoot = Paths.get("").toAbsolutePath().getRoot();
        Path fallbackRoot = filesystemRoot != null ? filesystemRoot : Paths.get("").toAbsolutePath().normalize();
        return fallbackRoot.resolve(actor).toAbsolutePath().normalize();
    }

    private BasicFileAttributes readEntryAttributes(SecureDirectoryStream<Path> directory, Path entry) throws IOException {
        try {
            BasicFileAttributeView view = directory.getFileAttributeView(entry, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return null;
            }
            return view.readAttributes();
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private BasicFileAttributes readPathAttributesNoFollow(Path path) throws IOException {
        try {
            BasicFileAttributeView view = Files.getFileAttributeView(path, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) return null;
            return view.readAttributes();
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private String readContentFromSecureEntry(SecureDirectoryStream<Path> directory, Path fileName, String displayPath) throws IOException {
        BasicFileAttributes attrs = readEntryAttributes(directory, fileName);
        validateReadableFileAttributes(attrs, displayPath);
        try (SeekableByteChannel channel = directory.newByteChannel(fileName, readOpenOptions())) {
            return readContentFromChannel(channel);
        }
    }

    private String readContentFromPathNoFollow(Path path, String displayPath) throws IOException {
        BasicFileAttributes attrs = readPathAttributesNoFollow(path);
        validateReadableFileAttributes(attrs, displayPath);
        try (SeekableByteChannel channel = Files.newByteChannel(path, readOpenOptions())) {
            return readContentFromChannel(channel);
        }
    }

    private void validateReadableFileAttributes(BasicFileAttributes attrs, String displayPath) throws IOException {
        if (attrs == null) {
            throw new NoSuchFileException(displayPath);
        }
        if (attrs.isSymbolicLink()) {
            throw new IOException(CommandMarkers.SECURITY_VIOLATION + " 拒絕讀取符號連結目標: " + displayPath);
        }
        if (attrs.isDirectory()) {
            throw new IOException("這是目錄，請使用 listDirectory");
        }
        if (attrs.size() > MAX_READ_SIZE) {
            throw new IOException("檔案過大 (>50KB)，請改用 executeLinuxCommand 搭配 head/tail 查看");
        }
    }

    private String readContentFromChannel(SeekableByteChannel channel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8 * 1024);
        int read;
        while ((read = channel.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            buffer.flip();
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            output.write(chunk);
            buffer.clear();
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private Set<java.nio.file.OpenOption> readOpenOptions() {
        return Set.of(
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private void writeContentToSecureEntry(SecureDirectoryStream<Path> directory, Path fileName, String content) throws IOException {
        try (SeekableByteChannel channel = directory.newByteChannel(fileName, writeOpenOptions())) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private void writeContentToPathNoFollow(Path path, String content) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, writeOpenOptions())) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private Set<java.nio.file.OpenOption> writeOpenOptions() {
        return Set.of(
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private Path findExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }

    private boolean createDirectoriesWithDescriptorPath(Path realAncestor, Path relativeToCreate) throws IOException {
        if (relativeToCreate == null || relativeToCreate.getNameCount() == 0) {
            return false;
        }

        try (DirectoryHandle root = openDirectoryHandle(realAncestor)) {
            boolean created = false;
            DirectoryHandle current = root;
            try {
                for (int i = 0; i < relativeToCreate.getNameCount(); i++) {
                    Path segment = relativeToCreate.getName(i);
                    boolean last = i == relativeToCreate.getNameCount() - 1;
                    Path candidate = current.resolve(segment);

                    BasicFileAttributes attrs = readPathAttributesNoFollow(candidate);
                    if (attrs != null) {
                        if (attrs.isSymbolicLink()) {
                            throw new IOException("路徑包含符號連結: " + candidate);
                        }
                        if (!attrs.isDirectory()) {
                            throw new IOException("路徑存在但不是目錄: " + candidate);
                        }
                        if (last) return false;
                    } else {
                        try {
                            Files.createDirectory(candidate);
                            created = true;
                        } catch (FileAlreadyExistsException alreadyExists) {
                            BasicFileAttributes currentAttrs = readPathAttributesNoFollow(candidate);
                            if (currentAttrs == null || currentAttrs.isSymbolicLink() || !currentAttrs.isDirectory()) {
                                throw new IOException("路徑存在且不可作為安全目錄: " + candidate, alreadyExists);
                            }
                            if (last) return false;
                        }
                    }

                    if (!last) {
                        DirectoryHandle next = openDirectoryHandle(candidate);
                        if (current != root) {
                            current.close();
                        }
                        current = next;
                    }
                }
                return created;
            } finally {
                if (current != root) {
                    current.close();
                }
            }
        }
    }

    /**
     * Fallback for environments where /proc/self/fd and /dev/fd are unavailable.
     * Uses NOFOLLOW_LINKS attribute checks instead of fd-relative paths.
     * The TOCTOU window between check and mkdir is accepted as documented.
     */
    private boolean createDirectoriesFallback(Path realAncestor, Path relativeToCreate) throws IOException {
        if (relativeToCreate == null || relativeToCreate.getNameCount() == 0) {
            return false;
        }
        boolean created = false;
        Path current = realAncestor;
        for (int i = 0; i < relativeToCreate.getNameCount(); i++) {
            Path segment = relativeToCreate.getName(i);
            boolean last = i == relativeToCreate.getNameCount() - 1;
            Path candidate = current.resolve(segment);

            BasicFileAttributes attrs = readPathAttributesNoFollow(candidate);
            if (attrs != null) {
                if (attrs.isSymbolicLink()) {
                    throw new IOException("路徑包含符號連結: " + candidate);
                }
                if (!attrs.isDirectory()) {
                    throw new IOException("路徑存在但不是目錄: " + candidate);
                }
                if (last) return false;
            } else {
                try {
                    Files.createDirectory(candidate);
                    created = true;
                } catch (FileAlreadyExistsException alreadyExists) {
                    BasicFileAttributes currentAttrs = readPathAttributesNoFollow(candidate);
                    if (currentAttrs == null || currentAttrs.isSymbolicLink() || !currentAttrs.isDirectory()) {
                        throw new IOException("路徑存在且不可作為安全目錄: " + candidate, alreadyExists);
                    }
                    if (last) return false;
                }
            }
            current = candidate;
        }
        return created;
    }

    private DirectoryHandle openDirectoryHandle(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path descriptorBase = resolveDescriptorBasePath();
        FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            int fd = resolveFileDescriptorNumber(channel);
            return new DirectoryHandle(channel, descriptorBase, fd);
        } catch (RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    private Path resolveDescriptorBasePath() {
        for (Path candidate : FD_BASE_PATH_CANDIDATES) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new UnsupportedOperationException("系統不支援 /proc/self/fd 或 /dev/fd，無法安全建立目錄");
    }

    private int resolveFileDescriptorNumber(FileChannel channel) {
        try {
            // Walk class hierarchy to find 'fd' field of type java.io.FileDescriptor.
            // This avoids sun.misc.Unsafe and works regardless of the concrete implementation class.
            java.io.FileDescriptor fileDescriptor = null;
            for (Class<?> cls = channel.getClass(); cls != null && fileDescriptor == null; cls = cls.getSuperclass()) {
                try {
                    Field fdField = cls.getDeclaredField("fd");
                    fdField.setAccessible(true);
                    Object value = fdField.get(channel);
                    if (value instanceof java.io.FileDescriptor fd) {
                        fileDescriptor = fd;
                    }
                } catch (NoSuchFieldException ignored) {
                    // not in this class, try superclass
                }
            }
            if (fileDescriptor == null) {
                throw new UnsupportedOperationException(
                        "無法解析目錄描述符：找不到 FileDescriptor 欄位（class=" + channel.getClass().getName() + "）");
            }
            Field intFdField = java.io.FileDescriptor.class.getDeclaredField("fd");
            intFdField.setAccessible(true);
            int fd = intFdField.getInt(fileDescriptor);
            if (fd < 0) {
                throw new UnsupportedOperationException("無法解析目錄描述符：fd 值無效 (" + fd + ")");
            }
            return fd;
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            // Treat any reflection failure (InaccessibleObjectException, NoSuchFieldException, etc.)
            // as "fd-based approach not available" so callers can fall back gracefully.
            throw new UnsupportedOperationException("無法以反射取得 FileDescriptor（可能缺少 --add-opens）", e);
        }
    }

    // Traverse from filesystem root using descriptor-relative operations to reduce TOCTOU/symlink swap risk.
    private SecureDirectoryStream<Path> openSecureDirectory(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        Path root = normalized.getRoot();
        if (root == null) {
            throw new IOException("無法解析目錄根路徑: " + directory);
        }

        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
        if (!(rootStream instanceof SecureDirectoryStream<Path> secureRoot)) {
            rootStream.close();
            throw new UnsupportedOperationException("檔案系統不支援 SecureDirectoryStream");
        }

        SecureDirectoryStream<Path> current = secureRoot;
        try {
            Path relative = root.relativize(normalized);
            for (Path segment : relative) {
                BasicFileAttributeView view = current.getFileAttributeView(segment, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (view == null) {
                    throw new NoSuchFileException(normalized.toString());
                }

                BasicFileAttributes attrs = view.readAttributes();
                if (attrs.isSymbolicLink()) {
                    throw new IOException("目錄包含符號連結: " + segment);
                }
                if (!attrs.isDirectory()) {
                    throw new IOException("非目錄路徑: " + segment);
                }

                DirectoryStream<Path> nextStream = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                if (!(nextStream instanceof SecureDirectoryStream<Path> secureNext)) {
                    nextStream.close();
                    throw new UnsupportedOperationException("檔案系統不支援 SecureDirectoryStream");
                }
                current.close();
                current = secureNext;
            }
            return current;
        } catch (IOException | RuntimeException e) {
            try {
                current.close();
            } catch (IOException closeError) {
                log.debug("[FileOps] Failed to close secure directory stream cleanly: {}", closeError.getMessage());
            }
            throw e;
        }
    }

    private boolean isUnderPath(Path candidate, Path root) {
        if (candidate == null || root == null) return false;
        return candidate.equals(root) || candidate.startsWith(root);
    }

    private boolean isUnderAllowedPrefixes(String path, List<String> allowedPrefixes) {
        if (path == null || allowedPrefixes == null) return false;
        for (String prefix : allowedPrefixes) {
            if (prefix == null || prefix.isBlank()) continue;
            String normalizedPrefix = prefix.endsWith("/") && prefix.length() > 1
                    ? prefix.substring(0, prefix.length() - 1)
                    : prefix;
            if (path.equals(normalizedPrefix) || path.startsWith(normalizedPrefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private String enforceReadRateLimit(String actor, String operation, String path) {
        long retryAfterSeconds = fileOperationRateLimiter.tryConsume(actor);
        if (retryAfterSeconds <= 0) {
            return null;
        }
        log.warn("[FileOps] Rate limit exceeded for actor='{}', operation='{}', path='{}', retryAfter={}s",
                actor, operation, path, retryAfterSeconds);
        return formatExecutionResult("錯誤：檔案操作請求過於頻繁，請在 " + retryAfterSeconds + " 秒後再試。");
    }

    private String resolveActor(String actor) {
        if (actor == null) {
            return null;
        }
        String normalized = actor.trim();
        if (!UsernameUtils.isValidLinuxUsername(normalized)) {
            return null;
        }
        return normalized;
    }

    private String changeOwnership(Path path, String user) throws IOException {
        if (user == null || user.equals("anonymous") || user.equals("system")) {
            return " (Owner: root/system)";
        }

        if (path == null) {
            throw new IOException("chown 失敗：無效路徑");
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("chown 失敗：拒絕對符號連結設定擁有者");
        }

        try {
            var principalLookup = path.getFileSystem().getUserPrincipalLookupService();
            var principal = principalLookup.lookupPrincipalByName(user);
            FileOwnerAttributeView ownerView =
                    Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (ownerView == null) {
                throw new IOException("chown 失敗：目前檔案系統不支援 owner 屬性");
            }

            ownerView.setOwner(principal);
            return " (Owner set to " + user + ")";
        } catch (NoSuchFileException e) {
            log.warn("[FileOps] Failed to chown because target disappeared: {}", path);
            throw new IOException("chown 失敗：目標不存在", e);
        } catch (IOException e) {
            log.warn("[FileOps] Failed to chown {}: {}", path, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.warn("[FileOps] Failed to chown {}: {}", path, e.getMessage());
            throw new IOException("chown 失敗: " + e.getMessage(), e);
        }
    }

    private static final class DirectoryHandle implements AutoCloseable {
        private final FileChannel channel;
        private final Path descriptorPath;

        private DirectoryHandle(FileChannel channel, Path descriptorBase, int descriptorNumber) {
            this.channel = channel;
            this.descriptorPath = descriptorBase.resolve(String.valueOf(descriptorNumber));
        }

        private Path resolve(Path child) {
            return descriptorPath.resolve(child.toString()).normalize();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private void saveAuditLog(String command, String output, boolean success, String actor) {
        try {
            String currentUser = (actor == null || actor.isBlank()) ? "anonymous" : actor.trim();

            CommandLog cmdLog = new CommandLog();
            cmdLog.setUsername(currentUser);
            cmdLog.setCommand(command);

            cmdLog.setOutput(CommandAuditService.truncateOutput(output));
            cmdLog.setSuccess(success);
            cmdLog.setCommandType(CommandAuditService.classifyCommandType(command));
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "FileOperationService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save log: " + ex.getMessage());
        }
    }
}
