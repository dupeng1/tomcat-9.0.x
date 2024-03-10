package com.appleyk.jmx;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

public class HelloAgent {
    public static void main(String[] args) throws Exception {
        MBeanServer server  = ManagementFactory.getPlatformMBeanServer();
        String domain = "com.appleyk.jmx";
        String key = "type";
        String val = "hello";
        ObjectName helloName = new ObjectName(String.format("%s:%s=%s",domain,key,val));
        /**创建和注册mbean*/
        Hello hello = new Hello();
        hello.setName("appleyk");
        hello.setAge(18);
        server.registerMBean(hello,helloName);
        String name = hello.getName();
        int age = hello.getAge();
        while (true){
            Thread.sleep(3000);
            if (!name.equals(hello.getName())){
                System.out.println("name = "+hello.getName());
                name = hello.getName();
            }
            if (age != hello.getAge()){
                System.out.println("age = "+hello.getAge());
                age = hello.getAge();
            }
        }
    }
}