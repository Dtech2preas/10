package com.jonas.x24

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

data class LocalLog(val id: Long, val type: String, val data: String, val timestamp: Long)

class LocalDatabaseHelper(val context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "x24LocalLogs.db"
        private const val TABLE_LOGS = "LogsTable"

        private const val COLUMN_ID = "id"
        private const val COLUMN_TYPE = "type"
        private const val COLUMN_DATA = "data"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = ("CREATE TABLE " + TABLE_LOGS + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_TYPE + " TEXT,"
                + COLUMN_DATA + " TEXT,"
                + COLUMN_TIMESTAMP + " INTEGER" + ")")
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOGS)
        onCreate(db)
    }

    fun insertLog(type: String, data: String, timestamp: Long): Long {
        val db = this.writableDatabase
        val values = ContentValues()
        values.put(COLUMN_TYPE, type)
        values.put(COLUMN_DATA, data)
        values.put(COLUMN_TIMESTAMP, timestamp)

        val id = db.insert(TABLE_LOGS, null, values)
        db.close()
        return id
    }

    fun getAllLogs(): List<LocalLog> {
        val logList = mutableListOf<LocalLog>()
        val selectQuery = "SELECT * FROM $TABLE_LOGS ORDER BY $COLUMN_TIMESTAMP ASC"

        val db = this.readableDatabase
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            do {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_ID))
                val type = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_TYPE))
                val data = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_DATA))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_TIMESTAMP))
                logList.add(LocalLog(id, type, data, timestamp))
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return logList
    }

    fun clearLogs() {
        val db = this.writableDatabase
        db.execSQL("DELETE FROM " + TABLE_LOGS)
        db.close()
    }

    fun getDatabaseSizeMB(): Double {
        val dbFile: File = context.getDatabasePath(DATABASE_NAME)
        if (dbFile.exists()) {
            val bytes = dbFile.length()
            return bytes / (1024.0 * 1024.0)
        }
        return 0.0
    }
}
