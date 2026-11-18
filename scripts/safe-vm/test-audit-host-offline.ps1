$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'audit-host-readonly.ps1') -LibraryOnly
function Fixture { return @{platforms=@{hyperv=$true;vmware=$false;virtualbox=$false};cpu=@{firmware=$true;extensions=$true};sdk=@{exists=$true;emulator=$true;platformTools=$true;avdmanager=$true;images=@('image')};memory=@{freeGiB=8};disk=@{freeGiB=80};adb=@{listener5037=$false};network=@{isolationVerified=$true}} }
function Check($name,$s,$expected) { $r=Invoke-HostAudit { $s }; if (($r.assessment.reasons -join '|') -notmatch $expected) { throw "Failed $name" }; "PASS $name" }
$s=Fixture; Check 'Hyper-V fixture' $s 'Nested virtualization'
$s=Fixture; $s.platforms.hyperv=$false;$s.platforms.vmware=$true; Check 'VMware fixture' $s 'Nested virtualization'
$s=Fixture; $s.platforms.hyperv=$false;$s.platforms.virtualbox=$true; Check 'VirtualBox fixture' $s 'Nested virtualization'
$s=Fixture; $s.platforms.hyperv=$false; Check 'no platform' $s 'No confirmed'
$s=Fixture; $s.cpu.extensions=$false; Check 'CPU unsupported' $s 'extensions not confirmed'
$s=Fixture; $s.cpu.firmware=$false; Check 'firmware disabled' $s 'Firmware virtualization'
$s=Fixture; $s.sdk.exists=$false; Check 'SDK missing' $s 'SDK missing'
$s=Fixture; $s.sdk.avdmanager=$false; Check 'SDK incomplete' $s 'SDK incomplete'
$s=Fixture; $s.sdk.images=@(); Check 'image missing' $s 'No system image'
$s=Fixture; $s.memory.freeGiB=1; Check 'low memory' $s 'memory below'
$s=Fixture; $s.disk.freeGiB=1; Check 'low disk' $s 'disk free'
$s=Fixture; $s.adb.listener5037=$true; Check 'port occupied' $s '5037 is occupied'
$s=Fixture; $s.network.isolationVerified=$false; Check 'network isolation' $s 'isolation unverified'
'13 synthetic host assessments passed; no host collector or devices invoked.'
