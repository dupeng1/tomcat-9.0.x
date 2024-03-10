/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net;

import org.apache.juli.logging.Log;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.IntrospectionUtils;
import org.apache.tomcat.util.collections.SynchronizedStack;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.Acceptor.AcceptorState;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.apache.tomcat.util.threads.*;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.*;

/**
 * @param <S> The type used by the socket wrapper associated with this endpoint.
 *            May be the same as U.
 * @param <U> The type of the underlying socket used by this endpoint. May be
 *            the same as S.
 * 模板方法
 * @author Mladen Turk
 * @author Remy Maucherat
 */
public abstract class AbstractEndpoint<S,U> {

    // -------------------------------------------------------------- Constants

    protected static final StringManager sm = StringManager.getManager(AbstractEndpoint.class);

    /**实现类 {@link org.apache.coyote.AbstractProtocol.ConnectionHandler<S>}*/
    public static interface Handler<S> {

        /**
         * Different types of socket states to react upon. 要对不同类型的套接字状态做出反应。
         */
        public enum SocketState {
            // TODO Add a new state to the AsyncStateMachine and remove
            //      ASYNC_END (if possible)
            OPEN, CLOSED, LONG, ASYNC_END, SENDFILE, UPGRADING, UPGRADED, SUSPENDED
        }


        /**
         * Process the provided socket with the given current status.
         * 以给定的当前状态处理所提供的套接字。
         * @param socket The socket to process
         * @param status The current socket status
         *
         * @return The state of the socket after processing
         */
        public SocketState process(SocketWrapperBase<S> socket,SocketEvent status);


        /**
         * Obtain the GlobalRequestProcessor associated with the handler.
         * 获取与处理程序相关联的GlobalRequestProcessor。
         * @return the GlobalRequestProcessor
         */
        public Object getGlobal();


        /**
         * Obtain the currently open sockets.
         *
         * @return The sockets for which the handler is tracking a currently
         *         open connection
         * @deprecated Unused, will be removed in Tomcat 10, replaced
         *         by AbstractEndpoint.getConnections
         */
        @Deprecated
        public Set<S> getOpenSockets();

        /**
         * Release any resources associated with the given SocketWrapper.
         *
         * @param socketWrapper The socketWrapper to release resources for
         */
        public void release(SocketWrapperBase<S> socketWrapper);


        /**
         * Inform the handler that the endpoint has stopped accepting any new
         * connections. Typically, the endpoint will be stopped shortly
         * afterwards but it is possible that the endpoint will be resumed so
         * the handler should not assume that a stop will follow.
         */
        public void pause();


        /**
         * Recycle resources associated with the handler.
         * 回收与处理程序关联的资源
         */
        public void recycle();
    }

    protected enum BindState {
        UNBOUND(false, false),
        BOUND_ON_INIT(true, true),
        BOUND_ON_START(true, true),
        SOCKET_CLOSED_ON_STOP(false, true);

        private final boolean bound;
        private final boolean wasBound;

        private BindState(boolean bound, boolean wasBound) {
            this.bound = bound;
            this.wasBound = wasBound;
        }

        public boolean isBound() {
            return bound;
        }

        public boolean wasBound() {
            return wasBound;
        }
    }


    public static long toTimeout(long timeout) {
        // Many calls can't do infinite timeout so use Long.MAX_VALUE if timeout is <= 0
        return (timeout > 0) ? timeout : Long.MAX_VALUE;
    }

    // ----------------------------------------------------------------- Fields

    /**
     * Running state of the endpoint.端点的运行状态
     */
    protected volatile boolean running = false;


    /**
     * Will be set to true whenever the endpoint is paused. 端点暂停时设置为false
     */
    protected volatile boolean paused = false;

    /**
     * Are we using an internal executor 我们是否使用内部执行器
     */
    protected volatile boolean internalExecutor = true;


    /**
     * counter for nr of connections handled by an endpoint
     * 一个端点处理的连接数的计数器（AQS）
     */
    private volatile LimitLatch connectionLimitLatch = null;

    /**
     * Socket properties
     */
    protected final SocketProperties socketProperties = new SocketProperties();
    public SocketProperties getSocketProperties() {
        return socketProperties;
    }

    /**
     * Thread used to accept new connections and pass them to worker threads.
     * 用于接收新连接并将其传递给工作线程的线程
     */
    protected Acceptor<U> acceptor;

    /**
     * Cache for SocketProcessor objects
     * SocketProcessor对象的缓存
     */
    protected SynchronizedStack<SocketProcessorBase<S>> processorCache;

    private ObjectName oname = null;

    /**
     * Map holding all current connections keyed with the sockets.
     * 所有连接的映射，key就是客户端channel，value就是
     */
    protected Map<U, SocketWrapperBase<S>> connections = new ConcurrentHashMap<>();

    /**
     * Get a set with the current open connections.
     * @return A set with the open socket wrappers
     */
    public Set<SocketWrapperBase<S>> getConnections() {
        return new HashSet<>(connections.values());
    }

    // ----------------------------------------------------------------- Properties

    private String defaultSSLHostConfigName = SSLHostConfig.DEFAULT_SSL_HOST_NAME;
    /**
     * @return The host name for the default SSL configuration for this endpoint
     *         - always in lower case.
     */
    public String getDefaultSSLHostConfigName() {
        return defaultSSLHostConfigName;
    }
    public void setDefaultSSLHostConfigName(String defaultSSLHostConfigName) {
        this.defaultSSLHostConfigName = defaultSSLHostConfigName.toLowerCase(Locale.ENGLISH);
    }


