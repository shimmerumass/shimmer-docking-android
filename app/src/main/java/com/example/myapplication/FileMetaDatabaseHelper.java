package com.example.myapplication;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class FileMetaDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "filemeta.db";
    private static final int DB_VERSION = 1;

    public FileMetaDatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS files (" +
                "ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "TIMESTAMP TEXT, " +
                "FILE_PATH TEXT, " +
                "SYNCED INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Handle schema upgrades if needed
    }
}