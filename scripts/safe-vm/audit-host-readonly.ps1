param([switch]$LibraryOnly)
# Read-only collector and pure assessment. Never invokes adb, an emulator, or VM lifecycle operations.
function Get-HostAssessment($s) {
    $issues=[System.Collections.Generic.List[string]]::new()
    if (!$s.platforms.hyperv -and !$s.platforms.vmware -and !$s.platforms.virtualbox) { $issues.Add('No confirmed usable VM manager') }
    if ($s.cpu.firmware -eq $false) { $issues.Add('Firmware virtualization disabled or unavailable') }
    if ($s.cpu.extensions -eq $false) { $issues.Add('CPU virtualization extensions not confirmed') }
    if (!$s.sdk.exists) { $issues.Add('Android SDK missing') }
    elseif (!$s.sdk.emulator -or !$s.sdk.platformTools -or !$s.sdk.avdmanager) { $issues.Add('Android SDK incomplete') }
    if (@($s.sdk.images).Count -eq 0) { $issues.Add('No system image') }
    if ($s.memory.freeGiB -lt 4) { $issues.Add('Available memory below 4 GiB') }
    if ($s.disk.freeGiB -lt 40) { $issues.Add('Candidate disk free space below 40 GiB') }
    if ($s.adb.listener5037 -eq $true) { $issues.Add('Host 5037 is occupied; isolation not established') }
    if (!$s.network.isolationVerified) { $issues.Add('Network and USB isolation unverified') }
    $issues.Add('Nested virtualization and guest acceleration not runtime verified')
    return @{ verdict='ENVIRONMENT PARTIAL'; reasons=@($issues) }
}
function Invoke-HostAudit([scriptblock]$Collector) {
    $snapshot=& $Collector
    if ($null -eq $snapshot) { throw 'Host collection failed' }
    return @{ collectedAtUtc=[DateTime]::UtcNow.ToString('o'); evidence=$snapshot; assessment=(Get-HostAssessment $snapshot) }
}
function Get-ReadOnlyHostSnapshot {
    $os=Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    $cpu=Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1
    $computer=Get-CimInstance Win32_ComputerSystem -ErrorAction Stop
    $drive=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='D:'" -ErrorAction Stop
    $sdk=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    $images=@(Get-ChildItem -LiteralPath (Join-Path $sdk 'system-images') -Recurse -Filter source.properties -ErrorAction SilentlyContinue | ForEach-Object {
        $props=@{}; foreach($line in Get-Content -LiteralPath $_.FullName) { if($line -match '^([^#=]+)=(.*)$') { $props[$Matches[1]]=$Matches[2] } }
        @{ path=$_.DirectoryName; api=$props['AndroidVersion.ApiLevel']; abi=$props['SystemImage.Abi']; tag=$props['SystemImage.TagId']; revision=$props['Pkg.Revision'] }
    })
    $listener='UNKNOWN'; try { $entries=@(Get-NetTCPConnection -State Listen -LocalPort 5037 -ErrorAction Stop); $listener=($entries.Count -gt 0) } catch { $listener='UNKNOWN' }
    $vbox=(Get-Command VBoxManage -ErrorAction SilentlyContinue) -or (Test-Path 'C:\Program Files\Oracle\VirtualBox\VBoxManage.exe')
    $vmware=(Get-Command vmrun -ErrorAction SilentlyContinue) -or (Test-Path 'C:\Program Files (x86)\VMware\VMware Workstation\vmrun.exe')
    $hyperv=[bool](Get-Command Get-VM -ErrorAction SilentlyContinue)
    $result=@{ system=@{ os=$os.Caption; version=$os.Version; build=$os.BuildNumber; architecture=$os.OSArchitecture; powerShell=$PSVersionTable.PSVersion.ToString(); isAdmin=([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) }; cpu=@{ name=$cpu.Name; cores=$cpu.NumberOfCores; threads=$cpu.NumberOfLogicalProcessors; firmware=$cpu.VirtualizationFirmwareEnabled; extensions=$cpu.VMMonitorModeExtensions; slat=$cpu.SecondLevelAddressTranslationExtensions; hypervisor=$computer.HypervisorPresent }; memory=@{totalGiB=[math]::Round($computer.TotalPhysicalMemory/1GB,2); freeGiB=[math]::Round($os.FreePhysicalMemory/1MB,2)}; disk=@{drive='D:'; filesystem=$drive.FileSystem; freeGiB=[math]::Round($drive.FreeSpace/1GB,2)}; platforms=@{hyperv=$hyperv;vmware=[bool]$vmware;virtualbox=[bool]$vbox}; sdk=@{exists=(Test-Path $sdk);emulator=(Test-Path (Join-Path $sdk 'emulator\emulator.exe'));platformTools=(Test-Path (Join-Path $sdk 'platform-tools\adb.exe'));avdmanager=[bool](Get-ChildItem (Join-Path $sdk 'cmdline-tools') -Recurse -Filter avdmanager.bat -ErrorAction SilentlyContinue | Select-Object -First 1);images=$images};adb=@{processCount=@(Get-Process adb -ErrorAction SilentlyContinue).Count;listener5037=$listener};network=@{isolationVerified=$false} }
    return $result
}
if (!$LibraryOnly) { Invoke-HostAudit { Get-ReadOnlyHostSnapshot } | ConvertTo-Json -Depth 10 }
