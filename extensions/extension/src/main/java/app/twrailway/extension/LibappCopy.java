package app.twrailway.extension;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Some patches change the app's compiled Dart code (libapp.so), which cannot read a setting.
 * The installed libapp.so has every such patch applied. When a setting differs from that, a copy
 * of libapp.so with the setting is written to the app's files directory, and the Flutter engine
 * is told to load the copy instead ({@code --aot-shared-library-name}, which only accepts
 * libraries in the app's internal storage). A setting takes effect the next time the app starts.
 *
 * <ul>
 *   <li>Expand search history: the number of searches shown in the search history.</li>
 *   <li>Hide upgrade button: whether the subscribe button of the search pages is shown.</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class LibappCopy {
    private static final String AOT_LIBRARY_FLAG = "--aot-shared-library-name=";
    private static final String DIRECTORY = "twrailway_patches";
    private static final String LIBRARY = "libapp.so";

    private static final int NOP = 0xd503201f;

    // Search history limit, arm64: x0 = (isPremium ? 0x22 : 0) + 6, the Smi of 20 or 3.
    // csetm x0, eq | mov x0, #0x22 (patched), then mov x16, #0x22; and x0, x0, x16; add x0, x0, #6.
    private static final int CSETM_X0_EQ = 0xda9f13e0;
    private static final int MOV_X0_0x22 = 0xd2800440;
    private static final int[] LIMIT_TAIL = {0xd2800450, 0x8a100000, 0x91001800};
    private static final int MOVZ_X0 = 0xd2800000;

    // Upgrade button: `if (iap.isPurchased.value) return const SizedBox();` where the patch
    // replaces the `tbnz` (branch to the button when false) with a nop. See HideUpgradeButtonPatch.
    private static final int[] UPGRADE_BUTTON_HEAD = {0xb8417001, 0x8b1c8021, 0xb841b020, 0x8b1c8000, 0xaa0003e1};
    private static final int TBNZ_W0_4_BUTTON = 0x372000a0;
    private static final int[] UPGRADE_BUTTON_TAIL = {0xf97ba360, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0};
    private static final int UPGRADE_BUTTON_LENGTH = 4 * (UPGRADE_BUTTON_HEAD.length + 2 + UPGRADE_BUTTON_TAIL.length);

    private LibappCopy() {
    }

    /**
     * Called at the start of FlutterLoader.ensureInitializationComplete with the engine's
     * arguments, before libapp.so is loaded.
     */
    public static String[] flutterShellArgs(Context context, String[] args) {
        try {
            if (context == null) return args;
            Context appContext = context.getApplicationContext();
            File directory = new File(appContext.getFilesDir(), DIRECTORY);

            Set<String> applied = PatchSettings.appliedPatches();

            // -1: leave as installed, 0: restore the app's own behavior, else the number to show.
            int searchHistoryLimit = -1;
            if (applied.contains(PatchSettings.EXPAND_SEARCH_HISTORY)) {
                boolean expand = PatchSettings.expandSearchHistory(appContext);
                int limit = PatchSettings.searchHistoryLimit(appContext);
                if (!expand) searchHistoryLimit = 0;
                else if (limit != PatchSettings.SEARCH_HISTORY_MAX) searchHistoryLimit = limit;
            }
            boolean showUpgradeButton = applied.contains(PatchSettings.HIDE_UPGRADE_BUTTON)
                    && !PatchSettings.hideUpgradeButton(appContext);

            if (searchHistoryLimit < 0 && !showUpgradeButton) {
                // The installed libapp.so already matches the settings.
                deleteCopies(directory, null);
                return args;
            }

            String name = "libapp"
                    + (searchHistoryLimit < 0 ? "" : searchHistoryLimit == 0 ? "-history-original" : "-history-" + searchHistoryLimit)
                    + (showUpgradeButton ? "-upgrade-button" : "")
                    + ".so";
            File copy = new File(directory, name);
            File stamp = new File(directory, name + ".source");
            String source = sourceIdentity(appContext);
            if (source == null) return args;

            if (!copy.isFile() || !source.equals(readText(stamp))) {
                deleteCopies(directory, null);
                byte[] library = readLibrary(appContext);
                if (library == null) return args;
                if (searchHistoryLimit >= 0 && !setSearchHistoryLimit(library, searchHistoryLimit)) {
                    Log.w(PatchSettings.TAG, "Could not find the search history limit in libapp.so");
                    return args;
                }
                if (showUpgradeButton && !restoreUpgradeButton(library)) {
                    Log.w(PatchSettings.TAG, "Could not find the upgrade button in libapp.so");
                    return args;
                }
                if (!directory.isDirectory() && !directory.mkdirs()) return args;
                File temporary = new File(directory, name + ".tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(library);
                    output.getFD().sync();
                }
                if (!temporary.renameTo(copy)) return args;
                writeText(stamp, source);
            }
            deleteCopies(directory, name);

            Log.i(PatchSettings.TAG, "Loading " + copy + " for the patch settings");
            String[] result = new String[(args == null ? 0 : args.length) + 1];
            result[0] = AOT_LIBRARY_FLAG + copy.getAbsolutePath();
            if (args != null) System.arraycopy(args, 0, result, 1, args.length);
            return result;
        } catch (Throwable t) {
            Log.e(PatchSettings.TAG, "Could not apply the patch settings to libapp.so", t);
            return args;
        }
    }

    /**
     * Sets the search history limit in the arm64 libapp.so in {@code library}, or restores the
     * original instruction when {@code limit} is 0.
     */
    private static boolean setSearchHistoryLimit(byte[] library, int limit) {
        int found = -1;
        for (int offset = 0; offset + 16 <= library.length; offset += 4) {
            int first = readInt(library, offset);
            if ((first == CSETM_X0_EQ || first == MOV_X0_0x22)
                    && readInt(library, offset + 4) == LIMIT_TAIL[0]
                    && readInt(library, offset + 8) == LIMIT_TAIL[1]
                    && readInt(library, offset + 12) == LIMIT_TAIL[2]) {
                if (found != -1) return false;
                found = offset;
            }
        }
        if (found == -1) return false;

        if (limit <= 0) {
            writeInt(library, found, CSETM_X0_EQ);
        } else {
            // mov x0, #Smi(limit); nop; nop; nop
            writeInt(library, found, MOVZ_X0 | ((limit << 1) << 5));
            writeInt(library, found + 4, NOP);
            writeInt(library, found + 8, NOP);
            writeInt(library, found + 12, NOP);
        }
        return true;
    }

    /** Puts back the branch to the upgrade button of the search pages. */
    private static boolean restoreUpgradeButton(byte[] library) {
        List<Integer> found = new ArrayList<>();
        for (int offset = 0; offset + UPGRADE_BUTTON_LENGTH <= library.length; offset += 4) {
            if (matches(library, offset, UPGRADE_BUTTON_HEAD)
                    && (readInt(library, offset + 20) >>> 26) == 0x25 // bl
                    && readInt(library, offset + 24) == NOP
                    && matches(library, offset + 28, UPGRADE_BUTTON_TAIL)) {
                found.add(offset);
            }
        }
        if (found.isEmpty() || found.size() > 2) return false;
        for (int offset : found) writeInt(library, offset + 24, TBNZ_W0_4_BUTTON);
        return true;
    }

    private static boolean matches(byte[] library, int offset, int[] words) {
        for (int i = 0; i < words.length; i++) {
            if (readInt(library, offset + 4 * i) != words[i]) return false;
        }
        return true;
    }

    /** Identifies the installed libapp.so, so a copy is made again after the app changes. */
    private static String sourceIdentity(Context context) {
        ApplicationInfo info = context.getApplicationInfo();
        File library = new File(info.nativeLibraryDir, LIBRARY);
        File file = library.isFile() ? library : new File(info.sourceDir);
        if (!file.isFile()) return null;
        return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
    }

    private static byte[] readLibrary(Context context) throws IOException {
        ApplicationInfo info = context.getApplicationInfo();
        File library = new File(info.nativeLibraryDir, LIBRARY);
        if (library.isFile()) {
            try (InputStream input = new FileInputStream(library)) {
                return readAll(input);
            }
        }

        // Not extracted (extractNativeLibs="false"): read it from the APK.
        List<String> apks = new ArrayList<>();
        apks.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) apks.add(split);
        }
        for (String apk : apks) {
            try (ZipFile zip = new ZipFile(apk)) {
                for (String abi : Build.SUPPORTED_ABIS) {
                    ZipEntry entry = zip.getEntry("lib/" + abi + "/" + LIBRARY);
                    if (entry == null) continue;
                    try (InputStream input = zip.getInputStream(entry)) {
                        return readAll(input);
                    }
                }
            }
        }
        return null;
    }

    private static void deleteCopies(File directory, String keep) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (keep != null && (name.equals(keep) || name.equals(keep + ".source"))) continue;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024 * 1024);
        byte[] buffer = new byte[256 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String readText(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | (bytes[offset + 1] & 0xff) << 8
                | (bytes[offset + 2] & 0xff) << 16
                | (bytes[offset + 3] & 0xff) << 24;
    }

    private static void writeInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
        bytes[offset + 2] = (byte) (value >> 16);
        bytes[offset + 3] = (byte) (value >> 24);
    }
}
