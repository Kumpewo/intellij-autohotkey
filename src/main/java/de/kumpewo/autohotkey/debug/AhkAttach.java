package de.kumpewo.autohotkey.debug;

import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.ptr.IntByReference;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Attaching the debugger to a running script, as described in the AutoHotkey help ({@code Scripts.htm},
 * "Interactive Debugging"): every script has a hidden main window of the class {@code AutoHotkey} with the title
 * {@code path - AutoHotkey v2.0.31}; the window message {@code AHK_ATTACH_DEBUGGER} (registered message) makes the
 * script connect to the debugger, wParam = IP address as {@code inet_addr} returns it (127.0.0.1 = 0x0100007F),
 * lParam = port. The script then stops and waits for commands (observed with 2.0.31). Windows only.
 */
public final class AhkAttach {

    /** 127.0.0.1 in network byte order, as wParam. */
    private static final long LOOPBACK = 0x0100007FL;

    /** Window title suffix of AutoHotkey v2 scripts. */
    private static final String V2_TITLE = " - AutoHotkey v2";

    /** A running AutoHotkey v2 script. */
    public record Script(@NotNull WinDef.HWND window, @NotNull String path, int processId) {
    }

    private AhkAttach() {
    }

    /** The running AutoHotkey v2 scripts (main windows), empty on other systems. */
    public static @NotNull List<Script> scripts() {
        List<Script> scripts = new ArrayList<>();
        if (!SystemInfo.isWindows) return scripts;
        User32 user32 = User32.INSTANCE;
        user32.EnumWindows((window, data) -> {
            char[] buffer = new char[1024];
            int length = user32.GetClassName(window, buffer, buffer.length);
            if ("AutoHotkey".equals(new String(buffer, 0, length))) {
                length = user32.GetWindowText(window, buffer, buffer.length);
                String title = new String(buffer, 0, length);
                int end = title.lastIndexOf(V2_TITLE);
                if (end > 0) {
                    IntByReference pid = new IntByReference();
                    user32.GetWindowThreadProcessId(window, pid);
                    scripts.add(new Script(window, title.substring(0, end), pid.getValue()));
                }
            }
            return true;
        }, null);
        return scripts;
    }

    /** Asks the script to connect to the debugger on 127.0.0.1:port. */
    public static void requestConnection(@NotNull Script script, int port) {
        User32 user32 = User32.INSTANCE;
        int message = user32.RegisterWindowMessage("AHK_ATTACH_DEBUGGER");
        user32.PostMessage(script.window(), message, new WinDef.WPARAM(LOOPBACK), new WinDef.LPARAM(port));
    }
}
