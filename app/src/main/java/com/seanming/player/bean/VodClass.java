package com.seanming.player.bean;

import java.io.Serializable;

/** 分类 */
public class VodClass implements Serializable {
    public String type_id;
    public String type_name;
    public String type_flag;
    public boolean selected;

    public VodClass() {}

    public VodClass(String type_id, String type_name) {
        this.type_id = type_id;
        this.type_name = type_name;
    }
}
