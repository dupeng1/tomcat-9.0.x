package com.appleyk.eltest;

/**
 * <p>EL本身并不具有处理字符串能力，这里我们自定义一个字符串转大写的函数类</p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/8/20:15:21
 */
public class ELFunc {
    private static ELFunc instance;
    public static ELFunc getInstance(){
        if (instance == null){
            instance = new ELFunc();
        }
        return instance;
    }
    public static String toUpper(String str){
        return str.toUpperCase();
    }
}
