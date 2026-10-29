# 按扫描码向前台窗口发送按键，用于自动化驱动开发客户端（Minecraft 窗口需在前台）。
# GLFW 按扫描码识别按键，普通自动化工具发送的 scancode 为 0 的按键会被游戏忽略。
# 用法：pwsh -NoProfile -File scripts/sendkey.ps1 k
#       pwsh -NoProfile -File scripts/sendkey.ps1 slash c r e e p e r enter
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory, Position = 0, ValueFromRemainingArguments)][string[]]$Keys,
    [int]$HoldMs = 60,
    [int]$GapMs = 120
)

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class ScanKeys {
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }
    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);
    const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x2, KEYEVENTF_SCANCODE = 0x8;
    public static void Send(ushort scan, bool up) {
        var input = new INPUT { type = INPUT_KEYBOARD };
        input.u.ki = new KEYBDINPUT { wScan = scan, dwFlags = KEYEVENTF_SCANCODE | (up ? KEYEVENTF_KEYUP : 0) };
        if (SendInput(1, new[] { input }, Marshal.SizeOf(typeof(INPUT))) != 1)
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
    }
}
"@

$scan = @{
    esc = 0x01; enter = 0x1C; slash = 0x35; space = 0x39
    q = 0x10; w = 0x11; e = 0x12; r = 0x13; t = 0x14; y = 0x15; u = 0x16; i = 0x17; o = 0x18; p = 0x19
    a = 0x1E; s = 0x1F; d = 0x20; f = 0x21; g = 0x22; h = 0x23; j = 0x24; k = 0x25; l = 0x26
    z = 0x2C; x = 0x2D; c = 0x2E; v = 0x2F; b = 0x30; n = 0x31; m = 0x32
}

foreach ($key in $Keys) {
    $code = $scan[$key.ToLowerInvariant()]
    if ($null -eq $code) { throw "未知按键：$key" }
    [ScanKeys]::Send([uint16]$code, $false)
    Start-Sleep -Milliseconds $HoldMs
    [ScanKeys]::Send([uint16]$code, $true)
    Start-Sleep -Milliseconds $GapMs
}
