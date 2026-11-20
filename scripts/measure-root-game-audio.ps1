[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ExpectedCsv,
    [Parameter(Mandatory)][string]$AudacityLabels,
    [Parameter(Mandatory)][ValidateSet(1, 2)][int]$Speed,
    [double]$TimeZeroMs = [double]::NaN
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$culture = [Globalization.CultureInfo]::InvariantCulture

function Get-PercentileMs {
    param([double[]]$Values, [double]$Percentile)
    if ($Values.Length -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    return $sorted[[Math]::Ceiling($sorted.Count * $Percentile) - 1]
}

$score = @(Import-Csv -LiteralPath $ExpectedCsv)
if ($score.Count -eq 0) { throw 'The expected score is empty.' }
$nextIndex = 1
$expectedEnd = 0
foreach ($row in $score) {
    if ([int]$row.noteIndex -ne $nextIndex -or [int]$row.midiNote -notin (0..127) -or
            [long]$row.sourceDurationMs -le 0 -or [long]$row.sourceStartMs -lt $expectedEnd) {
        throw "Invalid expected score row at index $nextIndex."
    }
    $expectedEnd = [long]$row.sourceStartMs + [long]$row.sourceDurationMs
    $nextIndex++
}

$notesByIndex = @{}
$extra = 0
$repeated = 0
$rowNumber = 0
foreach ($line in Get-Content -LiteralPath $AudacityLabels) {
    $rowNumber++
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $fields = $line -split "\t", 3
    if ($fields.Count -ne 3 -or $fields[2].Trim() -cnotmatch '^(?:(\d+)|(extra|repeat))\s+(\d{1,3}|\?)$') {
        throw "Invalid Audacity label at line $rowNumber."
    }
    $index = if ($Matches[1]) { [int]$Matches[1] } else { 0 }
    $kind = $Matches[2]
    $midiNote = if ($Matches[3] -eq '?') { $null } else { [int]$Matches[3] }
    $start = 1000 * [double]::Parse($fields[0], $culture)
    $end = 1000 * [double]::Parse($fields[1], $culture)
    if ($start -lt 0 -or $end -le $start -or [double]::IsNaN($start + $end) -or
            [double]::IsInfinity($start + $end) -or ($null -ne $midiNote -and $midiNote -gt 127)) {
        throw "Invalid time or MIDI pitch at label line $rowNumber."
    }
    if ($kind -eq 'extra') { $extra++; continue }
    if ($kind -eq 'repeat') { $repeated++; continue }
    if ($index -lt 1 -or $index -gt $score.Count -or $notesByIndex.ContainsKey($index)) {
        throw "Invalid or repeated score index at label line $rowNumber."
    }
    $notesByIndex[$index] = @{ start = $start; end = $end; pitch = $midiNote }
}

$origin = if ([double]::IsNaN($TimeZeroMs)) { 'First matched onset' } else { 'Provided music time zero' }
if ([double]::IsInfinity($TimeZeroMs)) { throw 'TimeZeroMs must be finite.' }
$scoreStart = [long]$score[0].sourceStartMs
$observedByScore = [Collections.Generic.List[object]]::new()
$missing = [Collections.Generic.List[int]]::new()
$wrongPitch = 0
$unknownPitch = 0
$previousStart = -1
foreach ($row in $score) {
    $index = [int]$row.noteIndex
    if (!$notesByIndex.ContainsKey($index)) {
        $missing.Add($index)
        continue
    }
    $expectedStart = ([long]$row.sourceStartMs - $scoreStart) / $Speed
    $expectedHold = [long]$row.sourceDurationMs / $Speed
    $expectedPitch = [int]$row.midiNote
    $note = $notesByIndex[$index]
    if ($note.start -le $previousStart) { throw "Expected onset order was reversed or duplicated at index $index." }
    $previousStart = $note.start
    if ([double]::IsNaN($TimeZeroMs)) { $TimeZeroMs = $note.start - $expectedStart }
    if ($null -eq $note.pitch) { $unknownPitch++ }
    elseif ($note.pitch -ne $expectedPitch) { $wrongPitch++ }
    $observedByScore.Add([pscustomobject]@{
        noteIndex = $index
        expectedMidiNote = $expectedPitch
        observedMidiNote = $note.pitch
        expectedStartMs = $expectedStart
        observedStartMs = $note.start - $TimeZeroMs
        onsetErrorMs = $note.start - $TimeZeroMs - $expectedStart
        expectedDurationMs = $expectedHold
        observedDurationMs = $note.end - $note.start
        durationErrorMs = $note.end - $note.start - $expectedHold
    })
}
$onset = @($observedByScore | ForEach-Object { [Math]::Abs($_.onsetErrorMs) })
$duration = @($observedByScore | ForEach-Object { [Math]::Abs($_.durationErrorMs) })
$onsetStats = $onset | Measure-Object -Average -Maximum
$durationStats = $duration | Measure-Object -Average -Maximum

[pscustomobject]@{
    expectedSha256 = (Get-FileHash -LiteralPath $ExpectedCsv).Hash
    labelsSha256 = (Get-FileHash -LiteralPath $AudacityLabels).Hash
    speed = $Speed
    origin = if (![double]::IsNaN($TimeZeroMs)) { $origin }
    timeZeroMs = if (![double]::IsNaN($TimeZeroMs)) { $TimeZeroMs }
    expectedNotes = $score.Count
    annotatedNotes = $notesByIndex.Count + $extra + $repeated
    matchedNotes = $notesByIndex.Count
    missingIndices = $missing.ToArray()
    additionalNotes = $extra
    repeatedNotes = $repeated
    correctPitch = $notesByIndex.Count - $wrongPitch - $unknownPitch
    wrongPitch = $wrongPitch
    unknownPitch = $unknownPitch
    meanAbsoluteOnsetErrorMs = if ($onset.Count) { $onsetStats.Average }
    maxAbsoluteOnsetErrorMs = if ($onset.Count) { $onsetStats.Maximum }
    p95AbsoluteOnsetErrorMs = Get-PercentileMs $onset 0.95
    p99AbsoluteOnsetErrorMs = Get-PercentileMs $onset 0.99
    meanAbsoluteDurationErrorMs = if ($duration.Count) { $durationStats.Average }
    maxAbsoluteDurationErrorMs = if ($duration.Count) { $durationStats.Maximum }
    cumulativeDriftMs = if ($observedByScore.Count -ge 2) {
        $observedByScore[-1].onsetErrorMs - $observedByScore[0].onsetErrorMs
    }
    notes = $observedByScore.ToArray()
} | ConvertTo-Json -Depth 4
