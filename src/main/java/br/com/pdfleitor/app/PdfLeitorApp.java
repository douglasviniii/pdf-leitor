package br.com.pdfleitor.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

public class PdfLeitorApp extends Application {
    private static final String DEFAULT_PROMPT_VERSION = "1.0.4";
    private static final String DEFAULT_PROMPT_KEY = "default-pdf-prompt-version";

    private MainWindow mainWindow;
    private final ExecutorService startupExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "delvind-startup");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                AppLog.write("uncaught throwable on " + thread.getName(), throwable));
        AppLog.mark("app-main-entered");
        AppLog.write("PdfLeitorApp.main args=" + AppLog.args(args));
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        String[] rawArgs = getParameters().getRaw().toArray(String[]::new);
        AppLog.mark("javafx-start-entered");
        AppLog.write("app start args=" + AppLog.args(rawArgs));
        if (SingleInstanceService.sendToRunningInstance(rawArgs)) {
            AppLog.write("running instance confirmed PDF tab open");
            Platform.exit();
            return;
        }
        AppLog.write("creating main window");
        mainWindow = new MainWindow(stage);
        Scene scene = new Scene(mainWindow.root(), 1180, 780);
        Optional.ofNullable(PdfLeitorApp.class.getResource("/style.css"))
                .map(Object::toString)
                .ifPresent(scene.getStylesheets()::add);
        stage.setTitle("Delvind PDF");
        Optional.ofNullable(PdfLeitorApp.class.getResourceAsStream("/icons/app-icon.png"))
                .ifPresent(stream -> stage.getIcons().add(new Image(stream)));
        stage.setScene(scene);
        stage.setMaximized(true);
        AppLog.write("showing primary stage");
        stage.show();
        stage.toFront();
        AppLog.mark("stage-shown");
        AppLog.write("primary stage shown");

        AppLog.write("starting acknowledged single instance listener");
        SingleInstanceService.start(mainWindow);
        openStartupPdfsAsync(rawArgs);
        configureWindowsIntegrationAsync(Arrays.stream(rawArgs).findFirst());
    }

    @Override
    public void stop() {
        AppLog.write("app stop");
        startupExecutor.shutdownNow();
        if (mainWindow != null) {
            mainWindow.shutdownResources();
        }
    }

    static Optional<Path> argumentToPath(String rawArgument) {
        if (rawArgument == null || rawArgument.isBlank() || rawArgument.startsWith("--")) {
            return Optional.empty();
        }
        String argument = rawArgument.trim();
        if (argument.regionMatches(true, 0, "file:", 0, 5)) {
            return pathFromFileUri(argument);
        }
        if (argument.regionMatches(true, 0, "acrobat:", 0, 8)) {
            return pathFromAcrobatUri(argument);
        }
        try {
            Path path = Path.of(argument);
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath().normalize());
            }
        } catch (InvalidPathException ignored) {
            // Some systems pass URL/protocol strings before the real file path exists.
        }
        return Optional.empty();
    }

    private void openStartupPdfsAsync(String[] args) {
        startupExecutor.submit(() -> Arrays.stream(args)
                .map(PdfLeitorApp::argumentToPath)
                .flatMap(Optional::stream)
                .distinct()
                .forEach(path -> Platform.runLater(() -> {
                    AppLog.write("opening startup pdf=" + path);
                    mainWindow.openPdf(path);
                    mainWindow.bringToFront();
                })));
    }

    private void configureWindowsIntegrationAsync(Optional<String> firstArgument) {
        startupExecutor.submit(() -> {
            try {
                FileAssociationRegistrar.registerForCurrentUser();
                Optional<Path> openedPdf = firstArgument.flatMap(PdfLeitorApp::argumentToPath);
                maybePromptDefaultPdf(openedPdf);
            } catch (RuntimeException ex) {
                AppLog.write("windows integration failed", ex);
            }
        });
    }

    private void maybePromptDefaultPdf(Optional<Path> openedPdf) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            return;
        }
        Preferences prefs = Preferences.userNodeForPackage(PdfLeitorApp.class);
        if (DEFAULT_PROMPT_VERSION.equals(prefs.get(DEFAULT_PROMPT_KEY, ""))) {
            return;
        }
        if (FileAssociationRegistrar.isCurrentUserPdfDefault()) {
            prefs.put(DEFAULT_PROMPT_KEY, DEFAULT_PROMPT_VERSION);
            return;
        }
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Delvind PDF");
            alert.setHeaderText("Fazer Delvind PDF padrao para abrir PDFs?");
            alert.setContentText("Isso ajuda arquivos baixados e sistemas externos a abrirem direto no Delvind PDF.");
            ButtonType configure = new ButtonType("Definir padrao");
            ButtonType later = new ButtonType("Agora nao");
            alert.getButtonTypes().setAll(configure, later);
            Optional<ButtonType> result = alert.showAndWait();
            prefs.put(DEFAULT_PROMPT_KEY, DEFAULT_PROMPT_VERSION);
            if (result.isPresent() && result.get() == configure) {
                startupExecutor.submit(() -> FileAssociationRegistrar.openDefaultPdfChooser(openedPdf));
            }
        });
    }

    private static Optional<Path> pathFromFileUri(String argument) {
        try {
            Path path = Path.of(URI.create(argument));
            if (Files.isRegularFile(path)) {
                return Optional.of(path.toAbsolutePath().normalize());
            }
        } catch (IllegalArgumentException ignored) {
            String decoded = URLDecoder.decode(argument.replaceFirst("(?i)^file:/+", ""), StandardCharsets.UTF_8);
            try {
                Path path = Path.of(decoded);
                if (Files.isRegularFile(path)) {
                    return Optional.of(path.toAbsolutePath().normalize());
                }
            } catch (InvalidPathException ignoredAgain) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> pathFromAcrobatUri(String argument) {
        String decoded = URLDecoder.decode(argument, StandardCharsets.UTF_8);
        int fileIndex = decoded.toLowerCase().indexOf("file=");
        if (fileIndex >= 0) {
            String file = decoded.substring(fileIndex + 5);
            int separator = file.indexOf('&');
            if (separator >= 0) {
                file = file.substring(0, separator);
            }
            return argumentToPath(file);
        }
        return Optional.empty();
    }
}
