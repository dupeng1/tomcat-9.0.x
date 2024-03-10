package com.appleyk.nio;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * <p>主要演示说明SelectionKey处理完要remove的作用</p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/8/26:23:34
 */
public class NioServ2 {
    int port = 8866;
    Selector selector;
    ServerSocketChannel server;

    public NioServ2() throws Exception{
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(port));
        server.configureBlocking(false);
        server.register(selector,SelectionKey.OP_ACCEPT);

    }

    void listen() throws Exception{
        System.out.println("服务端已启动....");
        while (true){
             int count = selector.select(200);
             if (count == 0){
                 continue;
             }
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                iterator.remove();
                if (key.isAcceptable()){
                    SocketChannel client = server.accept();
                    System.out.println(String.format("客户端-%s,已上线",client.getRemoteAddress().toString().substring(1)));
                    /***/
                }
            }
        }
    }

    public static void main(String[] args) throws Exception{
        NioServ2 nioServ = new NioServ2();
        nioServ.listen();
    }
}
