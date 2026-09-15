package top.weixiansen574.hybridfilexfer.core;

import top.weixiansen574.hybridfilexfer.core.bean.Directory;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 文件名（路径段）清洗：把在对端文件系统上非法的名字改写成可创建的等价名字。
 *
 * <p>背景（Issue #34 / #35）：Android/Linux 允许 {@code : * ? " < > |} 等字符（常见于
 * “2025-2-8 17:20:09.jpg”这种时间命名），传到 Windows 会在创建文件时直接失败。
 * 另外 Windows 还有几个隐形规则会造成「传输路径 ≠ 磁盘上的真实文件名」，
 * 而传输路径现在是断点续传检查点的键，一旦不一致就会静默失去续传能力：
 * 尾部点/空格被静默丢弃、保留设备名（CON/NUL/COM1…）指向设备而不是文件。</p>
 *
 * <p>清洗只依据<b>接收方</b>的文件系统类型（{@link Directory#fileSystem}）。</p>
 */
public class FileSanitizer {
    /** Windows 不允许出现在文件名中的字符（分隔符也一并替换，避免路径段被拆开） */
    private static final String WINDOWS_INVALID_CHARS = "\\/:*?\"<>|";
    /** Windows 保留设备名：这些名字（含带扩展名的形式，如 CON.txt）指向设备而非文件 */
    private static final String[] WINDOWS_RESERVED_NAMES = {
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    };
    /** NTFS/exFAT 的路径段上限是 255 个 UTF-16 单元，取保守值（还要给 uniquePath 的 "_N" 后缀留位置） */
    private static final int MAX_SEGMENT_LENGTH = 200;
    /**
     * ext4/f2fs 的路径段上限是 255 <b>字节</b>（NAME_MAX），不是字符数。
     * <p>一个汉字占 3 字节，按字符截断会写出 600 字节的名字——仍然创建失败（Issue #113 那一类）。
     * 240 留出 uniquePath 加序号（最多 5 字节）的余量。</p>
     */
    private static final int MAX_SEGMENT_BYTES = 240;

    /**
     * 清洗一个路径段（不含路径分隔符），返回在目标文件系统上可创建的等价名字。
     *
     * @param name       原始路径段；为空时返回占位名
     * @param fileSystem 目标文件系统类型（{@link Directory#FILE_SYSTEM_WINDOWS} / {@link Directory#FILE_SYSTEM_UNIX}）
     */
    public static String sanitizeSegment(String name, int fileSystem) {
        if (name == null || name.isEmpty()) {
            return "_";
        }
        String result = replaceInvalidChars(name);
        if (fileSystem == Directory.FILE_SYSTEM_WINDOWS) {
            //Windows 会静默丢弃结尾的点与空格，若不清洗则磁盘文件名与传输路径不一致
            result = trimTrailingDotsAndSpaces(result);
            result = escapeReservedName(result);
        }
        if (fileSystem == Directory.FILE_SYSTEM_WINDOWS) {
            //NTFS 按 UTF-16 单元算，char 数即上限
            if (result.length() > MAX_SEGMENT_LENGTH) {
                result = result.substring(0, MAX_SEGMENT_LENGTH);
            }
        } else {
            //Linux/Android 按字节算：必须按 UTF-8 字节截断，否则中文名截完还是超限
            result = truncateToUtf8Bytes(result, MAX_SEGMENT_BYTES);
        }
        return result.isEmpty() ? "_" : result;
    }

    /** 按 UTF-8 字节数截断，且不切开代理对（emoji 等占两个 char） */
    private static String truncateToUtf8Bytes(String name, int maxBytes) {
        if (name.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return name;
        }
        int bytes = 0;
        int end = 0;
        while (end < name.length()) {
            int codePoint = name.codePointAt(end);
            int width = utf8Length(codePoint);
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return name.substring(0, end);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        } else if (codePoint < 0x800) {
            return 2;
        } else if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /** 把非法字符与控制字符替换为下划线（无论目标平台，见类注释：Linux 也可能挂载 exFAT） */
    private static String replaceInvalidChars(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (WINDOWS_INVALID_CHARS.indexOf(c) >= 0 || c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String trimTrailingDotsAndSpaces(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        return name.substring(0, end);
    }

    private static String escapeReservedName(String name) {
        int dot = name.indexOf('.');
        String baseName = dot < 0 ? name : name.substring(0, dot);
        for (String reserved : WINDOWS_RESERVED_NAMES) {
            if (baseName.equalsIgnoreCase(reserved)) {
                return "_" + name;   //加前缀即可，保留原名可读性
            }
        }
        return name;
    }

    /**
     * 保证传输路径唯一：已出现过时在扩展名前插入序号（{@code a.txt} → {@code a_1.txt}）。
     *
     * <p>为何必须去重：清洗会把不同名字映射成同一个名字（{@code a:b.txt} 与 {@code a_b.txt}
     * 都变成 {@code a_b.txt}），而传输路径是断点续传检查点的主键、落盘状态（水位线）的键、
     * 校验清单的键。撞车会让两个不同文件共享同一份落盘状态 → 水位线互相污染 → 输出损坏。</p>
     *
     * @param used 已占用的传输路径集合，调用方按出现顺序逐个传入
     */
    public static String uniquePath(String path, Set<String> used) {
        if (used.add(path)) {
            return path;
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        boolean hasExtension = dot > slash;
        String head = hasExtension ? path.substring(0, dot) : path;
        String extension = hasExtension ? path.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = head + "_" + i + extension;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }
}
