package br.com.pdfleitor.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

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
        Optional<String> command = resolveLauncherExecutable();
        if (command.isEmpty()) {
            return;
        }
        String executable = command.get();
        String openCommand = "\"" + executable + "\" \"%1\"";
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
        removeLegacyBackgroundWatcher();
        registerDefaultCommandsWithPowerShell(executable);
    }

    private static Optional<String> resolveLauncherExecutable() {
        String configured = System.getProperty("delvind.launcher", "").trim();
        if (!configured.isEmpty()) {
            try {
                Path path = Path.of(configured).toAbsolutePath().normalize();
                if (Files.isRegularFile(path) && path.getFileName().toString().equalsIgnoreCase(EXE_NAME)) {
                    return Optional.of(path.toString());
                }
            } catch (RuntimeException ignored) {
                // Fall through to the process executable.
            }
        }
        return ProcessHandle.current().info().command()
                .filter(path -> path.toLowerCase(Locale.ROOT).endsWith(EXE_NAME.toLowerCase(Locale.ROOT)));
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

    private static void registerDefaultCommandsWithPowerShell(String executable) {
        String openCommand = "\"" + executable + "\" \"%1\"";
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

    private static void removeLegacyBackgroundWatcher() {
        reg("delete",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v",
                APP_NAME + " Download Watcher",
                "/f");
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
