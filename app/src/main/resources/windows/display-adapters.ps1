$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
try {
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class DisplayDevices {
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    static extern int CM_Get_Device_ID_List_SizeW(out int length, string filter, int flags);
    [DllImport("cfgmgr32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    static extern int CM_Get_Device_ID_ListW(string filter,
        [Out, MarshalAs(UnmanagedType.LPArray, ArraySubType = UnmanagedType.U2)] char[] buffer, int length, int flags);

    public static string[] Present() {
        const string display = "{4d36e968-e325-11ce-bfc1-08002be10318}";
        const int flags = 0x200 | 0x100; // CM_GETIDLIST_FILTER_CLASS | CM_GETIDLIST_FILTER_PRESENT
        for (int attempt = 0; attempt < 3; attempt++) {
            int length;
            int status = CM_Get_Device_ID_List_SizeW(out length, display, flags);
            if (status != 0) throw new InvalidOperationException("Display inventory size: " + status);
            char[] buffer = new char[length];
            status = CM_Get_Device_ID_ListW(display, buffer, length, flags);
            if (status == 0x1A) continue; // CR_BUFFER_SMALL after a hotplug
            if (status != 0) throw new InvalidOperationException("Display inventory: " + status);
            return new string(buffer).Split(new char[] {'\0'}, StringSplitOptions.RemoveEmptyEntries);
        }
        throw new InvalidOperationException("Display inventory changed during enumeration");
    }
}
'@
    $machine = [Microsoft.Win32.Registry]::LocalMachine
    foreach ($instance in [DisplayDevices]::Present()) {
        if ($instance -notmatch '^PCI\\VEN_([0-9A-F]{4})&DEV_([0-9A-F]{4})&SUBSYS_([0-9A-F]{8})') { continue }
        $model = '{0:X}&{1:X}&{2:X}' -f [Convert]::ToUInt32($Matches[1],16), [Convert]::ToUInt32($Matches[2],16), [Convert]::ToUInt32($Matches[3],16)
        $device = $machine.OpenSubKey("SYSTEM\CurrentControlSet\Enum\$instance")
        if (!$device) { continue }
        try { $driver = $device.GetValue('Driver') } finally { $device.Dispose() }
        if (!$driver) { continue }
        $class = $machine.OpenSubKey("SYSTEM\CurrentControlSet\Control\Class\$driver")
        if ($class) {
            try { [Console]::WriteLine($model + [char]9 + [string]$class.GetValue('DriverDesc')) } finally { $class.Dispose() }
        }
    }
} catch { [Console]::WriteLine($_.Exception.Message); exit 1 }
