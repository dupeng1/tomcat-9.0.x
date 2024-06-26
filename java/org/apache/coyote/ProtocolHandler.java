/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote;

import org.apache.tomcat.util.net.SSLHostConfig;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Abstract the protocol implementation, including threading, etc.
 *
 * This is the main interface to be implemented by a coyote protocol.
 * Adapter is the main interface to be implemented by a coyote servlet
 * container.
 * 对协议实现进行抽象，包括线程等，这是coyote协议要实现的主要接口。 Adapter 是 coyote servlet 容器要实现的主要接口。
 * @author Remy Maucherat
 * @author Costin Manolache
 * @see Adapter
 */
public interface ProtocolHandler {

    /**
     * Return the adapter associated with the protocol handler.
     * @return the adapter
     */
    // 获取适配器
    public Adapter getAdapter();


    /**
     * The adapter, used to call the connector.
     *
     * @param adapter The adapter to associate
     */
    // 设置适配器
    public void setAdapter(Adapter adapter);


    /**
     * The executor, provide access to the underlying thread pool.
     * 执行器(线程容器，也就是线程池)，提供对底层线程池的访问。
     * @return The executor used to process requests
     */
    // 获取执行线程池
    public Executor getExecutor();


    /**
     * Set the optional executor that will be used by the connector.
     * @param executor the executor
     */
    // 设置执行线程池
    public void setExecutor(Executor executor);


    /**
     * Get the utility executor that should be used by the protocol handler.
     * @return the executor
     */
    public ScheduledExecutorService getUtilityExecutor();


    /**
     * Set the utility executor that should be used by the protocol handler.
     * @param utilityExecutor the executor
     */
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor);


    /**
     * Initialise the protocol.
     * 初始化协议，默认实现是NioEndpoint的initServerSocket方法，初始化套接字
     * @throws Exception If the protocol handler fails to initialise
     */
    public void init() throws Exception;


    /**
     * Start the protocol.
     * 启动协议处理器
     * @throws Exception If the protocol handler fails to start
     */
    public void start() throws Exception;


    /**
     * Pause the protocol (optional).
     * 暂停协议处理器
     * @throws Exception If the protocol handler fails to pause
     */
    public void pause() throws Exception;


    /**
     * Resume the protocol (optional).
     *
     * @throws Exception If the protocol handler fails to resume
     */
    public void resume() throws Exception;


    /**
     * Stop the protocol.
     *
     * @throws Exception If the protocol handler fails to stop
     */
    public void stop() throws Exception;


    /**
     * Destroy the protocol (optional).
     *
     * @throws Exception If the protocol handler fails to destroy
     */
    public void destroy() throws Exception;


    /**
     * Close the server socket (to prevent further connections) if the server
     * socket was bound on {@link #start()} (rather than on {@link #init()}
     * but do not perform any further shutdown.
     */
    public void closeServerSocketGraceful();


    /**
     * Wait for the client connections to the server to close gracefully. The
     * method will return when all of the client connections have closed or the
     * method has been waiting for {@code waitTimeMillis}.
     *
     * @param waitMillis    The maximum time to wait in milliseconds for the
     *                      client connections to close.
     *
     * @return The wait time, if any remaining when the method returned
     */
    public long awaitConnectionsClose(long waitMillis);


    /**
     * Requires APR/native library
     *
     * @return <code>true</code> if this Protocol Handler requires the
     *         APR/native library, otherwise <code>false</code>
     */
    public boolean isAprRequired();


    /**
     * Does this ProtocolHandler support sendfile?
     *
     * @return <code>true</code> if this Protocol Handler supports sendfile,
     *         otherwise <code>false</code>
     */
    public boolean isSendfileSupported();


    /**
     * Add a new SSL configuration for a virtual host.
     * @param sslHostConfig the configuration
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig);


    /**
     * Find all configured SSL virtual host configurations which will be used
     * by SNI.
     * @return the configurations
     */
    public SSLHostConfig[] findSslHostConfigs();


    /**
     * Add a new protocol for used by HTTP/1.1 upgrade or ALPN.
     * @param upgradeProtocol the protocol
     */
    public void addUpgradeProtocol(UpgradeProtocol upgradeProtocol);


    /**
     * Return all configured upgrade protocols.
     * @return the protocols
     */
    public UpgradeProtocol[] findUpgradeProtocols();


    /**
     * Some protocols, like AJP, have a packet length that
     * shouldn't be exceeded, and this can be used to adjust the buffering
     * used by the application layer.
     * @return the desired buffer size, or -1 if not relevant
     */
    public default int getDesiredBufferSize() {
        return -1;
    }


    /**
     * The default behavior is to identify connectors uniquely with address
     * and port. However, certain connectors are not using that and need
     * some other identifier, which then can be used as a replacement.
     * @return the id
     */
    public default String getId() {
        return null;
    }


    /**
     * Create a new ProtocolHandler for the given protocol.
     * 基于给定的协议字符串，创建一个新的协议处理器对象
     * @param protocol the protocol
     * @param apr if <code>true</code> the APR protcol handler will be used
     * @return the newly instantiated protocol handler
     * @throws ClassNotFoundException Specified protocol was not found
     * @throws InstantiationException Specified protocol could not be instantiated
     * @throws IllegalAccessException Exception occurred
     * @throws IllegalArgumentException Exception occurred
     * @throws InvocationTargetException Exception occurred
     * @throws NoSuchMethodException Exception occurred
     * @throws SecurityException Exception occurred
     */
    public static ProtocolHandler create(String protocol, boolean apr)
            throws ClassNotFoundException, InstantiationException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
        /**基于协议类型创建协议处理器实例*/
        if (protocol == null || "HTTP/1.1".equals(protocol)
                || (!apr && org.apache.coyote.http11.Http11NioProtocol.class.getName().equals(protocol))
                || (apr && org.apache.coyote.http11.Http11AprProtocol.class.getName().equals(protocol))) {
            if (apr) {
                /**
                 * 如果apr为true，就使用apr协议
                 * apr=Apache Portable Runtime的简称，是Apache HTTP Server2.x的核心。
                 * 是一个高可移植库，APR有很多用途，包括访问高级IO功能(例如sendfile，epoll和OpenSSL)，
                 * OS级别功能(随机数生成，系统状态等等)，本地进程管理(共享内存，NT管道和UNIXsockets)。
                 * 这些功能可以使Tomcat作为一个通常的前台WEB服务器，能更好地和其它本地web技术集成，总体上让Java更有效率作为一个高性能web服务器平台而不是简单作为后台容器。
                 * */
                return new org.apache.coyote.http11.Http11AprProtocol();
            } else {
                /**默认是nio协议的处理器，当然tomcat还有一个nio2协议*/
                return new org.apache.coyote.http11.Http11NioProtocol();
            }
        } else if ("AJP/1.3".equals(protocol)
                || (!apr && org.apache.coyote.ajp.AjpNioProtocol.class.getName().equals(protocol))
                || (apr && org.apache.coyote.ajp.AjpAprProtocol.class.getName().equals(protocol))) {
            if (apr) {
                return new org.apache.coyote.ajp.AjpAprProtocol();
            } else {
                return new org.apache.coyote.ajp.AjpNioProtocol();
            }
        } else {
            // Instantiate protocol handler
            /**如果不是特定的协议而是协议处理器类完全限定名的话，就反射出对应的处理器对象*/
            Class<?> clazz = Class.forName(protocol);
            return (ProtocolHandler) clazz.getConstructor().newInstance();
        }
    }


}
