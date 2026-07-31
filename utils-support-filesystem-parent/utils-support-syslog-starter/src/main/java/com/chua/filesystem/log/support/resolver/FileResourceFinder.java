package com.chua.filesystem.log.support.resolver;

import com.chua.common.support.file.resource.FileSystemResource;
import com.chua.common.support.file.resource.Resource;
import com.chua.common.support.file.resource.ResourceConfiguration;
import com.chua.common.support.file.resource.ResourceFinder;
import com.chua.common.support.utils.StringUtils;
import com.chua.filesystem.log.support.bridge.PlatformSystems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * File system resource finder - registers "file:" protocol.
 * <p>
 * Uses OS-native search tools for fast file lookups:
 * <ul>
 *   <li>Windows: {@code where /r} (command-line search)</li>
 *   <li>Linux: {@code locate} (mlocate/plocate database)</li>
 *   <li>macOS: {@code mdfind} (Spotlight index)</li>
 * </ul>
 * Falls back to Java NIO Files.walkFileTree if native tool is unavailable.
 * </p>
 *
 * @author CH
*/
public class FileResourceFinder implements ResourceFinder {

    private static final Logger log = LoggerFactory.getLogger(FileResourceFinder.class);
    private static final int DEFAULT_MAX_DEPTH = 128;

    private final ResourceConfiguration configuration;

    public FileResourceFinder(ResourceConfiguration configuration) {
        this.configuration = configuration;
    }

    public FileResourceFinder() {
        this(ResourceConfiguration.DEFAULT);
    }

    private static final String PROTOCOL = "file";

    @Override
    public Set<Resource> find(String name) {
        name = name.replace("\\", "/");
        if (name.startsWith(PROTOCOL + ":")) {
            name = name.substring((PROTOCOL + ":").length());
        }
        String fullPath = getFullPath(name);
        String matchPath = getMatchPath(name);
        Set<String> dirs = getDir(fullPath);

        long startTime = System.currentTimeMillis();
        Set<Resource> results;

        if (!matchPath.contains("*") && !matchPath.contains("?")) {
            if (StringUtils.isNotEmpty(fullPath)) {
                File f = new File(fullPath);
                if (f.isFile()) {
                    results = new LinkedHashSet<>();
                    results.add(new FileSystemResource(f));
                    return results;
                }
            }
        }

        Set<Resource> nativeResults = searchWithNativeTool(name, fullPath, matchPath, dirs);
        if (nativeResults != null) {
            results = nativeResults;
        } else {
            results = ConcurrentHashMap.newKeySet();
            AtomicLong scannedCount = new AtomicLong(0);
            Set<Resource> finalResults = results;
            var stream = configuration.isParallel() ? dirs.parallelStream() : dirs.stream();
            stream.forEach(dir -> walkDirectory(dir, fullPath, matchPath, finalResults, scannedCount));
        }

        long elapsed = System.currentTimeMillis() - startTime;
        if (log.isDebugEnabled()) {
            log.debug("File scan: path={}, matched={}, elapsed={}ms", fullPath, results.size(), elapsed);
        }
        return results;
    }

    private Set<Resource> searchWithNativeTool(String name, String fullPath, String matchPath, Set<String> dirs) {
        try {
            if (PlatformSystems.isLinux()) {
                return searchLinuxLocate(name, fullPath, matchPath);
            } else if (PlatformSystems.isWindows()) {
                return searchWindowsWhere(name, fullPath, matchPath);
            } else if (PlatformSystems.isMacOs()) {
                return searchMacMdfind(name, fullPath, matchPath);
            }
        } catch (Exception e) {
            if (log.isWarnEnabled()) {
                log.warn("Native search failed, falling back to NIO walk: {}", e.getMessage());
            }
        }
        return null;
    }

    private Set<Resource> searchLinuxLocate(String name, String fullPath, String matchPath) throws Exception {
        if (!isCommandAvailable("locate")) {
            tryInstallLocate();
            if (!isCommandAvailable("locate")) {
                if (log.isWarnEnabled()) {
                    log.warn("locate not available; install plocate/mlocate for faster search");
                }
                return null;
            }
        }

        File dbFile = new File("/var/lib/plocate/plocate.db");
        if (!dbFile.isFile()) {
            if (log.isInfoEnabled()) {
                log.info("locate database missing, running updatedb (may take a minute)...");
            }
            if (isCommandAvailable("sudo")) {
                runAndForget("sudo", "updatedb");
            } else {
                runAndForget("updatedb");
            }
            if (!dbFile.isFile()) {
                if (log.isWarnEnabled()) {
                    log.warn("updatedb failed to create locate database, falling back to NIO walk");
                }
                return null;
            }
        }

        String locateArg = matchPath.isEmpty() ? fullPath : matchPath;
        if (fullPath != null && !fullPath.isEmpty() && !fullPath.equals("/")) {
            locateArg = fullPath + "/" + matchPath;
        }

        ProcessBuilder pb = new ProcessBuilder("locate", "-i", "--regex", globToRegex(locateArg));
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            if (log.isWarnEnabled()) {
                log.warn("locate command timed out");
            }
            return null;
        }

