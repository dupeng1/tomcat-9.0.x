package org.apache.juli.logging;

/**
 * <p></p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/6/17:22:59
 */
public class MyLog extends DirectJDKLog{
    public MyLog(){
        super(MyLog.class.getName());
        System.out.println("我是自定义的日志组件:"+MyLog.class.getName());
    }
    public MyLog(String name) {
        super(name);
    }
}
