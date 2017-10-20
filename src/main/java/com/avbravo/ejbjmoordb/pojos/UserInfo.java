/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.avbravo.ejbjmoordb.pojos;

import com.avbravo.ejbjmoordb.anotations.Id;
import java.util.Date;
import lombok.Getter;
import lombok.Setter;

/**
 *
 * @author avbravo
 */
@Getter
@Setter
public class UserInfo {

    @Id
    private String iduserinfo;
    private String username;
    private Date datetime;
    private String description;

    public UserInfo() {
    }

    public UserInfo(String iduserinfo, String username, Date datetime, String description) {
        this.iduserinfo = iduserinfo;
        this.username = username;
        this.datetime = datetime;
        this.description = description;
    }

  

    @Override
    public String toString() {
        return "Userinfo{" + "username=" + username + ", datetime=" + datetime + ", description=" + description + '}';
    }

}
