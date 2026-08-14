$ErrorActionPreference = "Stop"

$path = (Get-ChildItem -LiteralPath "D:\Programming\Thesis\LLMLogAnalyzer\documents" -Filter "*.docx" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1).FullName
$word = New-Object -ComObject Word.Application
$word.Visible = $false
$word.DisplayAlerts = 0
$doc = $null

try {
    $doc = $word.Documents.Open($path, $false, $false)
    Write-Output "opened"

    if ($doc.TablesOfContents.Count -gt 0) {
        $doc.TablesOfContents.Item(1).Update()
        Write-Output "toc-updated"
    }

    $customListCount = 0
    foreach ($field in @($doc.Fields)) {
        $code = $field.Code.Text
        if ($code -match 'TOC\s+\\f\s+[TF]') {
            $field.Update()
            $customListCount++
            Write-Output "custom-list-$customListCount-updated"
        }
    }

    for ($i = 1; $i -le $doc.TablesOfFigures.Count; $i++) {
        $doc.TablesOfFigures.Item($i).Update()
        Write-Output "tof-$i-updated"
    }

    $doc.Repaginate()
    $pages = $doc.ComputeStatistics(2)
    $footnotes = $doc.Footnotes.Count
    $tables = $doc.Tables.Count
    $fields = $doc.Fields.Count
    $doc.Save()
    Write-Output "saved pages=$pages footnotes=$footnotes tables=$tables fields=$fields"
}
finally {
    if ($null -ne $doc) {
        $doc.Close($false)
        [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($doc)
    }
    $word.Quit()
    [void][System.Runtime.InteropServices.Marshal]::FinalReleaseComObject($word)
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
