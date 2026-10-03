package com.seanming.player.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {HistoryRecord.class, Favorite.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public abstract HistoryDao historyDao();
    public abstract FavoriteDao favoriteDao();

    private static volatile AppDatabase instance;

    public static AppDatabase get(Context ctx) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(ctx.getApplicationContext(),
                                    AppDatabase.class, "sean_ming.db")
                            .allowMainThreadQueries() // TV 端数据量小, 简化读写; 后续可迁移到线程池
                            .build();
                }
            }
        }
        return instance;
    }
}
