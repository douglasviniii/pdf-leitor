package br.com.pdfleitor.app;

import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DownloadWatcherService {
    private final MainWindow mainWindow;
    private final Set<Path> opened = ConcurrentHashMap.newKeySet();
    private final Instant startedAt = Instant.now();

    DownloadWatcherService(MainWindow mainWindow) {
        this.mainWindow = mainWindow;
    }

    void start() {
        Path downloads = downloadsFolder();
        if (!Files.isDirectory(downloads)) {
            AppLog.write("downloads folder not found: " + downloads);
            return;
        }
        Thread thread = new Thread(() -> watch(downloads), "delvind-download-watch");
        thread.setDaemon(true);
        thread.start();
        AppLog.write("watching downloads: " + downloads);
    }

    private Path downloadsFolder() {
        String userHome = System.getProperty("user.home", "");
        return Path.of(userHome, "Downloads");
    }

    private void watch(Path downloads) {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            downloads.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watcher.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context() instanceof Path fileName) {
                        handleCandidate(downloads.resolve(fileName));
                    }
                }
                if (!key.reset()) {
                    break;
                }
            }
        } catch (IOException ex) {
            // Download watching is a convenience; the reader still works normally.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleCandidate(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".pdf") || name.endsWith(".crdownload") || !opened.add(path.toAbsolutePath())) {
            return;
        }
        Thread opener = new Thread(() -> openWhenReady(path), "delvind-download-open");
        opener.setDaemon(true);
        opener.start();
    }

    private void openWhenReady(Path path) {
        try {
            long lastSize = -1;
            for (int attempt = 0; attempt < 30; attempt++) {
                if (Files.isRegularFile(path)
                        && Files.getLastModifiedTime(path).toInstant().isAfter(startedAt.minusSeconds(2))) {
                    long size = Files.size(path);
                    if (size > 0 && size == lastSize) {
                        AppLog.write("opening new downloaded pdf: " + path);
                        Platform.runLater(() -> mainWindow.openPdf(path));
                        return;
                    }
                    lastSize = size;
                }
                Thread.sleep(500);
            }
        } catch (IOException ignored) {
            // File was likely still controlled by the browser.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
