package top.weixiansen574.hybridfilexfer.jdkcore;

import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.CheckpointManager;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PC 端断点续传检查点实现：JSON Lines 文件 {@code ~/.hybridfilexfer/checkpoints.json}。
 * <p>每行一条记录，格式例如：</p>
 * <pre>{"filePath":"E:\\transfer\\a.jpg","totalSize":1081344,"lastModified":1720000000000,"completedBlocks":11,"peerId":"192.168.1.100","timestamp":1720000000000}</pre>
 */
public class JdkCheckpointManager implements CheckpointManager {
    /** 检查点默认保留天数 */
    public static final int DEFAULT_MAX_AGE_DAYS = 7;
    private static final String FILE_NAME = "checkpoints.json";

    private final File file;
    /** 键：filePath + '\u0000' + peerId */
    private final Map<String, CheckpointEntry> entries = new HashMap<>();

    public JdkCheckpointManager() {
        File dir = new File(System.getProperty("user.home"), ".hybridfilexfer");
        if (!dir.exists() && !dir.mkdirs()) {
            System.err.println("cannot create checkpoint dir: " + dir);
        }
        file = new File(dir, FILE_NAME);
        loadFromDisk();
        cleanupOldCheckpoints(DEFAULT_MAX_AGE_DAYS);
    }

    private static String key(String filePath, String peerId) {
        return filePath + '\u0000' + peerId;
    }

    @Override
    public synchronized void saveCheckpoint(String filePath, long totalSize, long lastModified, long completedBytes, String peerId) {
        entries.put(key(filePath, peerId),
                new CheckpointEntry(filePath, totalSize, lastModified, completedBytes, peerId, System.currentTimeMillis()));
        writeToDisk();
    }

    @Override
    public synchronized Map<String, CheckpointEntry> loadCheckpoints(List<RemoteFile> files, String peerId) {
        Map<String, CheckpointEntry> result = new HashMap<>();
        for (RemoteFile file : files) {
            if (file.isDirectory()) {
                continue;
            }
            CheckpointEntry entry = entries.get(key(file.getPath(), peerId));
            if (entry != null && entry.totalSize == file.getSize() && entry.lastModified == file.lastModified()) {
                result.put(file.getPath(), entry);
            }
        }
        return result;
    }

    @Override
    public synchronized void clearCheckpoint(String filePath, String peerId) {
        if (entries.remove(key(filePath, peerId)) != null) {
            writeToDisk();
        }
    }

    @Override
    public synchronized void clearAllCheckpoints(String peerId) {
        boolean changed = entries.keySet().removeIf(k -> k.endsWith("\u0000" + peerId));
        if (changed) {
            writeToDisk();
        }
    }

    @Override
    public synchronized void cleanupOldCheckpoints(int maxAgeDays) {
        long deadline = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000;
        boolean changed = entries.values().removeIf(e -> e.timestamp < deadline);
        if (changed) {
            writeToDisk();
        }
    }

    private void loadFromDisk() {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                CheckpointEntry entry = parseEntry(line);
                if (entry != null) {
                    entries.put(key(entry.filePath, entry.peerId), entry);
                }
            }
        } catch (IOException e) {
            System.err.println("failed to load checkpoints: " + e);
        }
    }

    private void writeToDisk() {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                for (CheckpointEntry entry : entries.values()) {
                    writer.write(serialize(entry));
                    writer.newLine();
                }
            }
            // 原子替换：直接 rename 在目标存在时可能失败，先删旧文件
            if (file.exists() && !file.delete()) {
                System.err.println("failed to replace checkpoints file: " + file);
                return;
            }
            if (!tmp.renameTo(file)) {
                System.err.println("failed to rename checkpoints file: " + file);
            }
        } catch (IOException e) {
            System.err.println("failed to save checkpoints: " + e);
        }
    }

    private static String serialize(CheckpointEntry entry) {
        return "{\"filePath\":\"" + escape(entry.filePath)
                + "\",\"totalSize\":" + entry.totalSize
                + ",\"lastModified\":" + entry.lastModified
                + ",\"completedBytes\":" + entry.completedBytes
                + ",\"peerId\":\"" + escape(entry.peerId)
                + "\",\"timestamp\":" + entry.timestamp + "}";
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 解析一行 JSON 对象为 CheckpointEntry；格式不合法时返回 null */
    private static CheckpointEntry parseEntry(String line) {
        Map<String, String> fields = parseJsonObject(line);
        String filePath = fields.get("filePath");
        String peerId = fields.get("peerId");
        String totalSize = fields.get("totalSize");
        String lastModified = fields.get("lastModified");
        String timestamp = fields.get("timestamp");
        if (filePath == null || peerId == null || totalSize == null
                || lastModified == null || timestamp == null) {
            return null;
        }
        try {
            long completedBytes;
            String bytesField = fields.get("completedBytes");
            String blocksField = fields.get("completedBlocks");
            if (bytesField != null) {
                completedBytes = Long.parseLong(bytesField);
            } else if (blocksField != null) {
                //兼容旧格式：completedBlocks（块数）按 1MB 块换算为字节偏移
                completedBytes = Long.parseLong(blocksField) * (long) top.weixiansen574.hybridfilexfer.core.FileBlock.BLOCK_SIZE;
            } else {
                return null;
            }
            return new CheckpointEntry(filePath,
                    Long.parseLong(totalSize),
                    Long.parseLong(lastModified),
                    completedBytes,
                    peerId,
                    Long.parseLong(timestamp));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 极简 JSON 对象解析器：仅支持本类序列化产生的固定格式（字符串与整数数值） */
    private static Map<String, String> parseJsonObject(String line) {
        Map<String, String> map = new HashMap<>();
        int len = line.length();
        int i = 0;
        if (i >= len || line.charAt(i) != '{') {
            return map;
        }
        i++;
        while (i < len) {
            while (i < len && (line.charAt(i) == ',' || Character.isWhitespace(line.charAt(i)))) {
                i++;
            }
            if (i >= len || line.charAt(i) == '}') {
                break;
            }
            //键
            if (line.charAt(i) != '"') {
                return map;
            }
            i++;
            StringBuilder key = new StringBuilder();
            while (i < len && line.charAt(i) != '"') {
                key.append(line.charAt(i));
                i++;
            }
            i++;//跳过闭合引号
            while (i < len && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= len || line.charAt(i) != ':') {
                return map;
            }
            i++;
            while (i < len && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i < len && line.charAt(i) == '"') {
                //字符串值
                i++;
                StringBuilder value = new StringBuilder();
                while (i < len && line.charAt(i) != '"') {
                    char c = line.charAt(i);
                    if (c == '\\' && i + 1 < len) {
                        i++;
                        char esc = line.charAt(i);
                        switch (esc) {
                            case 'n': value.append('\n'); break;
                            case 'r': value.append('\r'); break;
                            case 't': value.append('\t'); break;
                            case '"': value.append('"'); break;
                            case '\\': value.append('\\'); break;
                            default: value.append(esc);
                        }
                    } else {
                        value.append(c);
                    }
                    i++;
                }
                i++;
                map.put(key.toString(), value.toString());
            } else {
                //数值值
                int start = i;
                while (i < len && line.charAt(i) != ',' && line.charAt(i) != '}') {
                    i++;
                }
                map.put(key.toString(), line.substring(start, i).trim());
            }
        }
        return map;
    }
}