package com.appleyk.model;

/**
 * <p>简单pojo对象</p>
 *
 * @author appleyk
 * @version v.1.0
 * @date created on 2023/6/19-11:29
 */
public class Person {

    private String name;


    public Person(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