        Set<Resource> results = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) { continue; }
                File file = new File(line);
                String realName = getRealName(line, fullPath);
                boolean isFile = file.isFile();
                boolean matched = isMatch(realName, matchPath);
                if (log.isDebugEnabled()) {
                    log.debug("locate line: {} -> isFile={}, realName={}, matchPath={}, matched={}",
                            line, isFile, realName, matchPath, matched);
                }
                if (isFile && matched) {
                    results.add(new FileSystemResource(file));
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("locate finished: {} results", results.size());
        }
        return results;
    }

    private Set<Resource> searchWindowsWhere(String name, String fullPath, String matchPath) throws Exception {
        String searchRoot = fullPath.isEmpty() ? "C:\\" : fullPath;
        String filePattern = matchPath.contains("/")
                ? matchPath.substring(matchPath.lastIndexOf('/') + 1)
                : matchPath;
        if (filePattern.isEmpty()) { filePattern = "*"; }

        ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", "where", "/r", searchRoot, filePattern);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean exited = process.waitFor(15, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            return null;
        }

        Set<Resource> results = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) { continue; }
                File file = new File(line);
                if (file.isFile() && isMatch(getRealName(line, fullPath), matchPath)) {
                    results.add(new FileSystemResource(file));
                }
            }
        }
        return results;
    }

    private Set<Resource> searchMacMdfind(String name, String fullPath, String matchPath) throws Exception {
        String searchRoot = fullPath.isEmpty() ? "/" : fullPath;
        String nameOnly = matchPath.contains("/")
                ? matchPath.substring(matchPath.lastIndexOf('/') + 1)
                : matchPath;
        if (nameOnly.isEmpty()) { nameOnly = "*"; }

        String mdfindQuery = "kMDItemFSName == '*" + nameOnly.replace("*", "").replace("?", "") + "*'";
        ProcessBuilder pb = new ProcessBuilder("mdfind", "-onlyin", searchRoot, mdfindQuery);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            return null;
        }

        Set<Resource> results = new LinkedHashSet<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) { continue; }
                File file = new File(line);
                if (file.isFile() && isMatch(getRealName(line, fullPath), matchPath)) {
                    results.add(new FileSystemResource(file));
                }
            }
        }
        return results;
    }

    private void walkDirectory(String dirPath, String fullPath, String matchPath,
                               Set<Resource> results, AtomicLong scannedCount) {
        Path startPath = Paths.get(dirPath);
        if (!Files.exists(startPath)) {
            if (log.isWarnEnabled()) {
                log.warn("Directory not found: {}", dirPath);
            }
            return;
        }
        try {
            int maxDepth = calculateMaxDepth(matchPath);
            Files.walkFileTree(startPath, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new FileVisitorImpl(fullPath, matchPath, results, scannedCount));
        } catch (IOException e) {
            if (log.isErrorEnabled()) {
                log.error("Failed to walk directory: {}", dirPath, e);
            }
        }
    }

    private int calculateMaxDepth(String matchPath) {
        if (StringUtils.isEmpty(matchPath)) { return 1; }
        if (matchPath.contains("**")) { return DEFAULT_MAX_DEPTH; }
        return (int) matchPath.chars().filter(c -> c == '/').count() + 1;
    }

    private Set<String> getDir(String fullPath) {
        if (StringUtils.isEmpty(fullPath)) {
            Set<String> roots = ConcurrentHashMap.newKeySet();
            for (File root : File.listRoots()) {
                roots.add(root.getAbsolutePath());
            }
            return roots;
        }
        return Collections.singleton(fullPath);
    }

    private String getFullPath(String path) {
        path = path.replace("\\", "/");
        StringBuilder sb = new StringBuilder();
        if (path.startsWith("/")) { sb.append("/"); }
        for (String item : path.split("/")) {
            if (item.contains("*") || item.contains("?")) { break; }
            if (!sb.toString().equals("/")) { sb.append("/"); }
            sb.append(item);
        }
        return sb.toString().replace("//", "/");
    }

    private String getMatchPath(String path) {
        path = path.replace("\\", "/");
        if (path.startsWith("/")) { path = path.substring(1); }
        StringBuilder sb = new StringBuilder();
        for (String item : path.split("/")) {
            if (item.contains("*") || item.contains("?")) {
                if (!sb.isEmpty()) { sb.append("/"); }
                sb.append(item);
            }
        }
        return sb.toString();
    }

    private String getRealName(String filePath, String rootPath) {
        if (StringUtils.isNullOrEmpty(rootPath)) {
            return filePath.replace("\\", "/");
        }
        rootPath = rootPath.replace("\\", "/");
        filePath = filePath.replace("\\", "/");
        if (filePath.startsWith(rootPath)) {
            return filePath.substring(rootPath.length()).replace("//", "/");
        }
        return filePath;
    }

    private boolean isMatch(String name, String matchPath) {
        if (StringUtils.isEmpty(matchPath) || "*".equals(matchPath) || "**".equals(matchPath)) { return true; }
        if (matchPath.contains("*") || matchPath.contains("?")) {
            return name.matches(globToRegex(matchPath));
        }
        return name.contains(matchPath);
    }

    private static boolean isCommandAvailable(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("which", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly(); return false;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return r.readLine() != null;
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static void tryInstallLocate() {
        try {
            if (log.isInfoEnabled()) {
                log.info("locate not found, attempting auto-install plocate...");
            }
            if (isCommandAvailable("sudo")) {
                if (isCommandAvailable("apt-get")) {
                    runAndForget("sudo", "apt-get", "install", "-y", "plocate");
                    buildUpdatedb();
                    return;
                }
                if (isCommandAvailable("dnf")) {
                    runAndForget("sudo", "dnf", "install", "-y", "plocate");
                    buildUpdatedb();
                    return;
                }
                if (isCommandAvailable("pacman")) {
                    runAndForget("sudo", "pacman", "-S", "--noconfirm", "plocate");
                    buildUpdatedb();
                    return;
                }
                if (isCommandAvailable("apk")) {
                    runAndForget("sudo", "apk", "add", "plocate");
                    buildUpdatedb();
                    return;
                }
                if (isCommandAvailable("zypper")) {
                    runAndForget("sudo", "zypper", "install", "-y", "plocate");
                    buildUpdatedb();
                    return;
                }
            }
            if (isCommandAvailable("apt-get")) {
                runAndForget("apt-get", "install", "-y", "plocate");
                buildUpdatedb();
            } else if (isCommandAvailable("dnf")) {
                runAndForget("dnf", "install", "-y", "plocate");
                buildUpdatedb();
            } else if (isCommandAvailable("pacman")) {
                runAndForget("pacman", "-S", "--noconfirm", "plocate");
                buildUpdatedb();
            } else if (isCommandAvailable("apk")) {
                runAndForget("apk", "add", "plocate");
                buildUpdatedb();
            } else if (isCommandAvailable("zypper")) {
                runAndForget("zypper", "install", "-y", "plocate");
                buildUpdatedb();
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Auto-install locate failed: {}", e.getMessage());
            }
        }
    }

    private static void buildUpdatedb() {
        if (log.isInfoEnabled()) {
            log.info("Building locate database (updatedb) - this may take a minute...");
        }
        if (isCommandAvailable("sudo")) {
            runAndForget(120, "sudo", "updatedb");
        } else {
            runAndForget(120, "updatedb");
        }
    }

    private static void runAndForget(String... cmd) {
        runAndForget(30, cmd);
    }

    private static void runAndForget(int timeoutSecs, String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(timeoutSecs, TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }

    private static String globToRegex(String glob) {
        return glob
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
    }

    private class FileVisitorImpl extends SimpleFileVisitor<Path> {
        private final String fullPath;
        private final String matchPath;
        private final Set<Resource> results;
        private final AtomicLong scannedCount;

        FileVisitorImpl(String fullPath, String matchPath, Set<Resource> results, AtomicLong scannedCount) {
            this.fullPath = fullPath;
            this.matchPath = matchPath;
            this.results = results;
            this.scannedCount = scannedCount;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            scannedCount.incrementAndGet();
            String realName = getRealName(file.toString(), fullPath);
            if (isMatch(realName, matchPath)) {
                results.add(new FileSystemResource(file.toFile()));
                if (log.isTraceEnabled()) {
                    log.trace("Matched: {}", file);
                }
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            if (log.isDebugEnabled()) { log.debug("Failed to access: {}", file, exc); }
            return FileVisitResult.CONTINUE;
        }
    }
}
