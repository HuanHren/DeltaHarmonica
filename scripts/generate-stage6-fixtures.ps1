$ErrorActionPreference = 'Stop'
$rootPath = Split-Path -Parent $PSScriptRoot
$outputDir = Join-Path $rootPath 'app\src\test\resources'
foreach ($count in @(120, 360)) {
    $events = [System.Collections.Generic.List[byte]]::new()
    $events.AddRange([byte[]](0, 255, 81, 3, 7, 161, 32)) # 500,000 us per quarter
    for ($i = 0; $i -lt $count; $i++) {
        if ($i -eq 0) { $events.Add([byte]0) } else { $events.AddRange([byte[]](0x81, 0x7A)) }
        $pitch = if ($i % 2 -eq 0) { 60 } else { 62 }
        $events.AddRange([byte[]](0x90, $pitch, 90, 0x81, 0x7A, 0x80, $pitch, 0))
    }
    $events.AddRange([byte[]](0, 0xFF, 0x2F, 0))
    $size = $events.Count
    $header = [byte[]](77,84,104,100,0,0,0,6,0,0,0,1,1,244,77,84,114,107,
        (($size -shr 24) -band 255), (($size -shr 16) -band 255), (($size -shr 8) -band 255), ($size -band 255))
    $seconds = $count / 2
    $path = Join-Path $outputDir "stage6-${seconds}s.mid"
    [System.IO.File]::WriteAllBytes($path, $header + $events.ToArray())
    Write-Output "$path : $count notes"
}
