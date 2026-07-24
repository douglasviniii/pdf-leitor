package br.com.pdfleitor.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppLog {
    private static final AtomicBoolean ENVIRONMENT_WRITTEN = new AtomicBoolean(false);

    private AppLog() {
    }

    static void write(String message) {
        if (ENVIRONMENT_WRITTEN.compareAndSet(false, true)) {
            writeLine("========== DELVIND PDF DIAGNOSTICO ==========");
            writeLine("java.version=" + System.getProperty("java.version"));
            writeLine("java.vendor=" + System.getProperty("java.vendor"));
            writeLine("java.home=" + System.getProperty("java.home"));
            writeLine("os.name=" + System.getProperty("os.name"));
            writeLine("os.version=" + System.getProperty("os.version"));
            writeLine("os.arch=" + System.getProperty("os.arch"));
            writeLine("user.name=" + System.getProperty("user.name"));
            writeLine("user.home=" + System.getProperty("user.home"));
            writeLine("user.dir=" + System.getProperty("user.dir"));
            writeLine("command=" + ProcessHandle.current().info().command().orElse(""));
            writeLine("commandLine=" + ProcessHandle.current().info().commandLine().orElse(""));
            writeLine("pid=" + ProcessHandle.current().pid());
            writeLine("LOCALAPPDATA=" + System.getenv("LOCALAPPDATA"));
            writeLine("TEMP=" + System.getenv("TEMP"));
            writeLine("=============================================");
        }
        writeLine(message);
    }

    static void write(String message, Throwable throwable) {
        write(message + ": " + throwable);
        StringWriter buffer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(buffer));
        writeLine(buffer.toString());
    }

    private static void writeLine(String message) {
        String line = "[" + LocalDateTime.now() + "] " + message + System.lineSeparator();
        for (Path logFile : logFiles()) {
            try {
                Files.createDirectories(logFile.getParent());
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // Logging must never block the PDF reader.
            }
        }
    }

    private static List<Path> logFiles() {
        List<Path> files = new ArrayList<>();
        String userHome = System.getProperty("user.home", "");
        String localAppData = System.getenv("LOCALAPPDATA");
        String temp = System.getenv("TEMP");

        if (localAppData != null && !localAppData.isBlank()) {
            files.add(Path.of(localAppData, "Delvind PDF", "logs", "delvind.log"));
        }
        if (!userHome.isBlank()) {
            files.add(Path.of(userHome, "delvind-pdf-debug.log"));
            files.add(Path.of(userHome, "Desktop", "delvind-pdf-debug.log"));
            files.add(Path.of(userHome, "Area de Trabalho", "delvind-pdf-debug.log"));
            files.add(Path.of(userHome, "Área de Trabalho", "delvind-pdf-debug.log"));
        }
        if (temp != null && !temp.isBlank()) {
            files.add(Path.of(temp, "delvind-pdf-debug.log"));
        }
        return files;
    }

    static void mark(String marker) {
        try {
            Path markerFile = Path.of(System.getProperty("java.io.tmpdir"), "delvind-pdf-" + marker + ".marker");
            Files.writeString(markerFile, LocalDateTime.now().toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Diagnostic markers are best-effort.
        }
    }

    static String args(String[] args) {
        return Arrays.toString(args == null ? new String[0] : args);
    }
}
