package com.seanming.player.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface HistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(HistoryRecord record);

    @Query("SELECT * FROM history WHERE id = :id LIMIT 1")
    HistoryRecord find(String id);

    @Query("SELECT * FROM history ORDER BY updateTime DESC LIMIT 200")
    List<HistoryRecord> all();

    @Query("DELETE FROM history WHERE id = :id")
    void delete(String id);

    @Query("DELETE FROM history")
    void clear();
}
