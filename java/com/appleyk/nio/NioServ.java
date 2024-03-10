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
 * <p>主要演示configureBlocking设置的属性值的作用</p>
 *
 * @author Appleyk
 * @version v1.0
 * @blob https://blog.csdn.net/appleyk
 * @github https://github.com/kobeyk
 * @date created on 2023/8/26:23:34
 */
public class NioServ {
    int port = 8866;
    Selector selector;
    ServerSocketChannel server;

    public NioServ() throws Exception{
        selector = Selector.open();
        server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress(port));
        server.configureBlocking(true);
    }

    void listen() throws Exception{
        System.out.println("服务端已启动....");
        while (true){
            SocketChannel client = server.accept();
            new Thread(new Pooller(selector,client)).start();
        }
    }

    class Pooller implements Runnable{
        Selector selector;
        SocketChannel client;
        public Pooller(Selector selector,SocketChannel client){
            this.selector = selector;
            this.client = client;
        }

        @Override
        public void run(){
            try{
                client.configureBlocking(false);
                client.register(selector, SelectionKey.OP_READ);
                while (true){
                    if (selector.select(100) == 0){
                        continue;
                    }
                    Set<SelectionKey> selectionKeys = selector.selectedKeys();
                    Iterator<SelectionKey> iterator = selectionKeys.iterator();
                    while (iterator.hasNext()){
                        SelectionKey key = iterator.next();
                        if (key.isReadable()){
                            SocketChannel clientChannel = (SocketChannel) key.channel();
                            ByteBuffer byteBuffer = ByteBuffer.allocate(9);
                            int read = clientChannel.read(byteBuffer);
                            if (read == -1){
                                clientChannel.close();;
                            }else{
                                String s = new String(byteBuffer.array());
                                System.out.println(s);
                            }
                        }
                        iterator.remove();
                    }
                }
            }catch (Exception e){

            }
        }
    }

    public static void main(String[] args) throws Exception{
        NioServ nioServ = new NioServ();
        nioServ.listen();
    }
}