    protected ConcurrentMap<String,SSLHostConfig> sslHostConfigs = new ConcurrentHashMap<>();
    /**
     * Add the given SSL Host configuration.
     *
     * @param sslHostConfig The configuration to add
     *
     * @throws IllegalArgumentException If the host name is not valid or if a
     *                                  configuration has already been provided
     *                                  for that host
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig) throws IllegalArgumentException {
        addSslHostConfig(sslHostConfig, false);
    }
    /**
     * Add the given SSL Host configuration, optionally replacing the existing
     * configuration for the given host.
     *
     * @param sslHostConfig The configuration to add
     * @param replace       If {@code true} replacement of an existing
     *                      configuration is permitted, otherwise any such
     *                      attempted replacement will trigger an exception
     *
     * @throws IllegalArgumentException If the host name is not valid or if a
     *                                  configuration has already been provided
     *                                  for that host and replacement is not
     *                                  allowed
     */
    public void addSslHostConfig(SSLHostConfig sslHostConfig, boolean replace) throws IllegalArgumentException {
        String key = sslHostConfig.getHostName();
        if (key == null || key.length() == 0) {
            throw new IllegalArgumentException(sm.getString("endpoint.noSslHostName"));
        }
        if (bindState != BindState.UNBOUND && bindState != BindState.SOCKET_CLOSED_ON_STOP &&
                isSSLEnabled()) {
            try {
                createSSLContext(sslHostConfig);
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        if (replace) {
            SSLHostConfig previous = sslHostConfigs.put(key, sslHostConfig);
            if (previous != null) {
                unregisterJmx(sslHostConfig);
            }
            registerJmx(sslHostConfig);

            // Do not release any SSLContexts associated with a replaced
            // SSLHostConfig. They may still be in used by existing connections
            // and releasing them would break the connection at best. Let GC
            // handle the clean up.
        } else {
            SSLHostConfig duplicate = sslHostConfigs.putIfAbsent(key, sslHostConfig);
            if (duplicate != null) {
                releaseSSLContext(sslHostConfig);
                throw new IllegalArgumentException(sm.getString("endpoint.duplicateSslHostName", key));
            }
            registerJmx(sslHostConfig);
        }
    }
    /**
     * Removes the SSL host configuration for the given host name, if such a
     * configuration exists.
     *
     * @param hostName  The host name associated with the SSL host configuration
     *                  to remove
     *
     * @return  The SSL host configuration that was removed, if any
     */
    public SSLHostConfig removeSslHostConfig(String hostName) {
        if (hostName == null) {
            return null;
        }
        // Host names are case insensitive but stored/processed in lower case
        // internally because they are used as keys in a ConcurrentMap where
        // keys are compared in a case sensitive manner.
        String hostNameLower = hostName.toLowerCase(Locale.ENGLISH);
        if (hostNameLower.equals(getDefaultSSLHostConfigName())) {
            throw new IllegalArgumentException(
                    sm.getString("endpoint.removeDefaultSslHostConfig", hostName));
        }
        SSLHostConfig sslHostConfig = sslHostConfigs.remove(hostNameLower);
        unregisterJmx(sslHostConfig);
        return sslHostConfig;
    }
    /**
     * Re-read the configuration files for the SSL host and replace the existing
     * SSL configuration with the updated settings. Note this replacement will
     * happen even if the settings remain unchanged.
     *
     * @param hostName The SSL host for which the configuration should be
     *                 reloaded. This must match a current SSL host
     */
    public void reloadSslHostConfig(String hostName) {
        // Host names are case insensitive but stored/processed in lower case
        // internally because they are used as keys in a ConcurrentMap where
        // keys are compared in a case sensitive manner.
        // This method can be called via various paths so convert the supplied
        // host name to lower case here to ensure the conversion occurs whatever
        // the call path.
        SSLHostConfig sslHostConfig = sslHostConfigs.get(hostName.toLowerCase(Locale.ENGLISH));
        if (sslHostConfig == null) {
            throw new IllegalArgumentException(
                    sm.getString("endpoint.unknownSslHostName", hostName));
        }
        addSslHostConfig(sslHostConfig, true);
    }
    /**
     * Re-read the configuration files for all SSL hosts and replace the
     * existing SSL configuration with the updated settings. Note this
     * replacement will happen even if the settings remain unchanged.
     */
    public void reloadSslHostConfigs() {
        for (String hostName : sslHostConfigs.keySet()) {
            reloadSslHostConfig(hostName);
        }
    }
    public SSLHostConfig[] findSslHostConfigs() {
        return sslHostConfigs.values().toArray(new SSLHostConfig[0]);
    }

    /**
     * Create the SSLContextfor the the given SSLHostConfig.
     *
     * @param sslHostConfig The SSLHostConfig for which the SSLContext should be
     *                      created
     * @throws Exception If the SSLContext cannot be created for the given
     *                   SSLHostConfig
     */
    protected abstract void createSSLContext(SSLHostConfig sslHostConfig) throws Exception;


    protected void destroySsl() throws Exception {
        if (isSSLEnabled()) {
            for (SSLHostConfig sslHostConfig : sslHostConfigs.values()) {
                releaseSSLContext(sslHostConfig);
            }
        }
    }


    /**
     * Release the SSLContext, if any, associated with the SSLHostConfig.
     *
     * @param sslHostConfig The SSLHostConfig for which the SSLContext should be
     *                      released
     */
    protected void releaseSSLContext(SSLHostConfig sslHostConfig) {
        for (SSLHostConfigCertificate certificate : sslHostConfig.getCertificates(true)) {
            if (certificate.getSslContext() != null) {
                SSLContext sslContext = certificate.getSslContext();
                if (sslContext != null) {
                    sslContext.destroy();
                }
            }
        }
    }


    /**
     * Look up the SSLHostConfig for the given host name. Lookup order is:
     * <ol>
     * <li>exact match</li>
     * <li>wild card match</li>
     * <li>default SSLHostConfig</li>
     * </ol>
     *
     * @param sniHostName   Host name - must be in lower case
     *
     * @return The SSLHostConfig for the given host name.
     */
    protected SSLHostConfig getSSLHostConfig(String sniHostName) {
        SSLHostConfig result = null;

        if (sniHostName != null) {
            // First choice - direct match
            result = sslHostConfigs.get(sniHostName);
            if (result != null) {
                return result;
            }
            // Second choice, wildcard match
            int indexOfDot = sniHostName.indexOf('.');
            if (indexOfDot > -1) {
                result = sslHostConfigs.get("*" + sniHostName.substring(indexOfDot));
            }
        }

        // Fall-back. Use the default
        if (result == null) {
            result = sslHostConfigs.get(getDefaultSSLHostConfigName());
        }
        if (result == null) {
            // Should never happen.
            throw new IllegalStateException();
        }
        return result;
    }


    /**
     * Has the user requested that send file be used where possible?
     */
    private boolean useSendfile = true;
    public boolean getUseSendfile() {
        return useSendfile;
    }
    public void setUseSendfile(boolean useSendfile) {
        this.useSendfile = useSendfile;
    }


    /**
     * Time to wait for the internal executor (if used) to terminate when the
     * endpoint is stopped in milliseconds. Defaults to 5000 (5 seconds).
     * 在继续停止连接器的过程之前，私有内部执行器将等待请求处理线程终止的时间。 如果未设置，默认值为 5000（5 秒）。
     */
    private long executorTerminationTimeoutMillis = 5000;

    public long getExecutorTerminationTimeoutMillis() {
        return executorTerminationTimeoutMillis;
    }

    public void setExecutorTerminationTimeoutMillis(
            long executorTerminationTimeoutMillis) {
        this.executorTerminationTimeoutMillis = executorTerminationTimeoutMillis;
    }


    /**
     * Acceptor thread count.
     * 用于接收连接的线程数。 在多 CPU 机器上增加这个值，虽然你永远不会真正需要超过 2 个。
     * 另外，有很多非保持活动连接，你可能也想增加这个值。 默认值为 1。
     */
    protected int acceptorThreadCount = 1;

    /**
     * NO-OP.
     *
     * @param acceptorThreadCount Unused
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public void setAcceptorThreadCount(int acceptorThreadCount) {}

    /**
     * Always returns 1.
     *
     * @return Always 1.
     *
     * @deprecated Will be removed in Tomcat 10.
     */
    @Deprecated
    public int getAcceptorThreadCount() { return 1; }


    /**
     * Priority of the acceptor threads.
     * 接收者线程的优先级。 用于接收新连接的线程。
     * 5（java.lang.Thread.NORM_PRIORITY 常量的值）。
     * 有关此优先级含义的更多详细信息，请参阅 java.lang.Thread 类的 JavaDoc。
     */
    protected int acceptorThreadPriority = Thread.NORM_PRIORITY;

    public void setAcceptorThreadPriority(int acceptorThreadPriority) {
        this.acceptorThreadPriority = acceptorThreadPriority;
    }
    public int getAcceptorThreadPriority() { return acceptorThreadPriority; }


    /**
     * 8及之前的版本中，这个最大连接数是10000，即同一时间，tomcat可以接收的最大连接数，超过这个数，Acceptor将会阻塞
     * 多余的请求会加入到AQS队列里等待
     */
    private int maxConnections = 8*1024;

    public void setMaxConnections(int maxCon) {
        this.maxConnections = maxCon;
        LimitLatch latch = this.connectionLimitLatch;
        if (latch != null) {
            // Update the latch that enforces this
            if (maxCon == -1) {
                releaseConnectionLatch();
            } else {
                latch.setLimit(maxCon);
            }
        } else if (maxCon > 0) {
            initializeConnectionLatch();
        }
    }
    public int getMaxConnections() { return this.maxConnections; }

    /**
     * Return the current count of connections handled by this endpoint, if the
     * connections are counted (which happens when the maximum count of
     * connections is limited), or <code>-1</code> if they are not. This
     * property is added here so that this value can be inspected through JMX.
     * It is visible on "ThreadPool" MBean.
     *
     * <p>The count is incremented by the Acceptor before it tries to accept a
     * new connection. Until the limit is reached and thus the count cannot be
     * incremented,  this value is more by 1 (the count of acceptors) than the
     * actual count of connections that are being served.
     *
     * @return The count
     */
    public long getConnectionCount() {
        LimitLatch latch = connectionLimitLatch;
        if (latch != null) {
            return latch.getCount();
        }
        return -1;
    }

    /**
     * External Executor based thread pool.
     * 对 Executor 元素中名称的引用。 如果设置了此属性，并且指定的执行程序存在，
     * 则连接器将使用执行程序，并且将忽略所有其他线程属性。 请注意，如果未为连接器指定共享执行器，则连接器将使用私有的内部执行器来提供线程池。
     */
    private Executor executor = null;


    public void setExecutor(Executor executor) {
        this.executor = executor;
        this.internalExecutor = (executor == null);
    }
    public Executor getExecutor() { return executor; }


    /**
     * External Executor based thread pool for utility tasks.
     */
    private ScheduledExecutorService utilityExecutor = null;
    public void setUtilityExecutor(ScheduledExecutorService utilityExecutor) {
        this.utilityExecutor = utilityExecutor;
    }
    public ScheduledExecutorService getUtilityExecutor() {
        if (utilityExecutor == null) {
            getLog().warn(sm.getString("endpoint.warn.noUtilityExecutor"));
            utilityExecutor = new ScheduledThreadPoolExecutor(1);
        }
        return utilityExecutor;
    }


    /**
     * Server socket port.
     */
    private int port = -1;
    public int getPort() { return port; }
    public void setPort(int port ) {
        this.port=port;
    }


    private int portOffset = 0;
    public int getPortOffset() { return portOffset; }
    public void setPortOffset(int portOffset ) {
        if (portOffset < 0) {
            throw new IllegalArgumentException(
                    sm.getString("endpoint.portOffset.invalid", Integer.valueOf(portOffset)));
        }
        this.portOffset = portOffset;
    }


    public int getPortWithOffset() {
        // Zero is a special case and negative values are invalid
        int port = getPort();
        if (port > 0) {
            return port + getPortOffset();
        }
        return port;
    }


    public final int getLocalPort() {
        try {
            InetSocketAddress localAddress = getLocalAddress();
            if (localAddress == null) {
                return -1;
            }
            return localAddress.getPort();
        } catch (IOException ioe) {
            return -1;
        }
    }


    /**
     * Address for the server socket.
     */
    private InetAddress address;
    public InetAddress getAddress() { return address; }
    public void setAddress(InetAddress address) { this.address = address; }


    /**
     * Obtain the network address the server socket is bound to. This primarily
     * exists to enable the correct address to be used when unlocking the server
     * socket since it removes the guess-work involved if no address is
     * specifically set.
     *
     * @return The network address that the server socket is listening on or
     *         null if the server socket is not currently bound.
     *
     * @throws IOException If there is a problem determining the currently bound
     *                     socket
     */
    protected abstract InetSocketAddress getLocalAddress() throws IOException;


    /**
     * Allows the server developer to specify the acceptCount (backlog) that
     * should be used for server sockets. By default, this value
     * is 100.
     *
     * 当请求连接数达到 maxConnections 时，操作系统为传入连接请求提供的Accept队列的最大长度。
     * 操作系统可能会忽略此设置并为队列使用不同的大小。
     * 当此Accept队列已满时，操作系统可能会主动拒绝其他连接，或者这些连接可能会超时。 默认值为 100。
     */
    private int acceptCount = 100;

    public void setAcceptCount(int acceptCount) { if (acceptCount > 0) {
        this.acceptCount = acceptCount;
    } }
    public int getAcceptCount() { return acceptCount; }

    /**
     * Controls when the Endpoint binds the port. <code>true</code>, the default
     * binds the port on {@link #init()} and unbinds it on {@link #destroy()}.
     * If set to <code>false</code> the port is bound on {@link #start()} and
     * unbound on {@link #stop()}
     * 控制连接器使用的套接字何时绑定。 默认情况下，它在连接器启动时绑定，
     * 在连接器销毁时解除绑定。 如果设置为 false，则连接器启动时将绑定套接字，并在连接器停止时解除绑定。
     */
    private boolean bindOnInit = true;

    public boolean getBindOnInit() { return bindOnInit; }
    public void setBindOnInit(boolean b) { this.bindOnInit = b; }
    private volatile BindState bindState = BindState.UNBOUND;
    protected BindState getBindState() {
        return bindState;
    }

    /**
     * Keepalive timeout, if not set the soTimeout is used.
     * 此连接器在关闭连接之前等待另一个 HTTP 请求的毫秒数。 默认值是使用已为 connectionTimeout 属性设置的值。 使用值 -1 表示没有（即无限）超时。
     */
    private Integer keepAliveTimeout = null;


    public int getKeepAliveTimeout() {
        if (keepAliveTimeout == null) {
            return getConnectionTimeout();
        } else {
            return keepAliveTimeout.intValue();
        }
    }
    public void setKeepAliveTimeout(int keepAliveTimeout) {
        this.keepAliveTimeout = Integer.valueOf(keepAliveTimeout);
    }


    /**
     * Socket TCP no delay.
     *
     * @return The current TCP no delay setting for sockets created by this
     *         endpoint
     */
    public boolean getTcpNoDelay() { return socketProperties.getTcpNoDelay();}
    public void setTcpNoDelay(boolean tcpNoDelay) { socketProperties.setTcpNoDelay(tcpNoDelay); }


    /**
     * Socket linger.
     *
     * @return The current socket linger time for sockets created by this
     *         endpoint
     */
    public int getConnectionLinger() { return socketProperties.getSoLingerTime(); }
    public void setConnectionLinger(int connectionLinger) {
        socketProperties.setSoLingerTime(connectionLinger);
        socketProperties.setSoLingerOn(connectionLinger>=0);
    }


    /**
     * Socket timeout.
     *
     * @return The current socket timeout for sockets created by this endpoint
     */
    public int getConnectionTimeout() { return socketProperties.getSoTimeout(); }
    public void setConnectionTimeout(int soTimeout) { socketProperties.setSoTimeout(soTimeout); }

    /**
     * SSL engine.
     * 使用此属性在连接器上启用 SSL 流量。
     * 要在连接器上打开 SSL 握手/加密/解密，请将此值设置为 true。
     * 默认值为false。 将此值设为 true 时，您还需要设置方案和安全属性，
     * 以将正确的 request.getScheme() 和 request.isSecure() 值传递给 servlet，请参阅 SSL 支持以获取更多信息。
     */
    private boolean SSLEnabled = false;

    public boolean isSSLEnabled() { return SSLEnabled; }
    public void setSSLEnabled(boolean SSLEnabled) { this.SSLEnabled = SSLEnabled; }

    /**
     * Identifies if the endpoint supports ALPN. Note that a return value of
     * <code>true</code> implies that {@link #isSSLEnabled()} will also return
     * <code>true</code>.
     *
     * @return <code>true</code> if the endpoint supports ALPN in its current
     *         configuration, otherwise <code>false</code>.
     */
    public abstract boolean isAlpnSupported();

    /**
     * 最小备用线程，即线程池一上来先new出来10个
     * 最小线程数始终保持运行。 这包括活动线程和空闲线程。 如果未指定，则使用默认值 10。
     * 如果执行器与此连接器相关联，则忽略此属性，因为连接器将使用执行器而不是内部线程池执行任务。
     * 请注意，如果配置了执行程序，则为此属性设置的任何值都将被正确记录，但会报告（例如，通过 JMX）为 -1，以明确未使用它。
     */
    private int minSpareThreads = 10;

    public void setMinSpareThreads(int minSpareThreads) {
        this.minSpareThreads = minSpareThreads;
        Executor executor = this.executor;
        if (internalExecutor && executor instanceof java.util.concurrent.ThreadPoolExecutor) {
            // The internal executor should always be an instance of
            // j.u.c.ThreadPoolExecutor but it may be null if the endpoint is
            // not running.
            // This check also avoids various threading issues.
            ((java.util.concurrent.ThreadPoolExecutor) executor).setCorePoolSize(minSpareThreads);
        }
    }
    public int getMinSpareThreads() {
        return Math.min(getMinSpareThreadsInternal(), getMaxThreads());
    }
    private int getMinSpareThreadsInternal() {
        if (internalExecutor) {
            return minSpareThreads;
        } else {
            return -1;
        }
    }


    /**
     * Maximum amount of worker threads. 最大线程数默认200，这个基于服务器情况可以多开
     * 一般来说，不是设置的越大越好的。最大线程数 = 核心线程数 + 新开的非核心线程数
     * 每一次HTTP请求到达Web服务，tomcat都会创建一个线程来处理该请求，
     * 那么最大线程数决定了Web服务容器可以同时处理多少个请求。maxThreads默认200，
     * 肯定建议增加。但是，增加线程是有成本的，更多的线程，不仅仅会带来更多的线程上下文切换成本，
     * 而且意味着带来更多的内存消耗。JVM中默认情况下在创建新线程时会分配大小为1M的线程栈，所以，
     * 更多的线程异味着需要更多的内存。线程数的经验值为：1核2g内存为200，线程数经验值200；4核8g内存，线程数经验值800。
     */
    private int maxThreads = 200;
    public void setMaxThreads(int maxThreads) {
        this.maxThreads = maxThreads;
        Executor executor = this.executor;
        if (internalExecutor && executor instanceof java.util.concurrent.ThreadPoolExecutor) {
            // The internal executor should always be an instance of
            // j.u.c.ThreadPoolExecutor but it may be null if the endpoint is
            // not running.
            // This check also avoids various threading issues.
            ((java.util.concurrent.ThreadPoolExecutor) executor).setMaximumPoolSize(maxThreads);
        }
    }
    public int getMaxThreads() {
        if (internalExecutor) {
            return maxThreads;
        } else {
            return -1;
        }
    }


    /**
     * Priority of the worker threads.
     */
    protected int threadPriority = Thread.NORM_PRIORITY;
    public void setThreadPriority(int threadPriority) {
        // Can't change this once the executor has started
        this.threadPriority = threadPriority;
    }
    public int getThreadPriority() {
        if (internalExecutor) {
            return threadPriority;
        } else {
            return -1;
        }
    }


    /**
     * Max keep alive requests
     * 允许最大的长连接请求的数量
     */
    private int maxKeepAliveRequests=100; // as in Apache HTTPD server
    public int getMaxKeepAliveRequests() {
        // Disable keep-alive if the server socket is not bound
        if (bindState.isBound()) {
            return maxKeepAliveRequests;
        } else {
            return 1;
        }
    }
    public void setMaxKeepAliveRequests(int maxKeepAliveRequests) {
        this.maxKeepAliveRequests = maxKeepAliveRequests;
    }


    /**
     * Name of the thread pool, which will be used for naming child threads.
     */
    private String name = "TP";
    public void setName(String name) { this.name = name; }
    public String getName() { return name; }


    /**
     * Name of domain to use for JMX registration.
     */
    private String domain;
    public void setDomain(String domain) { this.domain = domain; }
    public String getDomain() { return domain; }


    /**
     * The default is true - the created threads will be
     *  in daemon mode. If set to false, the control thread
     *  will not be daemon - and will keep the process alive.
     */
    private boolean daemon = true;
    public void setDaemon(boolean b) { daemon = b; }
    public boolean getDaemon() { return daemon; }


    /**
     * Expose asynchronous IO capability.
     */
    private boolean useAsyncIO = true;
    public void setUseAsyncIO(boolean useAsyncIO) { this.useAsyncIO = useAsyncIO; }
    public boolean getUseAsyncIO() { return useAsyncIO; }


    protected abstract boolean getDeferAccept();


    /**
     * The default behavior is to identify connectors uniquely with address
     * and port. However, certain connectors are not using that and need
     * some other identifier, which then can be used as a replacement.
     * @return the id
     */
    public String getId() {
        return null;
    }


    protected final List<String> negotiableProtocols = new ArrayList<>();
    public void addNegotiatedProtocol(String negotiableProtocol) {
        negotiableProtocols.add(negotiableProtocol);
    }
    public boolean hasNegotiableProtocols() {
        return (negotiableProtocols.size() > 0);
    }


    /**
     * Handling of accepted sockets.
     * 处理接受的套接字。
     */
    private Handler<S> handler = null;
    public void setHandler(Handler<S> handler ) {
        this.handler = handler;
    }
    public Handler<S> getHandler() { return handler; }


    /**
     * Attributes provide a way for configuration to be passed to sub-components
     * without the {@link org.apache.coyote.ProtocolHandler} being aware of the
     * properties available on those sub-components.
     */
    protected HashMap<String, Object> attributes = new HashMap<>();

    /**
     * Generic property setter called when a property for which a specific
     * setter already exists within the
     * {@link org.apache.coyote.ProtocolHandler} needs to be made available to
     * sub-components. The specific setter will call this method to populate the
     * attributes.
     *
     * @param name  Name of property to set
     * @param value The value to set the property to
     */
    public void setAttribute(String name, Object value) {
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.setAttribute", name, value));
        }
        attributes.put(name, value);
    }
    /**
     * Used by sub-components to retrieve configuration information.
     *
     * @param key The name of the property for which the value should be
     *            retrieved
     *
     * @return The value of the specified property
     */
    public Object getAttribute(String key) {
        Object value = attributes.get(key);
        if (getLog().isTraceEnabled()) {
            getLog().trace(sm.getString("endpoint.getAttribute", key, value));
        }
        return value;
    }



    public boolean setProperty(String name, String value) {
        setAttribute(name, value);
        final String socketName = "socket.";
        try {
            if (name.startsWith(socketName)) {
                return IntrospectionUtils.setProperty(socketProperties, name.substring(socketName.length()), value);
            } else {
                return IntrospectionUtils.setProperty(this,name,value,false);
            }
        }catch ( Exception x ) {
            getLog().error(sm.getString("endpoint.setAttributeError", name, value), x);
            return false;
        }
    }
    public String getProperty(String name) {
        String value = (String) getAttribute(name);
        final String socketName = "socket.";
        if (value == null && name.startsWith(socketName)) {
            Object result = IntrospectionUtils.getProperty(socketProperties, name.substring(socketName.length()));
            if (result != null) {
                value = result.toString();
            }
        }
        return value;
    }

    /**
     * Return the amount of threads that are managed by the pool.
     *
     * @return the amount of threads that are managed by the pool
     */
    public int getCurrentThreadCount() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getPoolSize();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getPoolSize();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    /**
     * Return the amount of threads that are in use
     *
     * @return the amount of threads that are in use
     */
    public int getCurrentThreadsBusy() {
        Executor executor = this.executor;
        if (executor != null) {
            if (executor instanceof ThreadPoolExecutor) {
                return ((ThreadPoolExecutor) executor).getActiveCount();
            } else if (executor instanceof ResizableExecutor) {
                return ((ResizableExecutor) executor).getActiveCount();
            } else {
                return -1;
            }
        } else {
            return -2;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPaused() {
        return paused;
    }


    /**使用tomcat内部自定义的线程池*/
    public void createExecutor() {
        internalExecutor = true;
        /**默认是无界的阻塞队列，当然我们也可以成有界的，减少内存消耗*/
        TaskQueue taskqueue = new TaskQueue();
        TaskThreadFactory tf = new TaskThreadFactory(getName() + "-exec-", daemon, getThreadPriority());
        /***
         * int corePoolSize -> getMinSpareThreads,最小备用线程，默认10个，我们配置成100个以防止连接高峰
         * int maximumPoolSize, -> 最大线程，默认200个，建议稍微设置大一些，但不是设置的越大越好，500-1000范围
         * long keepAliveTime,1分钟，存活时间，默认1分钟，非核心线程空闲超过这个时间会被回收
         * TimeUnit unit,单位秒
         * BlockingQueue<Runnable> workQueue, 阻塞任务队列
         * ThreadFactory threadFactory
         * Tomcat对jdk的线程池进行了改造(org.apache.tomcat.util.threads.ThreadPoolExecutor)
         * 1、当线程池没有达到最大执行线程的时候，会优先开线程再使用任务队列；
         * 2、任务执行失败时不会直接抛出错误，而是装回队列里再次尝试执行；(TaskQueue中的force方法)
         * 3、扩展计数用于追踪任务的执行情况；
         * 4、将线程池融入 Catalina 的生命周期组件中。
         */
        executor = new ThreadPoolExecutor(getMinSpareThreads(), getMaxThreads(), 60, TimeUnit.SECONDS,taskqueue, tf);
        taskqueue.setParent( (ThreadPoolExecutor) executor);
    }

    public void shutdownExecutor() {
        Executor executor = this.executor;
        if (executor != null && internalExecutor) {
            this.executor = null;
            if (executor instanceof ThreadPoolExecutor) {
                //this is our internal one, so we need to shut it down
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) executor;
                tpe.shutdownNow();
                long timeout = getExecutorTerminationTimeoutMillis();
                if (timeout > 0) {
                    try {
                        tpe.awaitTermination(timeout, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                    if (tpe.isTerminating()) {
                        getLog().warn(sm.getString("endpoint.warn.executorShutdown", getName()));
                    }
                }
                TaskQueue queue = (TaskQueue) tpe.getQueue();
                queue.setParent(null);
            }
        }
    }

    /**
     * Unlock the server socket acceptor threads using bogus connections.
     */
    protected void unlockAccept() {
        // Only try to unlock the acceptor if it is necessary
        if (acceptor == null || acceptor.getState() != AcceptorState.RUNNING) {
            return;
        }

        InetSocketAddress unlockAddress = null;
        InetSocketAddress localAddress = null;
        try {
            localAddress = getLocalAddress();
        } catch (IOException ioe) {
            getLog().debug(sm.getString("endpoint.debug.unlock.localFail", getName()), ioe);
        }
        if (localAddress == null) {
            getLog().warn(sm.getString("endpoint.debug.unlock.localNone", getName()));
            return;
        }

        try {
            unlockAddress = getUnlockAddress(localAddress);

            try (java.net.Socket s = new java.net.Socket()) {
                int stmo = 2 * 1000;
                int utmo = 2 * 1000;
                if (getSocketProperties().getSoTimeout() > stmo) {
                    stmo = getSocketProperties().getSoTimeout();
                }
                if (getSocketProperties().getUnlockTimeout() > utmo) {
                    utmo = getSocketProperties().getUnlockTimeout();
                }
                s.setSoTimeout(stmo);
                s.setSoLinger(getSocketProperties().getSoLingerOn(),getSocketProperties().getSoLingerTime());
                if (getLog().isDebugEnabled()) {
                    getLog().debug("About to unlock socket for:" + unlockAddress);
                }
                s.connect(unlockAddress,utmo);
                if (getDeferAccept()) {
                    /*
                     * In the case of a deferred accept / accept filters we need to
                     * send data to wake up the accept. Send OPTIONS * to bypass
                     * even BSD accept filters. The Acceptor will discard it.
                     */
                    OutputStreamWriter sw;

                    sw = new OutputStreamWriter(s.getOutputStream(), "ISO-8859-1");
                    sw.write("OPTIONS * HTTP/1.0\r\n" +
                            "User-Agent: Tomcat wakeup connection\r\n\r\n");
                    sw.flush();
                }
                if (getLog().isDebugEnabled()) {
                    getLog().debug("Socket unlock completed for:" + unlockAddress);
                }
            }
            // Wait for upto 1000ms acceptor threads to unlock
            long waitLeft = 1000;
            while (waitLeft > 0 &&
                    acceptor.getState() == AcceptorState.RUNNING) {
                Thread.sleep(5);
                waitLeft -= 5;
            }
        } catch(Throwable t) {
            ExceptionUtils.handleThrowable(t);
            if (getLog().isDebugEnabled()) {
                getLog().debug(sm.getString(
                        "endpoint.debug.unlock.fail", String.valueOf(getPortWithOffset())), t);
            }
        }
    }


    private static InetSocketAddress getUnlockAddress(InetSocketAddress localAddress) throws SocketException {
        if (localAddress.getAddress().isAnyLocalAddress()) {
            // Need a local address of the same type (IPv4 or IPV6) as the
            // configured bind address since the connector may be configured
            // to not map between types.
            InetAddress loopbackUnlockAddress = null;
            InetAddress linkLocalUnlockAddress = null;

            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();
                    if (localAddress.getAddress().getClass().isAssignableFrom(inetAddress.getClass())) {
                        if (inetAddress.isLoopbackAddress()) {
                            if (loopbackUnlockAddress == null) {
                                loopbackUnlockAddress = inetAddress;
                            }
                        } else if (inetAddress.isLinkLocalAddress()) {
                            if (linkLocalUnlockAddress == null) {
                                linkLocalUnlockAddress = inetAddress;
                            }
                        } else {
                            // Use a non-link local, non-loop back address by default
                            return new InetSocketAddress(inetAddress, localAddress.getPort());
                        }
                    }
                }
            }
            // Prefer loop back over link local since on some platforms (e.g.
            // OSX) some link local addresses are not included when listening on
            // all local addresses.
            if (loopbackUnlockAddress != null) {
                return new InetSocketAddress(loopbackUnlockAddress, localAddress.getPort());
            }
            if (linkLocalUnlockAddress != null) {
                return new InetSocketAddress(linkLocalUnlockAddress, localAddress.getPort());
            }
            // Fallback
            return new InetSocketAddress("localhost", localAddress.getPort());
        } else {
            return localAddress;
        }
    }


    // ---------------------------------------------- Request processing methods

    /**
     * Process the given SocketWrapper with the given status. Used to trigger
     * processing as if the Poller (for those endpoints that have one)
     * selected the socket.
     * 处理具有给定状态的给定 SocketWrapper。 用于触发处理，就好像轮询器（对于具有一个端点的那些端点）选择了套接字一样
     * @param socketWrapper The socket wrapper to process 要处理的套接字包装器
     * @param event         The socket event to be processed 要处理的套接字事件
     * @param dispatch      Should the processing be performed on a new container thread
     *
     * @return if processing was triggered successfully
     */
    public boolean processSocket(SocketWrapperBase<S> socketWrapper,SocketEvent event, boolean dispatch) {
        try {
            if (socketWrapper == null) {
                return false;
            }
            SocketProcessorBase<S> sc = null;
            if (processorCache != null) {
                sc = processorCache.pop();
            }
            if (sc == null) {
                /**创建一个套接字的处理器SocketProcessor（就是一个线程，用来处理请求的，这个需要扔给外部的线程池来执行）*/
                sc = createSocketProcessor(socketWrapper, event);
                if (!(this instanceof AprEndpoint)){
                    System.out.println("------》 创建了一个SocketProcessor -- "+((NioEndpoint.SocketProcessor)sc));
                }
            } else {
                sc.reset(socketWrapper, event);
            }
            /**取出线程池（tomcat内部自定义的），执行SocketProcessor*/
            Executor executor = getExecutor();
            /**如果dispatch等于true且线程池不为空，就把当前的socket交给线程池来处理*/
            if (dispatch && executor != null) {
                /**交给线程池执行*/
                executor.execute(sc);
            } else {
                /**否则就主线程执行*/
                sc.run();
            }
        } catch (RejectedExecutionException ree) {
            getLog().warn(sm.getString("endpoint.executor.fail", socketWrapper) , ree);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            getLog().error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    protected abstract SocketProcessorBase<S> createSocketProcessor(
            SocketWrapperBase<S> socketWrapper, SocketEvent event);


    // ------------------------------------------------------- Lifecycle methods

    /*
     * NOTE: There is no maintenance of state or checking for valid transitions
     * within this class other than ensuring that bind/unbind are called in the
     * right place. It is expected that the calling code will maintain state and
     * prevent invalid state transitions.
     */

    public abstract void bind() throws Exception;
    public abstract void unbind() throws Exception;
    public abstract void startInternal() throws Exception;
    public abstract void stopInternal() throws Exception;


    private void bindWithCleanup() throws Exception {
        try {
            bind(); /**初始化服务端套接字，绑定端口,子类实现，典型的模板方法*/
        } catch (Throwable t) {
            // Ensure open sockets etc. are cleaned up if something goes
            // wrong during bind
            ExceptionUtils.handleThrowable(t);
            unbind();
            throw t;
        }
    }


    /**模板方法设计模式中定义算法步骤的入口方法必须是final，以防止子类破坏*/
    public final void init() throws Exception {
        System.out.println("======> AbstractEndPoint init.");
        if (bindOnInit) {
            /**很关键的方法*/
            bindWithCleanup();
            bindState = BindState.BOUND_ON_INIT;
        }
        if (this.domain != null) {
            // Register endpoint (as ThreadPool - historical name)
            oname = new ObjectName(domain + ":type=ThreadPool,name=\"" + getName() + "\"");
            Registry.getRegistry(null, null).registerComponent(this, oname, null);

            ObjectName socketPropertiesOname = new ObjectName(domain +
                    ":type=SocketProperties,name=\"" + getName() + "\"");
            socketProperties.setObjectName(socketPropertiesOname);
            Registry.getRegistry(null, null).registerComponent(socketProperties, socketPropertiesOname, null);

            for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
                registerJmx(sslHostConfig);
            }
        }
    }


    private void registerJmx(SSLHostConfig sslHostConfig) {
        if (domain == null) {
            // Before init the domain is null
            return;
        }
        ObjectName sslOname = null;
        try {
            sslOname = new ObjectName(domain + ":type=SSLHostConfig,ThreadPool=\"" +
                    getName() + "\",name=" + ObjectName.quote(sslHostConfig.getHostName()));
            sslHostConfig.setObjectName(sslOname);
            try {
                Registry.getRegistry(null, null).registerComponent(sslHostConfig, sslOname, null);
            } catch (Exception e) {
                getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslOname), e);
            }
        } catch (MalformedObjectNameException e) {
            getLog().warn(sm.getString("endpoint.invalidJmxNameSslHost",
                    sslHostConfig.getHostName()), e);
        }

        for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
            ObjectName sslCertOname = null;
            try {
                sslCertOname = new ObjectName(domain +
                        ":type=SSLHostConfigCertificate,ThreadPool=\"" + getName() +
                        "\",Host=" + ObjectName.quote(sslHostConfig.getHostName()) +
                        ",name=" + sslHostConfigCert.getType());
                sslHostConfigCert.setObjectName(sslCertOname);
                try {
                    Registry.getRegistry(null, null).registerComponent(
                            sslHostConfigCert, sslCertOname, null);
                } catch (Exception e) {
                    getLog().warn(sm.getString("endpoint.jmxRegistrationFailed", sslCertOname), e);
                }
            } catch (MalformedObjectNameException e) {
                getLog().warn(sm.getString("endpoint.invalidJmxNameSslHostCert",
                        sslHostConfig.getHostName(), sslHostConfigCert.getType()), e);
            }
        }
    }


