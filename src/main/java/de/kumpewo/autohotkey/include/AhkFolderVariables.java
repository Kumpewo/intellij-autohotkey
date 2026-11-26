package de.kumpewo.autohotkey.include;

import com.intellij.openapi.util.SystemInfo;
import com.sun.jna.platform.win32.Guid;
import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in variables of AutoHotkey that do not depend on a running script and that Ahk2Exe also resolves:
 * {@code A_MyDocuments}, {@code A_AppData}, {@code A_AppDataCommon}, {@code A_Desktop}, {@code A_DesktopCommon},
 * {@code A_ProgramFiles}, {@code A_Programs}, {@code A_ProgramsCommon}, {@code A_StartMenu},
 * {@code A_StartMenuCommon}, {@code A_Startup}, {@code A_StartupCommon}, {@code A_Temp}, {@code A_UserName},
 * {@code A_ComputerName}, {@code A_WinDir}. Folders come from Windows like in AutoHotkey ({@code SHGetKnownFolderPath},
 * so a redirected "Documents" folder is found), with the environment as fallback. Used by {@code #Include}
 * ({@link AhkInclude}) and by {@code settings.ahk-v2-plugin.ini}.
 */
public final class AhkFolderVariables {

    /** The names of the variables as AutoHotkey writes them. */
    public static final List<String> NAMES = List.of("A_MyDocuments", "A_AppData", "A_AppDataCommon", "A_Desktop",
            "A_DesktopCommon", "A_ProgramFiles", "A_Programs", "A_ProgramsCommon", "A_StartMenu", "A_StartMenuCommon",
            "A_Startup", "A_StartupCommon", "A_Temp", "A_UserName", "A_ComputerName", "A_WinDir");

    private AhkFolderVariables() {
    }

    /** The values by lower-case name; a variable whose value cannot be found is missing. */
    public static @NotNull Map<String, String> values() {
        Map<String, String> values = new HashMap<>();
        folder(values, "a_mydocuments", KnownFolders.FOLDERID_Documents, null, "Documents");
        folder(values, "a_appdata", KnownFolders.FOLDERID_RoamingAppData, "APPDATA", null);
        folder(values, "a_appdatacommon", KnownFolders.FOLDERID_ProgramData, "ProgramData", null);
        folder(values, "a_desktop", KnownFolders.FOLDERID_Desktop, null, "Desktop");
        folder(values, "a_desktopcommon", KnownFolders.FOLDERID_PublicDesktop, null, null);
        folder(values, "a_programfiles", KnownFolders.FOLDERID_ProgramFiles, "ProgramFiles", null);
        folder(values, "a_programs", KnownFolders.FOLDERID_Programs, null, null);
        folder(values, "a_programscommon", KnownFolders.FOLDERID_CommonPrograms, null, null);
        folder(values, "a_startmenu", KnownFolders.FOLDERID_StartMenu, null, null);
        folder(values, "a_startmenucommon", KnownFolders.FOLDERID_CommonStartMenu, null, null);
        folder(values, "a_startup", KnownFolders.FOLDERID_Startup, null, null);
        folder(values, "a_startupcommon", KnownFolders.FOLDERID_CommonStartup, null, null);
        env(values, "a_temp", "TEMP");
        env(values, "a_username", "USERNAME");
        env(values, "a_computername", "COMPUTERNAME");
        env(values, "a_windir", "windir");
        return values;
    }

    /** {@code A_MyDocuments}, {@code null} if unknown. */
    public static @Nullable Path myDocuments() {
        Map<String, String> values = new HashMap<>();
        folder(values, "a_mydocuments", KnownFolders.FOLDERID_Documents, null, "Documents");
        String value = values.get("a_mydocuments");
        return value == null ? null : Path.of(value);
    }

    /** {@code A_Desktop}, {@code null} if unknown. */
    public static @Nullable Path desktop() {
        Map<String, String> values = new HashMap<>();
        folder(values, "a_desktop", KnownFolders.FOLDERID_Desktop, null, "Desktop");
        String value = values.get("a_desktop");
        return value == null ? null : Path.of(value);
    }

    /**
     * Puts a known Windows folder, otherwise the environment variable, otherwise {@code user.home\fallback}. Not
     * {@code FileSystemView.getDefaultDirectory()}: depending on the environment it fails in the shell query.
     */
    private static void folder(Map<String, String> values, String name, Guid.GUID id, @Nullable String variable,
                               @Nullable String fallback) {
        if (SystemInfo.isWindows) {
            try {
                String path = Shell32Util.getKnownFolderPath(id);
                if (path != null && !path.isEmpty()) {
                    values.put(name, path);
                    return;
                }
            } catch (Throwable ignored) {
                // JNA not available or folder unknown: fallback
            }
        }
        if (variable != null && env(values, name, variable)) return;
        String home = System.getProperty("user.home");
        if (fallback != null && home != null) values.put(name, Path.of(home, fallback).toString());
    }

    /** Puts the environment variable under {@code name} if it is set. */
    private static boolean env(Map<String, String> values, String name, String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isEmpty()) return false;
        values.put(name, value);
        return true;
    }
}
