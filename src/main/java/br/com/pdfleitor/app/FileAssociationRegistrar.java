package br.com.pdfleitor.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

final class FileAssociationRegistrar {
    private static final String APP_NAME = "Delvind PDF";
    private static final String EXE_NAME = "Delvind PDF.exe";
    private static final String PROG_ID = "DelvindPDF.Document";

    private FileAssociationRegistrar() {
    }

    static void registerForCurrentUser() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return;
        }
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isEmpty() || !command.get().endsWith(EXE_NAME)) {
            return;
        }
        String executable = command.get();
        String launchTarget = ensureWorkingDirectoryLauncher(executable);
        String openCommand = "\"" + launchTarget + "\" \"%1\"";
        String icon = "\"" + executable + "\",0";

        regAdd("HKCU\\Software\\Classes\\Applications\\" + EXE_NAME, "ApplicationCompany", "REG_SZ", "Delvind");
        regAdd("HKCU\\Software\\Classes\\Applications\\" + EXE_NAME, "ApplicationName", "REG_SZ", APP_NAME);
        regAdd("HKCU\\Software\\Classes\\Applications\\" + EXE_NAME + "\\SupportedTypes", ".pdf", "REG_SZ", "");
        regAddDefault("HKCU\\Software\\Classes\\Applications\\" + EXE_NAME + "\\shell\\open\\command", openCommand);

        regAddDefault("HKCU\\Software\\Classes\\" + PROG_ID, "Documento PDF");
        regAddDefault("HKCU\\Software\\Classes\\" + PROG_ID + "\\DefaultIcon", icon);
        regAddDefault("HKCU\\Software\\Classes\\" + PROG_ID + "\\shell\\open\\command", openCommand);
        regAddDefault("HKCU\\Software\\Classes\\ACROBAT", "URL:Acrobat Protocol");
        regAdd("HKCU\\Software\\Classes\\ACROBAT", "URL Protocol", "REG_SZ", "");
        regAddDefault("HKCU\\Software\\Classes\\ACROBAT\\DefaultIcon", icon);
        regAddDefault("HKCU\\Software\\Classes\\ACROBAT\\shell\\open\\command", openCommand);
        regAdd("HKCU\\Software\\Classes\\.pdf\\OpenWithProgids", PROG_ID, "REG_NONE", "");
        regAdd("HKCU\\Software\\Classes\\MIME\\Database\\Content Type\\application/pdf", "Extension", "REG_SZ", ".pdf");

        regAdd("HKCU\\Software\\RegisteredApplications", APP_NAME, "REG_SZ", "Software\\Classes\\DelvindPDF.Capabilities");
        regAdd("HKCU\\Software\\Classes\\DelvindPDF.Capabilities", "ApplicationName", "REG_SZ", APP_NAME);
        regAdd("HKCU\\Software\\Classes\\DelvindPDF.Capabilities", "ApplicationDescription", "REG_SZ", "Leitor de PDF Delvind");
        regAdd("HKCU\\Software\\Classes\\DelvindPDF.Capabilities\\FileAssociations", ".pdf", "REG_SZ", PROG_ID);
        regAdd("HKCU\\Software\\Classes\\DelvindPDF.Capabilities\\URLAssociations", "ACROBAT", "REG_SZ", "ACROBAT");
        registerBrowserPdfPolicies();
        configureBrowserPdfPreferences();
        registerBackgroundWatcher(executable);
        registerDefaultCommandsWithPowerShell(launchTarget, executable);
    }

    private static String ensureWorkingDirectoryLauncher(String executable) {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return executable;
        }
        Path executablePath = Path.of(executable);
        Path launcher = Path.of(localAppData, "Delvind PDF Support", "Abrir Delvind PDF.cmd");
        String script = """
                @echo off
                setlocal
                pushd "%s"
                start "" "%s" %%*
                popd
                """.formatted(executablePath.getParent(), executable);
        try {
            Files.createDirectories(launcher.getParent());
            Files.writeString(launcher, script, StandardCharsets.UTF_8);
            return launcher.toString();
        } catch (IOException ex) {
            AppLog.write("working directory launcher creation failed", ex);
            return executable;
        }
    }

    static boolean isCurrentUserPdfDefault() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return true;
        }
        try {
            Process process = new ProcessBuilder(
                    "powershell.exe",
                    "-NoProfile",
                    "-Command",
                    "(Get-ItemProperty -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\.pdf\\UserChoice' -ErrorAction SilentlyContinue).ProgId"
            ).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            return output.contains(PROG_ID);
        } catch (IOException ex) {
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static void openDefaultPdfChooser(Optional<Path> openedPdf) {
        registerForCurrentUser();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return;
        }
        openedPdf.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                .ifPresentOrElse(FileAssociationRegistrar::openWithDialog, FileAssociationRegistrar::openDefaultAppsSettings);
    }

    private static void regAddDefault(String key, String value) {
        reg("add", key, "/ve", "/t", "REG_SZ", "/d", value, "/f");
    }

    private static void regAdd(String key, String name, String type, String value) {
        reg("add", key, "/v", name, "/t", type, "/d", value, "/f");
    }

    private static void reg(String... args) {
        try {
            String systemRoot = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
            Path reg = Path.of(systemRoot, "System32", "reg.exe");
            String[] command = new String[args.length + 1];
            command[0] = reg.toString();
            System.arraycopy(args, 0, command, 1, args.length);
            new ProcessBuilder(command).redirectErrorStream(true).start().waitFor();
        } catch (IOException ex) {
            // Registration is best-effort; the reader must still open normally.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void registerDefaultCommandsWithPowerShell(String launchTarget, String executable) {
        String openCommand = "\"" + launchTarget + "\" \"%1\"";
        String icon = "\"" + executable + "\",0";
        String script = """
                $ErrorActionPreference = 'SilentlyContinue'
                New-Item -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Document\\shell\\open\\command' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Document\\shell\\open\\command' -Value '__OPEN_COMMAND__'
                New-Item -Path 'HKCU:\\Software\\Classes\\Applications\\Delvind PDF.exe\\shell\\open\\command' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\Applications\\Delvind PDF.exe\\shell\\open\\command' -Value '__OPEN_COMMAND__'
                New-Item -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Document\\DefaultIcon' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Document\\DefaultIcon' -Value '__ICON__'
                New-Item -Path 'HKCU:\\Software\\Classes\\.pdf\\OpenWithProgids' -Force | Out-Null
                New-ItemProperty -Path 'HKCU:\\Software\\Classes\\.pdf\\OpenWithProgids' -Name 'DelvindPDF.Document' -PropertyType String -Value '0' -Force | Out-Null
                New-Item -Path 'HKCU:\\Software\\Classes\\.pdf\\OpenWithList\\Delvind PDF.exe' -Force | Out-Null
                New-Item -Path 'HKCU:\\Software\\Classes\\MIME\\Database\\Content Type\\application/pdf' -Force | Out-Null
                New-ItemProperty -Path 'HKCU:\\Software\\Classes\\MIME\\Database\\Content Type\\application/pdf' -Name 'Extension' -PropertyType String -Value '.pdf' -Force | Out-Null
                New-Item -Path 'HKCU:\\Software\\Classes\\ACROBAT\\shell\\open\\command' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\ACROBAT' -Value 'URL:Acrobat Protocol'
                New-ItemProperty -Path 'HKCU:\\Software\\Classes\\ACROBAT' -Name 'URL Protocol' -PropertyType String -Value '' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\ACROBAT\\shell\\open\\command' -Value '__OPEN_COMMAND__'
                New-Item -Path 'HKCU:\\Software\\Classes\\ACROBAT\\DefaultIcon' -Force | Out-Null
                Set-Item -Path 'HKCU:\\Software\\Classes\\ACROBAT\\DefaultIcon' -Value '__ICON__'
                New-Item -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Capabilities\\URLAssociations' -Force | Out-Null
                New-ItemProperty -Path 'HKCU:\\Software\\Classes\\DelvindPDF.Capabilities\\URLAssociations' -Name 'ACROBAT' -PropertyType String -Value 'ACROBAT' -Force | Out-Null
                """
                .replace("__OPEN_COMMAND__", openCommand.replace("'", "''"))
                .replace("__ICON__", icon.replace("'", "''"));
        try {
            Path scriptPath = Files.createTempFile("delvind-pdf-association-", ".ps1");
            Files.writeString(scriptPath, script, StandardCharsets.UTF_8);
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptPath.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
            Files.deleteIfExists(scriptPath);
        } catch (IOException ex) {
            // Registration is best-effort; the reader must still open normally.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void registerBrowserPdfPolicies() {
        regAdd("HKCU\\Software\\Policies\\Google\\Chrome", "AlwaysOpenPdfExternally", "REG_DWORD", "1");
        regAdd("HKCU\\Software\\Policies\\Microsoft\\Edge", "AlwaysOpenPdfExternally", "REG_DWORD", "1");
    }

    private static void registerBackgroundWatcher(String executable) {
        String command = "\"" + executable + "\" --watch-downloads";
        regAdd("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", APP_NAME + " Download Watcher", "REG_SZ", command);
        startBackgroundWatcher(executable);
    }

    private static void startBackgroundWatcher(String executable) {
        try {
            new ProcessBuilder(executable, "--watch-downloads")
                    .directory(Path.of(executable).getParent().toFile())
                    .start();
            AppLog.write("background watcher start requested");
        } catch (IOException ex) {
            AppLog.write("background watcher start failed: " + ex.getMessage());
        }
    }

    private static void configureBrowserPdfPreferences() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null || localAppData.isBlank()) {
            return;
        }
        List<Path> browserRoots = List.of(
                Path.of(localAppData, "Google", "Chrome", "User Data"),
                Path.of(localAppData, "Microsoft", "Edge", "User Data")
        );
        for (Path browserRoot : browserRoots) {
            updateBrowserProfiles(browserRoot);
        }
    }

    private static void updateBrowserProfiles(Path browserRoot) {
        if (!Files.isDirectory(browserRoot)) {
            return;
        }
        try (var profiles = Files.list(browserRoot)) {
            profiles.filter(Files::isDirectory)
                    .map(profile -> profile.resolve("Preferences"))
                    .filter(Files::isRegularFile)
                    .forEach(FileAssociationRegistrar::updateBrowserPreferences);
        } catch (IOException ex) {
            // Browser preferences are best-effort. Chrome/Edge may be open and rewrite the file.
        }
    }

    private static void updateBrowserPreferences(Path preferencesFile) {
        try {
            String json = Files.readString(preferencesFile, StandardCharsets.UTF_8);
            String updated = enableExternalPdfPreference(json);
            if (!json.equals(updated)) {
                Path backup = preferencesFile.resolveSibling("Preferences.delvind-bak");
                if (!Files.exists(backup)) {
                    Files.copy(preferencesFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
                }
                Files.writeString(preferencesFile, updated, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            // Nothing else to do; users can still set this manually in the browser.
        }
    }

    private static String enableExternalPdfPreference(String json) {
        String updated = enableAlwaysOpenPdfExternally(json);
        return enablePdfDownloadOpenExternally(updated);
    }

    private static String enableAlwaysOpenPdfExternally(String json) {
        Pattern existing = Pattern.compile("\"always_open_pdf_externally\"\\s*:\\s*(true|false)");
        var matcher = existing.matcher(json);
        if (matcher.find()) {
            return matcher.replaceAll("\"always_open_pdf_externally\":true");
        }

        String pluginsKey = "\"plugins\":{";
        int pluginsIndex = json.indexOf(pluginsKey);
        if (pluginsIndex >= 0) {
            int insertAt = pluginsIndex + pluginsKey.length();
            return json.substring(0, insertAt)
                    + "\"always_open_pdf_externally\":true,"
                    + json.substring(insertAt);
        }

        int objectEnd = json.lastIndexOf('}');
        if (objectEnd <= 0) {
            return json;
        }
        return json.substring(0, objectEnd)
                + ",\"plugins\":{\"always_open_pdf_externally\":true}"
                + json.substring(objectEnd);
    }

    private static String enablePdfDownloadOpenExternally(String json) {
        Pattern existing = Pattern.compile("\"extensions_to_open\"\\s*:\\s*\"([^\"]*)\"");
        var matcher = existing.matcher(json);
        if (matcher.find()) {
            String value = matcher.group(1);
            if (hasPdfExtension(value)) {
                return json;
            }
            String newValue = value.isBlank() ? "pdf" : value + ":pdf";
            return matcher.replaceFirst("\"extensions_to_open\":\"" + newValue + "\"");
        }

        String downloadKey = "\"download\":{";
        int downloadIndex = json.indexOf(downloadKey);
        if (downloadIndex >= 0) {
            int insertAt = downloadIndex + downloadKey.length();
            return json.substring(0, insertAt)
                    + "\"extensions_to_open\":\"pdf\","
                    + json.substring(insertAt);
        }

        int objectEnd = json.lastIndexOf('}');
        if (objectEnd <= 0) {
            return json;
        }
        return json.substring(0, objectEnd)
                + ",\"download\":{\"extensions_to_open\":\"pdf\"}"
                + json.substring(objectEnd);
    }

    private static boolean hasPdfExtension(String extensions) {
        for (String extension : extensions.split(":")) {
            if ("pdf".equalsIgnoreCase(extension.trim())) {
                return true;
            }
        }
        return false;
    }

    private static void openWithDialog(Path pdfPath) {
        try {
            new ProcessBuilder(
                    "rundll32.exe",
                    "shell32.dll,OpenAs_RunDLL",
                    pdfPath.toAbsolutePath().toString()
            ).start();
        } catch (IOException ex) {
            openDefaultAppsSettings();
        }
    }

    private static void openDefaultAppsSettings() {
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:defaultapps").start();
        } catch (IOException ignored) {
            // Nothing else to do; Windows owns the final default-app choice.
        }
    }
}
