package top.weixiansen574.hybridfilexfer;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import top.weixiansen574.hybridfilexfer.bean.BookMark;
import top.weixiansen574.hybridfilexfer.core.CheckpointEntry;
import top.weixiansen574.hybridfilexfer.core.bean.RemoteFile;

@SuppressLint("Range")
public class ConfigDB extends SQLiteOpenHelper {
    public static final int VERSION = 3;
    private static ConfigDB instance;
    private ConfigDB(@Nullable Context context) {
        super(context, "config", null, VERSION);
    }

    public synchronized static ConfigDB getInstance(Context context){
        if (instance == null){
            instance = new ConfigDB(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE local_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    NOT NULL\n" +
                "                 UNIQUE\n" +
                ");");
        db.execSQL("CREATE TABLE remote_dir_bookmarks (\n" +
                "    id   INTEGER PRIMARY KEY AUTOINCREMENT\n" +
                "                 NOT NULL,\n" +
                "    path TEXT    UNIQUE\n" +
                "                 NOT NULL\n" +
                ");");
        db.execSQL(CREATE_TRANSFER_CHECKPOINT_TABLE);
    }

    //断点续传：传输检查点表（completed_bytes 为字节偏移持久化单位，与块大小解耦）
    private static final String CREATE_TRANSFER_CHECKPOINT_TABLE =
            "CREATE TABLE transfer_checkpoint (\n" +
            "    id               INTEGER PRIMARY KEY AUTOINCREMENT\n" +
            "                             NOT NULL,\n" +
            "    file_path        TEXT    NOT NULL,\n" +
            "    total_size       INTEGER NOT NULL,\n" +
            "    last_modified    INTEGER NOT NULL,\n" +
            "    completed_bytes  INTEGER NOT NULL DEFAULT 0,\n" +
            "    peer_id          TEXT    NOT NULL,\n" +
            "    timestamp        INTEGER NOT NULL,\n" +
            "    UNIQUE(file_path, peer_id)\n" +
            ");";

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL(CREATE_TRANSFER_CHECKPOINT_TABLE);
        } else if (oldVersion < 3) {
            //v2 表使用 completed_blocks（块数）持久化，迁移为 completed_bytes（字节偏移）
            db.execSQL("ALTER TABLE transfer_checkpoint RENAME TO transfer_checkpoint_old");
            db.execSQL(CREATE_TRANSFER_CHECKPOINT_TABLE);
            db.execSQL("INSERT INTO transfer_checkpoint (file_path, total_size, last_modified, completed_bytes, peer_id, timestamp) " +
                    "SELECT file_path, total_size, last_modified, completed_blocks * 1048576, peer_id, timestamp " +
                    "FROM transfer_checkpoint_old");
            db.execSQL("DROP TABLE transfer_checkpoint_old");
        }
    }

    public long addLocalBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("local_dir_bookmarks", null, cv);

    }

    public long addRemoteBookmark(String path) {
        ContentValues cv = new ContentValues();
        cv.put("path", path);
        return getWritableDatabase()
                .insert("remote_dir_bookmarks", null, cv);
    }

    public boolean checkLocalBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM local_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }

    public boolean checkRemoteBookmarkExists(String path) {
        Cursor cursor = getReadableDatabase()
                .rawQuery("SELECT * FROM remote_dir_bookmarks WHERE path = ?", new String[]{path});
        boolean exists = cursor.moveToFirst();
        cursor.close();
        return exists;
    }


    public long removeLocalBookmark(int id) {
        return getWritableDatabase()
                .delete("local_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }

    public long removeRemoteBookmark(int id) {
        return getWritableDatabase()
                .delete("remote_dir_bookmarks", "id = ?", new String[]{String.valueOf(id)});
    }


    public List<BookMark> getAllLocalBookmark() {
        return getAllBookmarks("local_dir_bookmarks");
    }

    public List<BookMark> getAllRemoteBookmark() {
        return getAllBookmarks("remote_dir_bookmarks");
    }

    private List<BookMark> getAllBookmarks(String tableName){
        Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM "+tableName, null);
        List<BookMark> bookmarks = new ArrayList<>(cursor.getCount());
        while (cursor.moveToNext()) {
            bookmarks.add(
                    new BookMark(
                            cursor.getInt(cursor.getColumnIndex("id")),
                            cursor.getString(cursor.getColumnIndex("path"))
                    ));
        }
        cursor.close();
        return bookmarks;
    }

    //===== 断点续传：transfer_checkpoint 表 CRUD =====

    /**
     * 保存/更新一个文件的检查点（(file_path, peer_id) 唯一，冲突时替换）。
     *
     * @param completedBytes 已确认完成的字节偏移
     */
    public void saveCheckpoint(String filePath, long totalSize, long lastModified,
                               long completedBytes, String peerId) {
        ContentValues cv = new ContentValues();
        cv.put("file_path", filePath);
        cv.put("total_size", totalSize);
        cv.put("last_modified", lastModified);
        cv.put("completed_bytes", completedBytes);
        cv.put("peer_id", peerId);
        cv.put("timestamp", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("transfer_checkpoint", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 加载文件列表中匹配的检查点（peerId 匹配 + totalSize/lastModified 校验通过才返回）。
     *
     * @return file_path → CheckpointEntry（仅含列表中的文件）
     */
    public Map<String, CheckpointEntry> loadCheckpoints(List<RemoteFile> files, String peerId) {
        Map<String, CheckpointEntry> result = new HashMap<>();
        if (files.isEmpty()) {
            return result;
        }
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT * FROM transfer_checkpoint WHERE peer_id = ?",
                new String[]{peerId});
        Map<String, CheckpointEntry> allEntries = new HashMap<>();
        while (cursor.moveToNext()) {
            String path = cursor.getString(cursor.getColumnIndex("file_path"));
            allEntries.put(path, new CheckpointEntry(
                    path,
                    cursor.getLong(cursor.getColumnIndex("total_size")),
                    cursor.getLong(cursor.getColumnIndex("last_modified")),
                    cursor.getLong(cursor.getColumnIndex("completed_bytes")),
                    cursor.getString(cursor.getColumnIndex("peer_id")),
                    cursor.getLong(cursor.getColumnIndex("timestamp"))
            ));
        }
        cursor.close();
        for (RemoteFile file : files) {
            if (file.isDirectory()) {
                continue;
            }
            CheckpointEntry entry = allEntries.get(file.getPath());
            if (entry != null && entry.totalSize == file.getSize()
                    && entry.lastModified == file.lastModified()) {
                result.put(file.getPath(), entry);
            }
        }
        return result;
    }

    /**
     * 清除单个文件的检查点。
     */
    public void clearCheckpoint(String filePath, String peerId) {
        getWritableDatabase().delete("transfer_checkpoint",
                "file_path = ? AND peer_id = ?", new String[]{filePath, peerId});
    }

    /**
     * 清除某个对端的全部检查点。
     */
    public void clearAllCheckpoints(String peerId) {
        getWritableDatabase().delete("transfer_checkpoint", "peer_id = ?", new String[]{peerId});
    }

    /**
     * 清理超过指定天数未更新的检查点。
     */
    public void cleanupOldCheckpoints(int maxAgeDays) {
        long deadline = System.currentTimeMillis() - maxAgeDays * 24L * 3600 * 1000;
        getWritableDatabase().delete("transfer_checkpoint", "timestamp < ?", new String[]{String.valueOf(deadline)});
    }
}