    private void unregisterJmx(SSLHostConfig sslHostConfig) {
        Registry registry = Registry.getRegistry(null, null);
        registry.unregisterComponent(sslHostConfig.getObjectName());
        for (SSLHostConfigCertificate sslHostConfigCert : sslHostConfig.getCertificates()) {
            registry.unregisterComponent(sslHostConfigCert.getObjectName());
        }
    }


    public final void start() throws Exception {
        System.out.println("======> AbstractEndpoint start.");
        if (bindState == BindState.UNBOUND) { // 如果没有绑定，则先绑定，因为在init方法执行时已经绑定过了，所以直接startInternal
            bindWithCleanup();
            bindState = BindState.BOUND_ON_START;
        }
        startInternal();
    }


    protected void startAcceptorThread() {
        /**从这里可以看出，acceptor也是一个Runnable*/
        acceptor = new Acceptor<>(this);
        String threadName = getName() + "-Acceptor";
        acceptor.setThreadName(threadName);
        Thread t = new Thread(acceptor, threadName);
        /**设置优先级*/
        t.setPriority(getAcceptorThreadPriority());
        /**Acceptor还是一个守护线程，在tomcat接收到shutdown命令后，不会等其他线程处理完请求在关闭，而是直接关闭*/
        t.setDaemon(getDaemon());
        t.start();
        System.out.println("======> Nio Socket Port - "+getPort()+"连接器启动，等待接收请求....");
    }


