package br.com.pdfleitor.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class BackgroundDownloadWatcher {
    private static final Path LOCK_FILE = Path.of(System.getProperty("user.home"), "AppData", "Local", "Delvind PDF", "download-watcher.lock");
    private final Set<Path> opened = ConcurrentHashMap.newKeySet();
    private final Instant startedAt = Instant.now();

    void run() {
        try {
            Files.createDirectories(LOCK_FILE.getParent());
            RandomAccessFile lockAccess = new RandomAccessFile(LOCK_FILE.toFile(), "rw");
            FileChannel channel = lockAccess.getChannel();
            FileLock lock = channel.tryLock();
            if (lock == null) {
                AppLog.write("background watcher already running");
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> closeLock(lock, channel, lockAccess)));
        } catch (IOException ex) {
            AppLog.write("background watcher lock failed: " + ex.getMessage());
            return;
        }

        List<Path> folders = watchedFolders();
        if (folders.isEmpty()) {
            AppLog.write("background watcher found no folders");
            sleepForever();
            return;
        }
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            for (Path folder : folders) {
                folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
                AppLog.write("background watching: " + folder);
            }
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watcher.take();
                Path folder = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path fileName) {
                        handleCandidate(folder.resolve(fileName));
                    }
                }
                key.reset();
            }
        } catch (IOException ex) {
            AppLog.write("background watcher io error: " + ex.getMessage());
            sleepForever();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeLock(FileLock lock, FileChannel channel, RandomAccessFile lockAccess) {
        try {
            lock.release();
            channel.close();
            lockAccess.close();
        } catch (IOException ignored) {
            // Process is shutting down.
        }
    }

    private List<Path> watchedFolders() {
        String userHome = System.getProperty("user.home", "");
        String localAppData = System.getenv("LOCALAPPDATA");
        List<Path> folders = new ArrayList<>();
        addIfExists(folders, Path.of(userHome, "Downloads"));
        addIfExists(folders, Path.of(userHome, "Desktop"));
        if (localAppData != null && !localAppData.isBlank()) {
            addIfExists(folders, Path.of(localAppData, "Packages"));
        }
        return folders;
    }

    private void addIfExists(List<Path> folders, Path folder) {
        if (Files.isDirectory(folder) && !folders.contains(folder)) {
            folders.add(folder);
        }
    }

    private void handleCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") || name.endsWith(".crdownload") || !opened.add(path.toAbsolutePath())) {
            return;
        }
        Thread thread = new Thread(() -> openWhenReady(path), "delvind-background-open");
        thread.setDaemon(true);
        thread.start();
    }

    private void openWhenReady(Path path) {
        try {
            long lastSize = -1;
            for (int attempt = 0; attempt < 40; attempt++) {
                if (Files.isRegularFile(path)
                        && Files.getLastModifiedTime(path).toInstant().isAfter(startedAt.minusSeconds(2))) {
                    long size = Files.size(path);
                    if (size > 0 && size == lastSize) {
                        AppLog.write("background opening downloaded pdf: " + path);
                        startDelvind(path);
                        return;
                    }
                    lastSize = size;
                }
                Thread.sleep(500);
            }
        } catch (IOException ex) {
            AppLog.write("background could not open pdf: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void startDelvind(Path pdf) throws IOException {
        if (SingleInstanceService.sendToRunningInstance(new String[]{pdf.toAbsolutePath().toString()})) {
            AppLog.write("background sent pdf to running instance: " + pdf);
            return;
        }
        String executable = ProcessHandle.current().info().command().orElse("");
        if (executable.isBlank()) {
            return;
        }
        new ProcessBuilder(executable, pdf.toAbsolutePath().toString()).start();
    }

    private void sleepForever() {
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
