package ru.playerreports.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PlayerReportsExpansion extends PlaceholderExpansion {
    private static final long CACHE_MILLIS = 15000L;
    private static final String[] PLUGIN_NAMES = {"PlayerReports", "PlayerReport"};

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
        return "1.1.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return findPlayerReportsPlugin() != null;
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

        Plugin playerReports = findPlayerReportsPlugin();
        if (playerReports == null) {
            lastStatus = "PlayerReports plugin not found";
            cachedReports = 0;
            return cachedReports;
        }

        if (!playerReports.isEnabled()) {
            lastStatus = playerReports.getName() + " disabled";
            cachedReports = 0;
            return cachedReports;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Integer apiCount = findReportsCount(playerReports, 0, visited);
        if (apiCount != null) {
            lastStatus = "OK reflection";
            cachedReports = Math.max(0, apiCount);
            return cachedReports;
        }

        cachedReports = countReportFiles(playerReports.getDataFolder());
        lastStatus = "OK file fallback";
        return cachedReports;
    }

    private Plugin findPlayerReportsPlugin() {
        for (String name : PLUGIN_NAMES) {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
            if (plugin != null) {
                return plugin;
            }
        }
        return null;
    }

    private Integer findReportsCount(Object object, int depth, Set<Object> visited) {
        if (object == null || depth > 5 || visited.contains(object)) {
            return null;
        }
        visited.add(object);

        Integer direct = countContainer(object);
        if (direct != null) {
            return direct;
        }

        for (Method method : object.getClass().getMethods()) {
            if (method.getParameterTypes().length != 0) {
                continue;
            }

            String name = method.getName().toLowerCase(Locale.ROOT);
            if (!isUsefulAccessor(name)) {
                continue;
            }

            try {
                method.setAccessible(true);
                Object result = method.invoke(object);

                Integer count = countContainer(result);
                if (count != null) {
                    return count;
                }

                if (isExplicitCountAccessor(name) && result instanceof Number) {
                    return ((Number) result).intValue();
                }

                Integer nested = findReportsCount(result, depth + 1, visited);
                if (nested != null) {
                    return nested;
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> type = object.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!isUsefulAccessor(name)) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object result = field.get(object);

                    Integer count = countContainer(result);
                    if (count != null) {
                        return count;
                    }

                    Integer nested = findReportsCount(result, depth + 1, visited);
                    if (nested != null) {
                        return nested;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }

        return null;
    }

    private boolean isUsefulAccessor(String name) {
        return name.contains("report")
                || name.contains("complaint")
                || name.contains("storage")
                || name.contains("database")
                || name.contains("manager")
                || name.contains("repository");
    }

    private boolean isExplicitCountAccessor(String name) {
        return isUsefulAccessor(name)
                && (name.contains("count") || name.contains("size") || name.contains("amount") || name.contains("total"));
    }

    private Integer countContainer(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof Collection<?>) {
            return countOpenValues((Collection<?>) value);
        }

        if (value instanceof Map<?, ?>) {
            return countOpenValues(((Map<?, ?>) value).values());
        }

        if (value.getClass().isArray()) {
            int open = 0;
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                if (isOpenReport(Array.get(value, i))) {
                    open++;
                }
            }
            return open;
        }

        return null;
    }

    private int countOpenValues(Collection<?> values) {
        int open = 0;
        for (Object value : values) {
            if (isOpenReport(value)) {
                open++;
            }
        }
        return open;
    }

    private boolean isOpenReport(Object report) {
        if (report == null) {
            return false;
        }

        String text = report.toString().toLowerCase(Locale.ROOT);
        if (text.contains("closed") || text.contains("removed") || text.contains("deleted")) {
            return false;
        }

        Object status = readNoArg(report, "getStatus");
        if (status == null) {
            status = readNoArg(report, "status");
        }

        if (status != null) {
            String statusText = status.toString().toLowerCase(Locale.ROOT);
            return statusText.contains("open") || statusText.contains("active") || statusText.contains("new");
        }

        return true;
    }

    private Object readNoArg(Object object, String methodName) {
        try {
            Method method = object.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
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
            return isOpenReportFile(file) ? 1 : 0;
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

    private boolean isOpenReportFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml") && !name.endsWith(".json")) {
            return false;
        }

        String content = readSmallFile(file).toLowerCase(Locale.ROOT);
        if (content.contains("status: closed") || content.contains("\"status\":\"closed\"")) {
            return false;
        }
        return content.isEmpty()
                || content.contains("status: open")
                || content.contains("\"status\":\"open\"")
                || name.contains("report");
    }

    private String readSmallFile(File file) {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lines = 0;
            while ((line = reader.readLine()) != null && lines < 80) {
                builder.append(line).append('\n');
                lines++;
            }
        } catch (Throwable ignored) {
            return "";
        }
        return builder.toString();
    }
}
