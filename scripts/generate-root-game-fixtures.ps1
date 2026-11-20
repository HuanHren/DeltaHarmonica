[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Add-VariableLengthValue {
    param([Collections.Generic.List[byte]]$Track, [int]$Value)
    $bytes = [Collections.Generic.List[byte]]::new()
    $bytes.Add([byte]($Value -band 127))
    for ($remaining = $Value -shr 7; $remaining -gt 0; $remaining = $remaining -shr 7) {
        $bytes.Add([byte](128 -bor ($remaining -band 127)))
    }
    for ($index = $bytes.Count - 1; $index -ge 0; $index--) { $Track.Add($bytes[$index]) }
}

function Write-Fixture {
    param([string]$Name, [int[]]$Pitches, [int[]]$Intervals, [int[]]$Holds, [int]$Count)
    # PPQ 500 at 500,000 microseconds/quarter gives exactly one tick per millisecond.
    $track = [Collections.Generic.List[byte]]::new()
    $track.AddRange([byte[]](0, 0xFF, 0x51, 3, 7, 161, 32))
    $track.AddRange([byte[]](0, 0xFF, 0x58, 4, 4, 2, 24, 8))
    $start = 0
    $lastEnd = 0
    $notes = foreach ($index in 0..($Count - 1)) {
        $pitch = $Pitches[$index % $Pitches.Count]
        $hold = $Holds[$index % $Holds.Count]
        Add-VariableLengthValue -Track $track -Value ($start - $lastEnd)
        $track.AddRange([byte[]](0x90, $pitch, 90))
        Add-VariableLengthValue -Track $track -Value $hold
        $track.AddRange([byte[]](0x80, $pitch, 0))
        [pscustomobject]@{
            noteIndex = $index + 1
            midiNote = $pitch
            sourceStartMs = $start
            sourceDurationMs = $hold
        }
        $lastEnd = $start + $hold
        $start += $Intervals[$index % $Intervals.Count]
    }
    $track.AddRange([byte[]](0, 0xFF, 0x2F, 0))
    $size = $track.Count
    $header = [byte[]](77, 84, 104, 100, 0, 0, 0, 6, 0, 0, 0, 1, 1, 244, 77, 84, 114, 107,
        (($size -shr 24) -band 255), (($size -shr 16) -band 255), (($size -shr 8) -band 255), ($size -band 255))
    $midiPath = Join-Path $directory "$Name.mid"
    $scorePath = Join-Path $directory "$Name.expected.csv"
    [IO.File]::WriteAllBytes($midiPath, $header + $track.ToArray())
    $notes | Export-Csv -LiteralPath $scorePath -NoTypeInformation -Encoding UTF8
    [pscustomobject]@{
        name = $Name
        noteCount = $Count
        sourceDurationMs = $lastEnd
        midiSha256 = (Get-FileHash -LiteralPath $midiPath).Hash
    }
}

$directory = (New-Item -Path $OutputDirectory -ItemType Directory -Force).FullName
Write-Fixture -Name 'A-natural-8' -Count 8 -Pitches @(62, 64, 67, 60, 69, 71, 65, 72) `
    -Intervals @(500, 500, 320, 750) -Holds @(310, 180, 150, 520)
# Accidentals chosen here require the semitone button even with alternative fingerings.
$switchPitches = @(75, 55, 63, 79, 51, 67, 73, 57, 49, 81, 68, 53, 78, 62, 58, 74)
Write-Fixture -Name 'B-controls-16' -Count 16 -Pitches $switchPitches `
    -Intervals @(470, 320, 500, 370) -Holds @(290, 100, 275, 200)
Write-Fixture -Name 'C-dense-32' -Count 32 -Pitches @(62, 62, 63, 63, 62, 74, 50, 50, 75, 75, 51, 51, 62, 62, 62, 67) `
    -Intervals @(133, 112, 150, 87, 112, 136, 120, 200) -Holds @(82, 57, 85, 50, 57, 75, 65, 135)
Write-Fixture -Name 'B-full-360' -Count 360 -Pitches $switchPitches `
    -Intervals @(430, 420, 490, 660) -Holds @(190, 120, 310, 440)
