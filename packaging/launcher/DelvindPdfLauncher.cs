using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

internal static class DelvindPdfLauncher
{
    private const uint JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitKillOnJobClose = 0x00002000;

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateJobObject(IntPtr securityAttributes, string name);

    [DllImport("kernel32.dll")]
    private static extern bool SetInformationJobObject(IntPtr job, uint infoClass, IntPtr info, uint length);

    [DllImport("kernel32.dll")]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    [STAThread]
    private static void Main(string[] args)
    {
        try
        {
            string root = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            string javaw = Path.Combine(root, "runtime", "bin", "javaw.exe");
            string jar = Path.Combine(root, "app", "delvind-pdf-1.0.4.jar");
            string launcher = Path.Combine(root, "Delvind PDF.exe");

            if (!File.Exists(javaw) || !File.Exists(jar))
            {
                MessageBox.Show(
                    "A instalação do Delvind PDF está incompleta. Reinstale o programa.",
                    "Delvind PDF",
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
                return;
            }

            var command = new StringBuilder();
            Append(command, "-Dfile.encoding=UTF-8");
            Append(command, "-Ddelvind.launcher=" + launcher);
            Append(command, "-cp");
            Append(command, jar);
            Append(command, "br.com.pdfleitor.app.DelfosPdfLauncher");
            foreach (string arg in args)
            {
                Append(command, arg);
            }

            var start = new ProcessStartInfo
            {
                FileName = javaw,
                Arguments = command.ToString(),
                WorkingDirectory = root,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using (Process child = Process.Start(start))
            {
                IntPtr job = CreateKillOnCloseJob();
                try
                {
                    if (job != IntPtr.Zero)
                    {
                        AssignProcessToJobObject(job, child.Handle);
                    }
                    child.WaitForExit();
                }
                finally
                {
                    if (job != IntPtr.Zero)
                    {
                        CloseHandle(job);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            MessageBox.Show(
                "Não foi possível abrir o Delvind PDF.\r\n\r\n" + ex.Message,
                "Delvind PDF",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
        }
    }

    private static IntPtr CreateKillOnCloseJob()
    {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero)
        {
            return IntPtr.Zero;
        }

        var info = new ExtendedLimitInformation();
        info.BasicLimitInformation.LimitFlags = JobObjectLimitKillOnJobClose;
        int size = Marshal.SizeOf(typeof(ExtendedLimitInformation));
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(info, buffer, false);
            if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, buffer, (uint)size))
            {
                CloseHandle(job);
                return IntPtr.Zero;
            }
            return job;
        }
        finally
        {
            Marshal.FreeHGlobal(buffer);
        }
    }

    private static void Append(StringBuilder command, string value)
    {
        if (command.Length > 0)
        {
            command.Append(' ');
        }
        command.Append(Quote(value));
    }

    private static string Quote(string value)
    {
        if (value.Length == 0)
        {
            return "\"\"";
        }

        var result = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char character in value)
        {
            if (character == '\\')
            {
                backslashes++;
                continue;
            }
            if (character == '\"')
            {
                result.Append('\\', backslashes * 2 + 1);
                result.Append('\"');
                backslashes = 0;
                continue;
            }
            result.Append('\\', backslashes);
            backslashes = 0;
            result.Append(character);
        }
        result.Append('\\', backslashes * 2);
        result.Append('\"');
        return result.ToString();
    }
}
