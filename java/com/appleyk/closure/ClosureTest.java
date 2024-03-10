package com.appleyk.closure;

/**
 * <p></p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/4/19:13:26
 */
public class ClosureTest {

    interface ISay{
        public String say(String message);
    }
    public ISay methodA(){
        String name = "appleyk";
        return message -> {
            if (message == null || message == ""){
                message = name;
            }
            return "Talk: "+message;
        };
    }

    public static void main(String[] args) {
        ClosureTest test = new ClosureTest();
        ISay iSay = test.methodA();
        String say = iSay.say("");
        System.out.println(say);
    }
}
