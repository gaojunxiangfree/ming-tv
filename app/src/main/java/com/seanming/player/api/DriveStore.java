package com.seanming.player.api;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.seanming.player.bean.StorageDrive;
import com.seanming.player.util.PrefUtils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 网盘配置持久化(SharedPreferences + Gson).
 * 对标影视仓 StorageDrive 表, 这里用 JSON 列表存储以保持工程约定一致.
 */
public class DriveStore {

    private static final String KEY = "drive_list";
    private static final Gson GSON = new Gson();

    public static List<StorageDrive> getAll() {
        String json = PrefUtils.get(KEY, "");
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            Type t = new TypeToken<List<StorageDrive>>() {}.getType();
            List<StorageDrive> list = GSON.fromJson(json, t);
            return list == null ? new ArrayList<>() : list;
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private static void save(List<StorageDrive> list) {
        PrefUtils.put(KEY, GSON.toJson(list));
    }

    public static void add(StorageDrive d) {
        List<StorageDrive> list = getAll();
        int maxId = 0;
        for (StorageDrive s : list) maxId = Math.max(maxId, s.id);
        d.id = maxId + 1;
        list.add(d);
        save(list);
    }

    public static void remove(int id) {
        List<StorageDrive> list = getAll();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == id) { list.remove(i); break; }
        }
        save(list);
    }
}