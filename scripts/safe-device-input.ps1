# Windows handle protection for the local acceptance runner. This relies on
# NTFS and does not protect against administrators, kernel code or injection
# into the runner or ADB process.
if (!('DeltaSafe.InstallFile' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace DeltaSafe {
    public sealed class InstallFile : IDisposable {
        [StructLayout(LayoutKind.Sequential)]
        struct Info {
            internal uint attributes;
            internal System.Runtime.InteropServices.ComTypes.FILETIME created, accessed, written;
            internal uint volume, sizeHigh, sizeLow, links, indexHigh, indexLow;
        }

        [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)]
        static extern SafeFileHandle CreateFileW(string path, uint access, uint share, IntPtr security,
            uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32", SetLastError=true)]
        static extern bool GetFileInformationByHandle(SafeFileHandle handle, out Info info);
        [DllImport("kernel32", CharSet=CharSet.Unicode, SetLastError=true)]
        static extern uint GetFinalPathNameByHandleW(SafeFileHandle handle, StringBuilder path, uint size, uint flags);

        readonly List<SafeFileHandle> ancestors = new List<SafeFileHandle>();
        FileStream stream;
        public string Path { get; private set; }
        public string Identity { get; private set; }
        public long Length { get { return stream.Length; } }

        static IOException NativeError(string operation) {
            return new IOException(operation, new Win32Exception(Marshal.GetLastWin32Error()));
        }

        static SafeFileHandle Open(string path, bool directory, out Info info) {
            const uint OpenReparsePoint = 0x00200000, BackupSemantics = 0x02000000;
            SafeFileHandle handle = CreateFileW(path, 0x80000000, 1, IntPtr.Zero, 3,
                OpenReparsePoint | (directory ? BackupSemantics : 0), IntPtr.Zero);
            try {
                if (handle.IsInvalid) throw NativeError("Cannot protect path: " + path);
                if (!GetFileInformationByHandle(handle, out info)) throw NativeError("Cannot get file identity");
                if ((info.attributes & 0x400) != 0 || ((info.attributes & 16) != 0) != directory ||
                    (!directory && info.links != 1)) throw new IOException("Reparse point, unexpected type or hard link: " + path);
                var final = new StringBuilder(1024);
                uint length = GetFinalPathNameByHandleW(handle, final, (uint)final.Capacity, 0);
                if (length == 0 || length >= final.Capacity) throw NativeError("Cannot resolve handle path");
                if (!String.Equals(final.ToString(), @"\\?\" + path, StringComparison.OrdinalIgnoreCase))
                    throw new IOException("Handle resolved to a different path: " + path);
                return handle;
            } catch {
                handle.Dispose();
                throw;
            }
        }

        public InstallFile(string path) {
            Path = System.IO.Path.GetFullPath(path);
            var root = System.IO.Path.GetPathRoot(Path);
            if (root.Length != 3 || Path.Substring(2).Contains(":") || new DriveInfo(root).DriveFormat != "NTFS")
                throw new IOException("An ordinary path on a local NTFS volume is required");
            var parents = new Stack<string>();
            for (var dir = Directory.GetParent(Path); dir != null; dir = dir.Parent) parents.Push(dir.FullName);
            try {
                Info info;
                foreach (var parent in parents) ancestors.Add(Open(parent, true, out info));
                var file = Open(Path, false, out info);
                try { stream = new FileStream(file, FileAccess.Read); }
                catch { file.Dispose(); throw; }
                Identity = FormatIdentity(info);
            } catch {
                Dispose();
                throw;
            }
        }

        static string FormatIdentity(Info info) {
            return String.Format("{0:x8}:{1:x8}{2:x8}", info.volume, info.indexHigh, info.indexLow);
        }

        public string Hash() {
            Info info;
            using (var reopened = Open(Path, false, out info)) {
                if (Identity != FormatIdentity(info)) throw new IOException("File at path was replaced");
            }
            stream.Position = 0;
            using (var sha = SHA256.Create())
                return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "");
        }

        public void Dispose() {
            if (stream != null) stream.Dispose();
            for (int i = ancestors.Count - 1; i >= 0; i--) ancestors[i].Dispose();
            ancestors.Clear();
        }
    }
}
'@
}

function New-PrivateApkDirectory([string]$Path) {
    if (Test-Path -LiteralPath $Path) { throw 'Staging directory must be new' }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
        $sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'))
    $directory = [IO.DirectoryInfo]::new($Path)
    if ($PSVersionTable.PSEdition -eq 'Desktop') { $directory.Create($acl) }
    else { [IO.FileSystemAclExtensions]::Create($directory, $acl) }
    $actual = Get-Acl -LiteralPath $Path
    if (!$actual.AreAccessRulesProtected -or $actual.GetOwner([Security.Principal.SecurityIdentifier]) -ne $sid) {
        throw 'Staging ACL does not match the current user'
    }
    Assert-PrivateApkAcl $Path
    return $directory.FullName
}

function Assert-PrivateApkAcl([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]) -ne $sid -or $rules.Count -ne 1 -or
        $rules[0].IdentityReference -ne $sid -or $rules[0].AccessControlType -ne 'Allow' -or
        $rules[0].FileSystemRights -ne 'FullControl') { throw 'Unexpected staging access rule' }
}

function Open-InstallFile([string]$Path) {
    return [DeltaSafe.InstallFile]::new($Path)
}

function Assert-InstallFile($Lease, [string]$ExpectedSha256) {
    if ($ExpectedSha256 -notmatch '^[0-9a-fA-F]{64}$' -or $Lease.Hash() -ine $ExpectedSha256) {
        throw [IO.IOException]::new('Install input digest mismatch')
    }
}

function Enter-DeviceRun {
    $mutex = [Threading.Mutex]::new($false, 'Global\DeltaHarmonica.SafeDevice.5037')
    try {
        if (!$mutex.WaitOne(0)) { throw [IO.IOException]::new('Another device run is active') }
        return $mutex
    } catch {
        $mutex.Dispose()
        throw
    }
}
