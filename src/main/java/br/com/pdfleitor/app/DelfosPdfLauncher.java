package br.com.pdfleitor.app;

import java.util.Arrays;

public final class DelfosPdfLauncher {
    private DelfosPdfLauncher() {
    }

    public static void main(String[] args) {
        try {
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                    AppLog.write("uncaught throwable on " + thread.getName(), throwable));
            AppLog.mark("launcher-entered");
            AppLog.write("launcher args=" + AppLog.args(args));
            if (Arrays.asList(args).contains("--watch-downloads")) {
                AppLog.write("launcher entering download watcher mode");
                new BackgroundDownloadWatcher().run();
                AppLog.write("launcher leaving download watcher mode");
                exitAuxiliaryProcess();
            }
            AppLog.write("launcher starting JavaFX app");
            PdfLeitorApp.main(args);
            AppLog.write("launcher JavaFX app returned");
        } catch (Throwable throwable) {
            AppLog.write("launcher fatal error", throwable);
            throw throwable;
        }
    }

    private static void exitAuxiliaryProcess() {
        System.exit(0);
    }
}
