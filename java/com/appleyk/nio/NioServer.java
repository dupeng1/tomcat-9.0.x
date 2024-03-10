package com.appleyk.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.Set;

public class NioServer {
    private int port= 8866;
    private Selector selector;
    private ServerSocketChannel server;
    public NioServer() throws IOException {
        this.selector = Selector.open();
        this.server = ServerSocketChannel.open();
        server.bind(new InetSocketAddress("127.0.0.1",port));
        server.configureBlocking(false);
        server.register(selector, SelectionKey.OP_ACCEPT);
    }
    private void listen() throws IOException{
        while (true){
            if (selector.select(2000) == 0){
                continue;
            }
            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()){
                SelectionKey key = iterator.next();
                if (key.isAcceptable()){
                    SocketChannel clientChannel = server.accept();
                    System.out.println(String.format("客户端-%s,已上线",clientChannel.getRemoteAddress().toString().substring(1)));
                    clientChannel.configureBlocking(false);
                    clientChannel.register(selector,SelectionKey.OP_READ);
                }else if (key.isReadable()){
                    readHandler(key);
                }
                iterator.remove();
            }
        }
    }

    private void readHandler(SelectionKey key) throws IOException{
        SocketChannel channel = (SocketChannel) key.channel();
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        int read = channel.read(buffer);
        if (read == -1){
            /**如果等于-1，说明客户端断了*/
            close(key,channel);
        }else if (read > 0){
            String msg = new String(buffer.array());
            String info = String.format("客户端-%s说：%s", channel.getRemoteAddress().toString()
                .substring(1), msg);
            System.out.println(info);
            send2Others(channel,info);
        }

    }

    private void send2Others(SocketChannel self, String msg) throws IOException{
        Set<SelectionKey> keys = selector.keys();
        for (SelectionKey key : keys) {
            Channel channel = key.channel();
            if (channel instanceof SocketChannel && self!=channel){
                SocketChannel client = (SocketChannel)channel;
                ByteBuffer buffer = ByteBuffer.wrap(msg.trim().getBytes());
                client.write(buffer);
            }
        }
    }

    private void close(SelectionKey key,SocketChannel channel)throws IOException{
        String msg = String.format("客户端-%s,已下线。",channel.getRemoteAddress().toString().substring(1));
        System.out.println(msg);
        send2Others(channel,msg);
        channel.close();
        key.cancel();
    }

    public static void main(String[] args) throws IOException {
        NioServer server = new NioServer();
        System.out.println("服务器已启动.....");
        server.listen();
    }
}
