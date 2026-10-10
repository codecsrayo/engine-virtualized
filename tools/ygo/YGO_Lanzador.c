// Launcher for Yu-Gi-Oh! Power of Chaos: Joey the Passion.
// 1) Writes HKLM\Software\KONAMI\Yu-Gi-Oh! Power Of Chaos\system\InstallDirJ = <this folder>\
//    (32-bit exe, so it lands in Wow6432Node like the original installer did).
//    Without this key the game relaunches itself and frontends like GameHub think it exited.
// 2) Starts joey_pc.exe -Lspa (plus any extra args) and waits until no joey_pc.exe is left.
#include <windows.h>
#include <tlhelp32.h>
#include <string.h>

// Returns the pid of a running joey_pc.exe, or 0 if none.
static DWORD game_running(void) {
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32 pe; pe.dwSize = sizeof(pe);
    DWORD found = 0;
    if (Process32First(snap, &pe)) {
        do { if (lstrcmpiA(pe.szExeFile, "joey_pc.exe") == 0) { found = pe.th32ProcessID; break; } }
        while (Process32Next(snap, &pe));
    }
    CloseHandle(snap);
    return found;
}

// Appends a timestamped line to <dir>\lanzador.log (for diagnosing exits under Wine).
static void log_line(const char *dir, const char *fmt, DWORD v) {
    char path[MAX_PATH + 32], msg[256], line[320];
    wsprintfA(path, "%slanzador.log", dir);
    wsprintfA(msg, fmt, v);
    SYSTEMTIME t; GetLocalTime(&t);
    int n = wsprintfA(line, "%02d:%02d:%02d.%03d %s\r\n", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds, msg);
    HANDLE h = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ, NULL, OPEN_ALWAYS, 0, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    DWORD w; WriteFile(h, line, (DWORD)n, &w, NULL); CloseHandle(h);
}

int WINAPI WinMain(HINSTANCE hi, HINSTANCE hp, LPSTR args, int show) {
    char dir[MAX_PATH];
    DWORD n = GetModuleFileNameA(NULL, dir, MAX_PATH);
    char *slash = strrchr(dir, '\\');
    if (!n || !slash) return 1;
    slash[1] = '\0';  // keep trailing backslash, as the installer wrote it

    HKEY k;
    if (RegCreateKeyExA(HKEY_LOCAL_MACHINE, "SOFTWARE\\KONAMI\\Yu-Gi-Oh! Power Of Chaos\\system",
                        0, NULL, 0, KEY_SET_VALUE, NULL, &k, NULL) == ERROR_SUCCESS) {
        RegSetValueExA(k, "InstallDirJ", 0, REG_SZ, (const BYTE *)dir, (DWORD)strlen(dir) + 1);
        RegCloseKey(k);
    }

    char exe[MAX_PATH + 16], cmd[MAX_PATH + 1024];
    lstrcpyA(exe, dir); lstrcatA(exe, "joey_pc.exe");
    wsprintfA(cmd, "\"%s\" -Lspa %s", exe, args ? args : "");

    log_line(dir, "starting joey_pc.exe (pid of launcher %lu)", GetCurrentProcessId());
    // Play mode: silence Wine debug output so it doesn't cost performance.
    SetEnvironmentVariableA("WINEDEBUG", "-all");

    // ARM64 Wine (GameHub Proton): run the 32-bit game on Box64 (wowbox64.dll) instead of FEX,
    // which makes joey_pc.exe exit with 0x80000101. Ignored by x86_64 Wine (Winlator).
    SetEnvironmentVariableA("HODLL", "wowbox64.dll");

    // Software (GDI) rendering: GL/Vulkan through the Adreno 810 driver gives a black screen.
    // Winlator-based frontends pass wined3d options in WINE_D3D_CONFIG (e.g. "renderer=gl"),
    // which overrides the registry, so rewrite renderer= there too and keep the other options.
    char d3dcfg[1024], newcfg[1100];
    newcfg[0] = '\0';
    DWORD got = GetEnvironmentVariableA("WINE_D3D_CONFIG", d3dcfg, sizeof(d3dcfg));
    if (got && got < sizeof(d3dcfg)) {
        char *tok = strtok(d3dcfg, ",");
        while (tok) {
            if (_strnicmp(tok, "renderer=", 9) != 0) {
                lstrcatA(newcfg, tok);
                lstrcatA(newcfg, ",");
            }
            tok = strtok(NULL, ",");
        }
    }
    lstrcatA(newcfg, "renderer=gdi");
    SetEnvironmentVariableA("WINE_D3D_CONFIG", newcfg);
    log_line(dir, "WINE_D3D_CONFIG set (renderer=gdi)", 0);

    HKEY d3d;
    if (RegCreateKeyExA(HKEY_CURRENT_USER, "Software\\Wine\\Direct3D", 0, NULL, 0, KEY_SET_VALUE,
                        NULL, &d3d, NULL) == ERROR_SUCCESS) {
        RegSetValueExA(d3d, "renderer", 0, REG_SZ, (const BYTE *)"gdi", 4);
        RegCloseKey(d3d);
    }

    STARTUPINFOA si; PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si)); si.cb = sizeof(si);
    if (!CreateProcessA(exe, cmd, NULL, NULL, FALSE, 0, NULL, dir, &si, &pi)) {
        MessageBoxA(NULL, "No se pudo iniciar joey_pc.exe", "YGO Lanzador", MB_ICONERROR);
        return 1;
    }
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 0;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    log_line(dir, "joey_pc.exe exited, code=0x%08lX", code);

    // The game may relaunch itself; keep running while any copy is alive.
    Sleep(3000);
    DWORD pid;
    while ((pid = game_running()) != 0) {
        HANDLE h = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
        if (!h) { Sleep(1000); continue; }
        log_line(dir, "waiting on relaunched joey_pc.exe pid %lu", pid);
        WaitForSingleObject(h, INFINITE);
        GetExitCodeProcess(h, &code);
        CloseHandle(h);
        log_line(dir, "relaunched joey_pc.exe exited, code=0x%08lX", code);
    }
    log_line(dir, "no joey_pc.exe left, launcher exiting", 0);
    return 0;
}
