$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskPython = Join-Path $taskRoot '.local/companion-venv/Scripts/python.exe'
$taskAssets = Join-Path $taskRoot '.local/companion-assets/desktop'
New-Item -ItemType Directory -Force -Path $taskAssets | Out-Null
foreach ($taskName in @('runtime_worker.mjs','coordination.mjs','activation.mjs','observation.mjs','history.mjs','transport.mjs','adapter.mjs')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot "desktop/$taskName") -Destination (Join-Path $taskAssets $taskName)
}
$env:PYINSTALLER_CONFIG_DIR = Join-Path $taskRoot '.local/pyinstaller-cache'
$env:PYTHONPATH = $taskRoot
& $taskPython -m PyInstaller --name DeviceBridge-Companion --windowed --onedir --noupx --noconfirm --paths $taskRoot --add-data "${taskAssets}:desktop" --distpath (Join-Path $taskRoot '.local/distribution') --workpath (Join-Path $taskRoot '.local/companion-build') --specpath (Join-Path $taskRoot '.local/companion-build') (Join-Path $taskRoot 'tools/companion_entry.py')
if ($LASTEXITCODE -ne 0) { throw 'Companion build failed' }
