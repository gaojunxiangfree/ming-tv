package com.seanming.player.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface FavoriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void add(Favorite favorite);

    @Query("SELECT * FROM favorite WHERE id = :id LIMIT 1")
    Favorite find(String id);

    @Query("SELECT * FROM favorite ORDER BY createTime DESC")
    List<Favorite> all();

    @Query("DELETE FROM favorite WHERE id = :id")
    void remove(String id);

    @Query("DELETE FROM favorite")
    void clear();
}
