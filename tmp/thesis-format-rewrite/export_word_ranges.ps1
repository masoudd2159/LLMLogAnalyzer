param(
    [Parameter(Mandatory=$true)][string]$InputPath,
    [Parameter(Mandatory=$true)][string]$OutputDir
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$word = $null
$doc = $null
try {
    $word = New-Object -ComObject Word.Application
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $doc = $word.Documents.Open($InputPath, $false, $true)
    $doc.Repaginate()
    $pageCount = $doc.ComputeStatistics(2)
    for ($start = 1; $start -le $pageCount; $start += 10) {
        $end = [Math]::Min($start + 9, $pageCount)
        $out = Join-Path $OutputDir ("part-{0:D2}-{1:D2}.pdf" -f $start, $end)
        $doc.ExportAsFixedFormat(
            $out,
            17,
            $false,
            0,
            3,
            $start,
            $end,
            0,
            $true,
            $true,
            0,
            $true,
            $true,
            $false
        )
        Write-Output $out
    }
    Write-Output ("pageCount={0}" -f $pageCount)
}
finally {
    if ($doc -ne $null) { $doc.Close($false) }
    if ($word -ne $null) { $word.Quit() }
    if ($doc -ne $null) { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($doc) | Out-Null }
    if ($word -ne $null) { [System.Runtime.InteropServices.Marshal]::ReleaseComObject($word) | Out-Null }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
