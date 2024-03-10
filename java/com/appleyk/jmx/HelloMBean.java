package com.appleyk.jmx;

/**
 * JMX（Java Management Extensions，即Java管理扩展）是一个为应用程序、设备、
 * 系统等植入管理功能的框架。JMX可以跨越一系列异构操作系统平台、
 * 系统体系结构和网络传输协议，灵活的开发无缝集成的系统、网络和服务管理应用
 * 标准的MBean必须以***MBean结尾，否则会报错,如下：
 * MBean class com.appleyk.jmx.Hello does not implement DynamicMBean
 */
public interface HelloMBean {
    String getName();
    void setName(String name);
    int getAge();
    void setAge(int age);
    /**jconsole中可以看到这个操作方法，同时也可以调用*/
    void printHello(String message);
}
