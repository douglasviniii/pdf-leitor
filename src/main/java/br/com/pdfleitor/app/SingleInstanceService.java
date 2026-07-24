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
import java.util.Base64;
import java.util.Optional;

final class SingleInstanceService {
    private static final int PORT = 39427;
    private static final String END = "__DELVIND_END__";

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
            socket.setSoTimeout(500);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                for (String arg : args) {
                    writer.write(Base64.getEncoder().encodeToString(arg.getBytes(StandardCharsets.UTF_8)));
                    writer.newLine();
                }
                writer.write(END);
                writer.newLine();
                writer.flush();
            }
            AppLog.write("sendToRunningInstance success");
            return true;
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
                     BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                    reader.lines()
                            .takeWhile(line -> !END.equals(line))
                            .map(SingleInstanceService::decode)
                            .flatMap(Optional::stream)
                            .map(PdfLeitorApp::argumentToPath)
                            .flatMap(Optional::stream)
                            .forEach(path -> {
                                AppLog.write("received pdf from second instance: " + path);
                                openOnUiThread(mainWindow, path);
                            });
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

    private static void openOnUiThread(MainWindow mainWindow, Path path) {
        Platform.runLater(() -> mainWindow.openPdf(path));
    }
}
