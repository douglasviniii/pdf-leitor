using System;
using System.Diagnostics;
using System.IO;
using System.Linq;

internal static class DelvindPdfNativeLauncher
{
    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string installedDirectory = AppDomain.CurrentDomain.BaseDirectory;
            string portableDirectory = @"C:\Delvind PDF Java 0.4.21";
            string javaw = Path.Combine(installedDirectory, "runtime", "bin", "javaw.exe");
            string appDirectory = Path.Combine(installedDirectory, "app");
            string workingDirectory = installedDirectory;

            if (!File.Exists(javaw))
            {
                javaw = Path.Combine(portableDirectory, "jdk", "bin", "javaw.exe");
                appDirectory = Path.Combine(portableDirectory, "app");
                workingDirectory = portableDirectory;
            }
            string jar = Directory.GetFiles(appDirectory, "delvind-pdf-*.jar")
                .OrderByDescending(Path.GetFileName, StringComparer.OrdinalIgnoreCase)
                .FirstOrDefault();

            if (!File.Exists(javaw) || string.IsNullOrEmpty(jar))
            {
                return 2;
            }

            string forwardedArguments = string.Join(" ", args.Select(Quote));
            var startInfo = new ProcessStartInfo
            {
                FileName = javaw,
                Arguments = "-Dfile.encoding=UTF-8 -cp " + Quote(jar)
                    + " br.com.pdfleitor.app.DelfosPdfLauncher"
                    + (forwardedArguments.Length == 0 ? "" : " " + forwardedArguments),
                WorkingDirectory = workingDirectory,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            Process.Start(startInfo);
            return 0;
        }
        catch
        {
            return 1;
        }
    }

    private static string Quote(string value)
    {
        if (value == null)
        {
            return "\"\"";
        }

        return "\"" + value.Replace("\"", "\\\"") + "\"";
    }
}
