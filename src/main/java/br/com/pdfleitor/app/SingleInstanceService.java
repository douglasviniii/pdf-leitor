package br.com.pdfleitor.app;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class SingleInstanceService {
    private static final int PORT = 39427;
    private static final String END = "__DELVIND_END__";
    private static final String ACK = "__DELVIND_OPENED__";

    private SingleInstanceService() {
    }

    static boolean sendToRunningInstance(String[] args) {
        if (args == null || args.length == 0) {
            AppLog.write("sendToRunningInstance skipped: no args");
            return false;
        }
        AppLog.write("sendToRunningInstance trying args=" + AppLog.args(args));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 160);
            socket.setSoTimeout(3_000);
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            for (String arg : args) {
                writer.write(Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
                writer.newLine();
            }
            writer.write(END);
            writer.newLine();
            writer.flush();
            boolean acknowledged = ACK.equals(reader.readLine());
            AppLog.write("sendToRunningInstance acknowledged=" + acknowledged);
            return acknowledged;
        } catch (IOException ex) {
            AppLog.write("sendToRunningInstance failed; no running instance for args=" + AppLog.args(args), ex);
            return false;
        }
    }

    static void start(MainWindow mainWindow) {
        Thread thread = new Thread(() -> listen(mainWindow), "delvind-single-instance");
        thread.setDaemon(true);
        thread.start();
    }

    private static void listen(MainWindow mainWindow) {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT));
            AppLog.write("single instance listener started");
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket socket = serverSocket.accept();
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    List<Path> paths = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null && !END.equals(line)) {
                        decode(line)
                                .flatMap(PdfLeitorApp::argumentToPath)
                                .ifPresent(paths::add);
                    }
                    paths.forEach(path -> AppLog.write("received pdf from second instance: " + path));
                    boolean opened = !paths.isEmpty() && openOnUiThread(mainWindow, paths);
                    writer.write(opened ? ACK : "__DELVIND_REJECTED__");
                    writer.newLine();
                    writer.flush();
                    if (!opened) {
                        AppLog.write("single instance listener stopping because UI did not acknowledge");
                        break;
                    }
                } catch (IOException ignored) {
                    AppLog.write("single instance malformed launch message", ignored);
                    // Keep the first instance alive even when one launch message is malformed.
                }
            }
        } catch (IOException ex) {
            AppLog.write("single instance listener unavailable", ex);
            // If the port is unavailable, the app still works without tab handoff.
        }
    }

    private static Optional<String> decode(String line) {
        try {
            return Optional.of(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static boolean openOnUiThread(MainWindow mainWindow, List<Path> paths) {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean opened = new AtomicBoolean(false);
        try {
            Platform.runLater(() -> {
                try {
                    paths.forEach(mainWindow::openPdf);
                    mainWindow.bringToFront();
                    opened.set(true);
                } finally {
                    completed.countDown();
                }
            });
            return completed.await(2, TimeUnit.SECONDS) && opened.get();
        } catch (IllegalStateException ex) {
            AppLog.write("single instance JavaFX UI unavailable", ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
