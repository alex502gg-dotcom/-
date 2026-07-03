package ru.playerreports.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

public final class PlayerReportsExpansion extends PlaceholderExpansion {
    private static final String PLAYER_REPORTS_PLUGIN_NAME = "PlayerReports";
    private static final long CACHE_MILLIS = 15000L;

    private long nextUpdate;
    private int cachedReports;
    private String lastStatus = "not-loaded";

    @Override
    public String getIdentifier() {
        return "playerreports";
    }

    @Override
    public String getAuthor() {
        return "Codex";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return Bukkit.getPluginManager().getPlugin(PLAYER_REPORTS_PLUGIN_NAME) != null;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) {
            return null;
        }

        String key = params.toLowerCase(Locale.ROOT);

        if (key.equals("reports") || key.equals("count") || key.equals("total") || key.equals("open")) {
            return String.valueOf(getReportsCount());
        }

        if (key.equals("status")) {
            getReportsCount();
            return lastStatus;
        }

        return null;
    }

    private int getReportsCount() {
        long now = System.currentTimeMillis();
        if (now < nextUpdate) {
            return cachedReports;
        }

        nextUpdate = now + CACHE_MILLIS;

        Plugin playerReports = Bukkit.getPluginManager().getPlugin(PLAYER_REPORTS_PLUGIN_NAME);
        if (playerReports == null) {
            lastStatus = "PlayerReports not found";
            cachedReports = 0;
            return cachedReports;
        }

        if (!playerReports.isEnabled()) {
            lastStatus = "PlayerReports disabled";
            cachedReports = 0;
            return cachedReports;
        }

        Integer apiCount = findReportsCount(playerReports, 0);
        if (apiCount != null) {
            lastStatus = "OK reflection";
            cachedReports = Math.max(0, apiCount);
            return cachedReports;
        }

        cachedReports = countReportFiles(playerReports.getDataFolder());
        lastStatus = "OK file fallback";
        return cachedReports;
    }

    private Integer findReportsCount(Object object, int depth) {
        if (object == null || depth > 4) {
            return null;
        }

        for (Method method : object.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }

            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!isReportsRelated(name)) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object result = method.invoke(object);
                Integer count = countValue(result);
                if (count != null) {
                    return count;
                }

                Integer nested = findReportsCount(result, depth + 1);
                if (nested != null) {
                    return nested;
                }
            } catch (Throwable ignored) {
            }
        }

        for (Field field : object.getClass().getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            if (!isReportsRelated(name)) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object result = field.get(object);
                Integer count = countValue(result);
                if (count != null) {
                    return count;
                }

                Integer nested = findReportsCount(result, depth + 1);
                if (nested != null) {
                    return nested;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private boolean isReportsRelated(String name) {
        return name.contains("report")
                || name.contains("complaint")
                || name.contains("storage")
                || name.contains("database")
                || name.contains("manager");
    }

    private Integer countValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).size();
        }
        if (value instanceof Map<?, ?>) {
            return ((Map<?, ?>) value).size();
        }
        if (value.getClass().isArray()) {
            return Array.getLength(value);
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private int countReportFiles(File playerReportsFolder) {
        int total = 0;
        total += countFiles(new File(playerReportsFolder, "reports"));
        total += countFiles(new File(playerReportsFolder, "data"));
        total += countFiles(new File(playerReportsFolder, "database"));
        return total;
    }

    private int countFiles(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }

        if (file.isFile()) {
            String name = file.getName().toLowerCase(Locale.ROOT);
            return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json") ? 1 : 0;
        }

        File[] files = file.listFiles();
        if (files == null) {
            return 0;
        }

        int total = 0;
        for (File child : files) {
            total += countFiles(child);
        }
        return total;
    }
}