    /**
     * Pause the endpoint, which will stop it accepting new connections and
     * unlock the acceptor.
     */
    public void pause() {
        if (running && !paused) {
            paused = true;
            releaseConnectionLatch();
            unlockAccept();
            getHandler().pause();
        }
    }

    /**
     * Resume the endpoint, which will make it start accepting new connections
     * again.
     */
    public void resume() {
        if (running) {
            paused = false;
        }
    }

    public final void stop() throws Exception {
        stopInternal();
        if (bindState == BindState.BOUND_ON_START || bindState == BindState.SOCKET_CLOSED_ON_STOP) {
            unbind();
            bindState = BindState.UNBOUND;
        }
    }

    public final void destroy() throws Exception {
        if (bindState == BindState.BOUND_ON_INIT) {
            unbind();
            bindState = BindState.UNBOUND;
        }
        Registry registry = Registry.getRegistry(null, null);
        registry.unregisterComponent(oname);
        registry.unregisterComponent(socketProperties.getObjectName());
        for (SSLHostConfig sslHostConfig : findSslHostConfigs()) {
            unregisterJmx(sslHostConfig);
        }
    }


    protected abstract Log getLog();

    protected LimitLatch initializeConnectionLatch() {
        if (maxConnections==-1) {
            return null;
        }
        if (connectionLimitLatch==null) {
            connectionLimitLatch = new LimitLatch(getMaxConnections());
        }
        return connectionLimitLatch;
    }

