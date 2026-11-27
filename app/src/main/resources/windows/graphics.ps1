$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$gpuField = '^\s*(GpuPreference|SpecificAdapter)\s*='
function GpuFields($value) {
    (($value -split '(?<=;)') | Where-Object { $_ -match $gpuField }) -join ''
}
function OtherFields($value) {
    (($value -split '(?<=;)') | Where-Object { $_ -notmatch $gpuField }) -join ''
}
function WithGpu($value, $gpu) {
    $other = OtherFields $value
    if ($gpu -and $other -and !$other.EndsWith(';')) { $other += ';' }
    return $other + $gpu
}
function SaveState($state) {
    [void][IO.Directory]::CreateDirectory($env:MCWE_GRAPHICS_CACHE)
    $temp = "$journal.tmp"
    try {
        [IO.File]::WriteAllText($temp, ($state | ConvertTo-Json -Compress -Depth 4))
        if ([IO.File]::Exists($journal)) { [IO.File]::Replace($temp, $journal, [NullString]::Value) }
        else { [IO.File]::Move($temp, $journal) }
    } finally { [IO.File]::Delete($temp) }
}
function LiveOwners($owners) {
    foreach ($owner in $owners) {
        if ($owner.token -eq $env:MCWE_GRAPHICS_SESSION) { continue }
        $process = Get-Process -Id $owner.processId -ErrorAction SilentlyContinue
        if ($process) {
            try {
                $start = $process.StartTime
                if ($start -and [string]$start.ToUniversalTime().Ticks -eq $owner.started) { $owner }
            } finally { $process.Dispose() }
        }
    }
}
function WritePreference($value) {
    if ($null -eq $value) { if ($registry) { $registry.DeleteValue($path, $false) } }
    else { $registry.SetValue($path, [string]$value, 'String') }
}
try {
    $path = $env:MCWE_GRAPHICS_EXE
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value + '|' + $path.ToUpperInvariant()
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($identity))
    $key = 'MCWE-GPU-' + [BitConverter]::ToString($hash).Replace('-', '')
    $journal = [IO.Path]::Combine($env:MCWE_GRAPHICS_CACHE, "$key.json")
    $mutex = [Threading.Mutex]::new($false, "Global\$key")
    try { $locked = $mutex.WaitOne(5000) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'GPU preference is busy' }
    $action = $env:MCWE_GRAPHICS_ACTION
    $subkey = 'Software\Microsoft\DirectX\UserGpuPreferences'
    $registry = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey($subkey, $true)
    $old = if ($registry) { $registry.GetValue($path, $null, 'DoNotExpandEnvironmentNames') }
    $state = if ([IO.File]::Exists($journal)) { [IO.File]::ReadAllText($journal) | ConvertFrom-Json }
    if ($action -eq 'release' -and (!$state -or $env:MCWE_GRAPHICS_SESSION -notin $state.owners.token)) { 'unchanged'; exit 0 }
    $foreignType = $null -ne $old -and $registry.GetValueKind($path) -ne 'String'
    if ($foreignType -and $action -ne 'release') { throw 'Unexpected GPU preference value type' }

    if ($action -eq 'release') {
        $state.owners = @(LiveOwners $state.owners)
        if ($state.owners.Count) { SaveState $state; 'unchanged'; exit 0 }
        if ($foreignType) { [IO.File]::Delete($journal); 'unchanged'; exit 0 }
        # Respect edits made in Windows while MCWE was open, including unrelated fields.
        if ($old -ceq $state.written) { $next = $state.before }
        elseif ((GpuFields $old) -ceq (GpuFields $state.written)) {
            $next = WithGpu $old (GpuFields $state.before)
            if (!$next -and $null -eq $state.before) { $next = $null }
        } else { $next = $old }
    } elseif ($action -eq 'reset') {
        # A pending explicit clear from an older build.
        $next = OtherFields $old
        if (!$next -or $next -notmatch '[^;\s]') { $next = $null }
    } else {
        $gpu = switch ($action) {
            'power' { 'GpuPreference=1;' }
            'performance' { 'GpuPreference=2;' }
            default {
                if ($_ -cnotmatch '^[0-9A-F]{1,4}&[0-9A-F]{1,4}&[0-9A-F]{1,8}$') { throw 'Unknown graphics preference' }
                "SpecificAdapter=$_;GpuPreference=1073741824;"
            }
        }
        if (!$registry) { $registry = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey($subkey) }
        $before = $old
        if ($state -and (GpuFields $old) -ceq (GpuFields $state.written)) {
            $before = $state.before
            if ($old -cne $state.written) {
                $before = WithGpu $old (GpuFields $before)
                if (!$before -and $null -eq $state.before) { $before = $null }
            }
        } elseif (!$state -and $env:MCWE_GRAPHICS_LEGACY -eq 'true' -and (GpuFields $old) -ceq $gpu) {
            $before = OtherFields $old
            if (!$before) { $before = $null }
        }
        $next = WithGpu $old $gpu
        $owners = @(LiveOwners $state.owners)
        $process = Get-Process -Id ([int]$env:MCWE_GRAPHICS_PID)
        $owners += @{ token=$env:MCWE_GRAPHICS_SESSION; processId=$process.Id; started=[string]$process.StartTime.ToUniversalTime().Ticks }
        # Persist the undo information before writing HKCU.
        SaveState @{ before=$before; written=$next; owners=$owners }
    }
    if ($old -cne $next) { WritePreference $next; 'changed' } else { 'unchanged' }
    if ($action -eq 'release') { [IO.File]::Delete($journal) }
} catch { [Console]::WriteLine($_.Exception.Message); exit 1 }
finally {
    if ($registry) { $registry.Dispose() }
    if ($locked) { $mutex.ReleaseMutex() }
    if ($mutex) { $mutex.Dispose() }
}
