param([string]$Version = '0.3.0', [string]$Python = '')
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid companion version' }
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPython = if ($Python) { [IO.Path]::GetFullPath($Python) } else { Join-Path $taskRoot '.local/companion-venv/Scripts/python.exe' }
$taskAssets = Join-Path $taskRoot ('.local/companion-assets-' + [guid]::NewGuid().ToString('N') + '/desktop')
New-Item -ItemType Directory -Force -Path $taskAssets | Out-Null
foreach ($taskName in @('runtime_worker.mjs','coordination.mjs','activation.mjs','observation.mjs','history.mjs','transport.mjs','adapter.mjs')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot "desktop/$taskName") -Destination (Join-Path $taskAssets $taskName)
}
$env:PYINSTALLER_CONFIG_DIR = Join-Path $taskRoot '.local/pyinstaller-cache'
$env:PYTHONPATH = $taskRoot
$taskDist = Join-Path $taskRoot ".local/distribution/companion-$Version"
$taskProgram = Join-Path $taskDist 'DeviceBridge-Companion'
if (Test-Path -LiteralPath $taskProgram) {
    if ((Get-Item -LiteralPath $taskProgram).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Linked package output is not replaceable' }
    $taskResolved = (Resolve-Path -LiteralPath $taskProgram).Path
    if ($taskResolved -ne [IO.Path]::GetFullPath($taskProgram)) { throw 'Unexpected package path' }
    $taskContents = Get-ChildItem -LiteralPath $taskResolved -Recurse -Force
    if ($taskContents | Where-Object { $_.Name -eq 'devicebridge-data' -or $_.Extension -in @('.dpapi','.sqlite') -or $_.Name -eq 'startup.json' -or $_.Attributes -band [IO.FileAttributes]::ReparsePoint }) { throw 'Runtime data or links found; choose a new version/output instead of replacing it' }
}
& $taskPython -m PyInstaller --name DeviceBridge-Companion --windowed --onedir --noupx --noconfirm --collect-submodules websockets --paths $taskRoot --add-data "${taskAssets}:desktop" --distpath $taskDist --workpath (Join-Path $taskRoot '.local/companion-build') --specpath (Join-Path $taskRoot '.local/companion-build') (Join-Path $taskRoot 'tools/companion_entry.py')
if ($LASTEXITCODE -ne 0) { throw 'Companion build failed' }