    private void releaseConnectionLatch() {
        LimitLatch latch = connectionLimitLatch;
        if (latch!=null) {
            latch.releaseAll();
        }
        connectionLimitLatch = null;
    }

    protected void countUpOrAwaitConnection() throws InterruptedException {
        if (maxConnections==-1) {
            return;
        }
        LimitLatch latch = connectionLimitLatch;
        if (latch!=null) {
            latch.countUpOrAwait();
        }
    }

    protected long countDownConnection() {
        if (maxConnections==-1) {
            return -1;
        }
        LimitLatch latch = connectionLimitLatch;
        if (latch!=null) {
            long result = latch.countDown();
            if (result<0) {
                getLog().warn(sm.getString("endpoint.warn.incorrectConnectionCount"));
            }
            return result;
        } else {
            return -1;
        }
    }


    /**
     * Close the server socket (to prevent further connections) if the server
     * socket was originally bound on {@link #start()} (rather than on
     * {@link #init()}).
     *
     * @see #getBindOnInit()
     */
    public final void closeServerSocketGraceful() {
        if (bindState == BindState.BOUND_ON_START) {
            // Stop accepting new connections
            acceptor.stop(-1);
            // Release locks that may be preventing the acceptor from stopping
            releaseConnectionLatch();
            unlockAccept();
            // Signal to any multiplexed protocols (HTTP/2) that they may wish
            // to stop accepting new streams
            getHandler().pause();
            // Update the bindState. This has the side-effect of disabling
            // keep-alive for any in-progress connections
            bindState = BindState.SOCKET_CLOSED_ON_STOP;
            try {
                doCloseServerSocket();
            } catch (IOException ioe) {
                getLog().warn(sm.getString("endpoint.serverSocket.closeFailed", getName()), ioe);
            }
        }
    }


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
    public final long awaitConnectionsClose(long waitMillis) {
        while (waitMillis > 0 && !connections.isEmpty()) {
            try {
                Thread.sleep(50);
                waitMillis -= 50;
            } catch (InterruptedException e) {
                Thread.interrupted();
                waitMillis = 0;
            }
        }
        return waitMillis;
    }


    /**
     * Actually close the server socket but don't perform any other clean-up.
     *
     * @throws IOException If an error occurs closing the socket
     */
    protected abstract void doCloseServerSocket() throws IOException;

    protected abstract U serverSocketAccept() throws Exception;

    protected abstract boolean setSocketOptions(U socket);

    /**
     * Close the socket when the connection has to be immediately closed when
     * an error occurs while configuring the accepted socket or trying to
     * dispatch it for processing. The wrapper associated with the socket will
     * be used for the close.
     * @param socket The newly accepted socket
     */
    protected void closeSocket(U socket) {
        SocketWrapperBase<S> socketWrapper = connections.get(socket);
        if (socketWrapper != null) {
            socketWrapper.close();
        }
    }

    /**
     * Close the socket. This is used when the connector is not in a state
     * which allows processing the socket, or if there was an error which
     * prevented the allocation of the socket wrapper.
     * @param socket The newly accepted socket
     */
    protected abstract void destroySocket(U socket);
}

