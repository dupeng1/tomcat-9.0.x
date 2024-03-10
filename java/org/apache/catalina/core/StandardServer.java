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
package org.apache.catalina.core;

import org.apache.catalina.*;
import org.apache.catalina.deploy.NamingResourcesImpl;
import org.apache.catalina.mbeans.MBeanFactory;
import org.apache.catalina.startup.Catalina;
import org.apache.catalina.util.ExtensionValidator;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.catalina.util.ServerInfo;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.StringCache;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;
import org.apache.tomcat.util.threads.TaskThreadFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.security.AccessControlException;
import java.util.Random;
import java.util.concurrent.*;


/**
 * Standard implementation of the <b>Server</b> interface, available for use
 * (but not required) when deploying and starting Catalina.
 *
 * @author Craig R. McClanahan
 */
public final class StandardServer extends LifecycleMBeanBase implements Server {

    private static final Log log = LogFactory.getLog(StandardServer.class);
    private static final StringManager sm = StringManager.getManager(StandardServer.class);


    // ------------------------------------------------------------ Constructor

    /**
     * Construct a default instance of this class.
     */
    public StandardServer() {

        super();

        globalNamingResources = new NamingResourcesImpl();
        globalNamingResources.setContainer(this);

        if (isUseNaming()) {
            namingContextListener = new NamingContextListener();
            addLifecycleListener(namingContextListener);
        } else {
            namingContextListener = null;
        }

    }


    // ----------------------------------------------------- Instance Variables


    /**
     * Global naming resources context.
     */
    private javax.naming.Context globalNamingContext = null;


    /**
     * Global naming resources.
     */
    private NamingResourcesImpl globalNamingResources = null;


    /**
     * The naming context listener for this web application.
     */
    private final NamingContextListener namingContextListener;


    /**
     * The port number on which we wait for shutdown commands.  监听关闭命令的端口
     */
    private int port = 8005;

    private int portOffset = 0;

    /**
     * The address on which we wait for shutdown commands.
     */
    private String address = "localhost";


    /**
     * A random number generator that is <strong>only</strong> used if
     * the shutdown command string is longer than 1024 characters.
     */
    private Random random = null;


    /**
     * The set of Services associated with this Server.
     * Service是包含Connector和Container的集合，Service用适当的Connector接收用户的请求，
     * 再发给相应的Container来处理。
     *
     * 1、一个Server{@link StandardServer}包含多个Service
     * 2、一个Service{@link StandardService}包含多个Connector{@link org.apache.catalina.connector.Connector}
     * 3、一个Service还包含一个Engine{@link StandardEngine}
     * 4、一个Engine包含多个Host{@link StandardHost}
     */
    private Service[] services = new Service[0];
    private final Object servicesLock = new Object();


    /**
     * The shutdown command string we are looking for.
     */
    private String shutdown = "SHUTDOWN";


    /**
     * The property change support for this component.
     */
    final PropertyChangeSupport support = new PropertyChangeSupport(this);

    private volatile boolean stopAwait = false;

    private Catalina catalina = null;

    private ClassLoader parentClassLoader = null;

    /**
     * Thread that currently is inside our await() method.
     */
    private volatile Thread awaitThread = null;

    /**
     * Server socket that is used to wait for the shutdown command.
     * 用于等待关闭命令的服务器套接字。
     */
    private volatile ServerSocket awaitSocket = null;

    private File catalinaHome = null;

    private File catalinaBase = null;

    private final Object namingToken = new Object();

    /**
     * The number of threads available to process utility tasks in this service.
     * 用于处理此服务中的实用工具任务的可用线程数。
     */
    protected int utilityThreads = 2;

    /**
     * The utility threads daemon flag.
     */
    protected boolean utilityThreadsAsDaemon = false;

    /**
     * Utility executor with scheduling capabilities.
     * 定时器（线程池），执行当前Server组件的周期性任务,调试了下源码，这个有没有都无所谓，暂时是这样的
     */
    private ScheduledThreadPoolExecutor utilityExecutor = null;

    /**
     * Utility executor wrapper.
     */
    private ScheduledExecutorService utilityExecutorWrapper = null;


    /**
     * Controller for the periodic lifecycle event.
     */
    private ScheduledFuture<?> periodicLifecycleEventFuture = null;
    private ScheduledFuture<?> monitorFuture;


    /**
     * The lifecycle event period in seconds. 生命周期事件周期，以秒为单位。
     */
    protected int periodicEventDelay = 10;


    // ------------------------------------------------------------- Properties

    @Override
    public Object getNamingToken() {
        return namingToken;
    }


    /**
     * Return the global naming resources context.
     */
    @Override
    public javax.naming.Context getGlobalNamingContext() {
        return this.globalNamingContext;
    }


    /**
     * Set the global naming resources context.
     *
     * @param globalNamingContext The new global naming resource context
     */
    public void setGlobalNamingContext(javax.naming.Context globalNamingContext) {
        this.globalNamingContext = globalNamingContext;
    }


    /**
     * Return the global naming resources.
     */
    @Override
    public NamingResourcesImpl getGlobalNamingResources() {
        return this.globalNamingResources;
    }


    /**
     * Set the global naming resources.
     *
     * @param globalNamingResources The new global naming resources
     */
    @Override
    public void setGlobalNamingResources
        (NamingResourcesImpl globalNamingResources) {

        NamingResourcesImpl oldGlobalNamingResources =
            this.globalNamingResources;
        this.globalNamingResources = globalNamingResources;
        this.globalNamingResources.setContainer(this);
        support.firePropertyChange("globalNamingResources",
                                   oldGlobalNamingResources,
                                   this.globalNamingResources);

    }


    /**
     * Report the current Tomcat Server Release number
     * @return Tomcat release identifier
     */
    public String getServerInfo() {
        return ServerInfo.getServerInfo();
    }


    /**
     * Return the current server built timestamp
     * @return server built timestamp.
     */
    public String getServerBuilt() {
        return ServerInfo.getServerBuilt();
    }


    /**
     * Return the current server's version number.
     * @return server's version number.
     */
    public String getServerNumber() {
        return ServerInfo.getServerNumber();
    }


    /**
     * Return the port number we listen to for shutdown commands.
     */
    @Override
    public int getPort() {
        return this.port;
    }


    /**
     * Set the port number we listen to for shutdown commands.
     *
     * @param port The new port number
     */
    @Override
    public void setPort(int port) {
        this.port = port;
    }


    @Override
    public int getPortOffset() {
        return portOffset;
    }


    @Override
    public void setPortOffset(int portOffset) {
        if (portOffset < 0) {
            throw new IllegalArgumentException(
                    sm.getString("standardServer.portOffset.invalid", Integer.valueOf(portOffset)));
        }
        this.portOffset = portOffset;
    }


    @Override
    public int getPortWithOffset() {
        // Non-positive port values have special meanings and the offset should
        // not apply.
        int port = getPort();
        if (port > 0) {
            return port + getPortOffset();
        } else {
            return port;
        }
    }


    /**
     * Return the address on which we listen to for shutdown commands.
     */
    @Override
    public String getAddress() {
        return this.address;
    }


    /**
     * Set the address on which we listen to for shutdown commands.
     *
     * @param address The new address
     */
    @Override
    public void setAddress(String address) {
        this.address = address;
    }

    /**
     * Return the shutdown command string we are waiting for.
     */
    @Override
    public String getShutdown() {
        return this.shutdown;
    }


    /**
     * Set the shutdown command we are waiting for.
     *
     * @param shutdown The new shutdown command
     */
    @Override
    public void setShutdown(String shutdown) {
        this.shutdown = shutdown;
    }


    /**
     * Return the outer Catalina startup/shutdown component if present.
     */
    @Override
    public Catalina getCatalina() {
        return catalina;
    }


    /**
     * Set the outer Catalina startup/shutdown component if present.
     */
    @Override
    public void setCatalina(Catalina catalina) {
        this.catalina = catalina;
    }


    @Override
    public int getUtilityThreads() {
        return utilityThreads;
    }


    /**
     * Handles the special values.
     */
    private static int getUtilityThreadsInternal(int utilityThreads) {
        int result = utilityThreads;
        if (result <= 0) {
            result = Runtime.getRuntime().availableProcessors() + result;
            if (result < 2) {
                result = 2;
            }
        }
        return result;
    }


    @Override
    public void setUtilityThreads(int utilityThreads) {
        // Use local copies to ensure thread safety
        int oldUtilityThreads = this.utilityThreads;
        if (getUtilityThreadsInternal(utilityThreads) < getUtilityThreadsInternal(oldUtilityThreads)) {
            return;
        }
        this.utilityThreads = utilityThreads;
        if (oldUtilityThreads != utilityThreads && utilityExecutor != null) {
            reconfigureUtilityExecutor(getUtilityThreadsInternal(utilityThreads));
        }
    }


    private synchronized void reconfigureUtilityExecutor(int threads) {
        // The ScheduledThreadPoolExecutor doesn't use MaximumPoolSize, only CorePoolSize is available
        if (utilityExecutor != null) {
            utilityExecutor.setCorePoolSize(threads);
        } else {
            ScheduledThreadPoolExecutor scheduledThreadPoolExecutor =
                    new ScheduledThreadPoolExecutor(threads,
                            new TaskThreadFactory("Catalina-utility-", utilityThreadsAsDaemon, Thread.MIN_PRIORITY));
            scheduledThreadPoolExecutor.setKeepAliveTime(10, TimeUnit.SECONDS);
            scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
            scheduledThreadPoolExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            utilityExecutor = scheduledThreadPoolExecutor;
            /**tomcat自己实现另一个定时线程池，其对jdk的包装了一下*/
            utilityExecutorWrapper = new org.apache.tomcat.util.threads.ScheduledThreadPoolExecutor(utilityExecutor);
        }
    }


    /**
     * Get if the utility threads are daemon threads.
     * @return the threads daemon flag
     */
    public boolean getUtilityThreadsAsDaemon() {
        return utilityThreadsAsDaemon;
    }


    /**
     * Set the utility threads daemon flag. The default value is true.
     * @param utilityThreadsAsDaemon the new thread daemon flag
     */
    public void setUtilityThreadsAsDaemon(boolean utilityThreadsAsDaemon) {
        this.utilityThreadsAsDaemon = utilityThreadsAsDaemon;
    }


    /**
     * @return The period between two lifecycle events, in seconds
     */
    public final int getPeriodicEventDelay() {
        return periodicEventDelay;
    }


    /**
     * Set the new period between two lifecycle events in seconds.
     * @param periodicEventDelay The period in seconds, negative or zero will
     *  disable events
     */
    public final void setPeriodicEventDelay(int periodicEventDelay) {
        this.periodicEventDelay = periodicEventDelay;
    }


    // --------------------------------------------------------- Server Methods


    /**
     * Add a new Service to the set of defined Services.
     *
     * @param service The Service to be added
     */
    @Override
    public void addService(Service service) {

        service.setServer(this);

        synchronized (servicesLock) {
            Service results[] = new Service[services.length + 1];
            System.arraycopy(services, 0, results, 0, services.length);
            results[services.length] = service;
            services = results;

            if (getState().isAvailable()) {
                try {
                    service.start();
                } catch (LifecycleException e) {
                    // Ignore
                }
            }

            // Report this property change to interested listeners
            support.firePropertyChange("service", null, service);
        }

    }

    public void stopAwait() {
        stopAwait=true;
        Thread t = awaitThread;
        if (t != null) {
            ServerSocket s = awaitSocket;
            if (s != null) {
                awaitSocket = null;
                try {
                    s.close();
                } catch (IOException e) {
                    // Ignored
                }
            }
            t.interrupt();
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                // Ignored
            }
        }
    }

    /**
     * Wait until a proper shutdown command is received, then return.
     * This keeps the main thread alive - the thread pool listening for http
     * connections is daemon threads.
     * 等待，直到收到正确的关机命令，然后返回。这使主线程保持活跃-线程池监听http连接是守护线程。
     */
    @Override
    public void await() {
        // Negative values - don't wait on port - tomcat is embedded or we just don't like ports
        if (getPortWithOffset() == -2) {
            // undocumented yet - for embedding apps that are around, alive.
            return;
        }
        /**如果外界设置*/
        if (getPortWithOffset() == -1) {
            try {
                awaitThread = Thread.currentThread();
                while(!stopAwait) {
                    try {
                        Thread.sleep( 10000 );
                    } catch( InterruptedException ex ) {
                        // continue and check the flag
                    }
                }
            } finally {
                awaitThread = null;
            }
            return;
        }

        // Set up a server socket to wait on
        try {
            /**默认监听服务器关闭命令的套接字端口号为：8005 （getPortWithOffset的值）*/
            awaitSocket = new ServerSocket(getPortWithOffset(), 1,InetAddress.getByName(address));
        } catch (IOException e) {
            log.error(sm.getString("standardServer.awaitSocket.fail", address,
                    String.valueOf(getPortWithOffset()), String.valueOf(getPort()),
                    String.valueOf(getPortOffset())), e);
            return;
        }

        try {
            awaitThread = Thread.currentThread();

            // Loop waiting for a connection and a valid command
            while (!stopAwait) {
                ServerSocket serverSocket = awaitSocket;
                if (serverSocket == null) {
                    break;
                }

                // Wait for the next connection
                Socket socket = null;
                StringBuilder command = new StringBuilder();
                try {
                    InputStream stream;
                    long acceptStartTime = System.currentTimeMillis();
                    try {
                        /**Tomcat服务器启动后，server组件会一直阻塞在这里，直到接收到shutdown命令*/
                        socket = serverSocket.accept();
                        socket.setSoTimeout(10 * 1000);  // Ten seconds
                        stream = socket.getInputStream();
                    } catch (SocketTimeoutException ste) {
                        // This should never happen but bug 56684 suggests that
                        // it does.
                        log.warn(sm.getString("standardServer.accept.timeout",
                                Long.valueOf(System.currentTimeMillis() - acceptStartTime)), ste);
                        continue;
                    } catch (AccessControlException ace) {
                        log.warn(sm.getString("standardServer.accept.security"), ace);
                        continue;
                    } catch (IOException e) {
                        if (stopAwait) {
                            // Wait was aborted with socket.close()
                            break;
                        }
                        log.error(sm.getString("standardServer.accept.error"), e);
                        break;
                    }

                    // Read a set of characters from the socket
                    int expected = 1024; // Cut off to avoid DoS attack
                    while (expected < shutdown.length()) {
                        if (random == null) {
                            random = new Random();
                        }
                        expected += (random.nextInt() % 1024);
                    }
                    while (expected > 0) {
                        int ch = -1;
                        try {
                            ch = stream.read();
                        } catch (IOException e) {
                            log.warn(sm.getString("standardServer.accept.readError"), e);
                            ch = -1;
                        }
                        // Control character or EOF (-1) terminates loop
                        if (ch < 32 || ch == 127) {
                            break;
                        }
                        command.append((char) ch);
                        expected--;
                    }
                } finally {
                    // Close the socket now that we are done with it
                    try {
                        if (socket != null) {
                            socket.close();
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                }

                // Match against our command string
                boolean match = command.toString().equals(shutdown);
                if (match) {
                    log.info(sm.getString("standardServer.shutdownViaPort"));
                    break;
                } else {
                    log.warn(sm.getString("standardServer.invalidShutdownCommand", command.toString()));
                }
            }
        } finally {
            ServerSocket serverSocket = awaitSocket;
            awaitThread = null;
            awaitSocket = null;

            // Close the server socket and return
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }


    /**
     * @return the specified Service (if it exists); otherwise return
     * <code>null</code>.
     *
     * @param name Name of the Service to be returned
     */
    @Override
    public Service findService(String name) {
        if (name == null) {
            return null;
        }
        synchronized (servicesLock) {
            for (Service service : services) {
                if (name.equals(service.getName())) {
                    return service;
                }
            }
        }
        return null;
    }


    /**
     * @return the set of Services defined within this Server.
     */
    @Override
    public Service[] findServices() {
        return services;
    }

    /**
     * @return the JMX service names.
     */
    public ObjectName[] getServiceNames() {
        ObjectName onames[]=new ObjectName[ services.length ];
        for( int i=0; i<services.length; i++ ) {
            onames[i]=((StandardService)services[i]).getObjectName();
        }
        return onames;
    }


    /**
     * Remove the specified Service from the set associated from this
     * Server.
     *
     * @param service The Service to be removed
     */
    @Override
    public void removeService(Service service) {

        synchronized (servicesLock) {
            int j = -1;
            for (int i = 0; i < services.length; i++) {
                if (service == services[i]) {
                    j = i;
                    break;
                }
            }
            if (j < 0) {
                return;
            }
            try {
                services[j].stop();
            } catch (LifecycleException e) {
                // Ignore
            }
            int k = 0;
            Service results[] = new Service[services.length - 1];
            for (int i = 0; i < services.length; i++) {
                if (i != j) {
                    results[k++] = services[i];
                }
            }
            services = results;

            // Report this property change to interested listeners
            support.firePropertyChange("service", service, null);
        }

    }


    @Override
    public File getCatalinaBase() {
        if (catalinaBase != null) {
            return catalinaBase;
        }

        catalinaBase = getCatalinaHome();
        return catalinaBase;
    }


    @Override
    public void setCatalinaBase(File catalinaBase) {
        this.catalinaBase = catalinaBase;
    }


    @Override
    public File getCatalinaHome() {
        return catalinaHome;
    }


    @Override
    public void setCatalinaHome(File catalinaHome) {
        this.catalinaHome = catalinaHome;
    }


    // --------------------------------------------------------- Public Methods

    /**
     * Add a property change listener to this component.
     *
     * @param listener The listener to add
     */
    public void addPropertyChangeListener(PropertyChangeListener listener) {

        support.addPropertyChangeListener(listener);

    }


    /**
     * Remove a property change listener from this component.
     *
     * @param listener The listener to remove
     */
    public void removePropertyChangeListener(PropertyChangeListener listener) {

        support.removePropertyChangeListener(listener);

    }


    /**
     * Return a String representation of this component.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("StandardServer[");
        sb.append(getPort());
        sb.append(']');
        return sb.toString();
    }


    /**
     * Write the configuration information for this entire <code>Server</code>
     * out to the server.xml configuration file.
     *
     * @exception InstanceNotFoundException
     *            if the managed resource object cannot be found
     * @exception MBeanException
     *            if the initializer of the object throws an exception, or
     *            persistence is not supported
     * @exception javax.management.RuntimeOperationsException
     *            if an exception is reported by the persistence mechanism
     */
    public synchronized void storeConfig() throws InstanceNotFoundException, MBeanException {
        try {
            // Note: Hard-coded domain used since this object is per Server/JVM
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            MBeanServer server = Registry.getRegistry(null, null).getMBeanServer();
            if (server.isRegistered(sname)) {
                server.invoke(sname, "storeConfig", null, null);
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardServer.storeConfig.error"), t);
        }
    }


    /**
     * Write the configuration information for <code>Context</code>
     * out to the specified configuration file.
     *
     * @param context the context which should save its configuration
     * @exception InstanceNotFoundException
     *            if the managed resource object cannot be found
     * @exception MBeanException
     *            if the initializer of the object throws an exception
     *            or persistence is not supported
     * @exception javax.management.RuntimeOperationsException
     *            if an exception is reported by the persistence mechanism
     */
    public synchronized void storeContext(Context context) throws InstanceNotFoundException, MBeanException {
        try {
            // Note: Hard-coded domain used since this object is per Server/JVM
            ObjectName sname = new ObjectName("Catalina:type=StoreConfig");
            MBeanServer server = Registry.getRegistry(null, null).getMBeanServer();
            if (server.isRegistered(sname)) {
                server.invoke(sname, "store",
                    new Object[] {context},
                    new String [] { "java.lang.String"});
            } else {
                log.error(sm.getString("standardServer.storeConfig.notAvailable", sname));
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("standardServer.storeConfig.contextError", context.getName()), t);
        }
    }


    /**
     * @return <code>true</code> if naming should be used.
     */
    private boolean isUseNaming() {
        boolean useNaming = true;
        // Reading the "catalina.useNaming" environment variable
        String useNamingProperty = System.getProperty("catalina.useNaming");
        if ((useNamingProperty != null)
            && (useNamingProperty.equals("false"))) {
            useNaming = false;
        }
        return useNaming;
    }


    /**
     * Start nested components ({@link Service}s) and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected void startInternal() throws LifecycleException {
        System.out.println("======> Server start.");
        /**调用父类{@link org.apache.catalina.util.LifecycleBase#fireLifecycleEvent(String, Object)}*/
        fireLifecycleEvent(CONFIGURE_START_EVENT, null);
        /**二话不说，上来先设置状态，宣告自己已经处于Staring*/
        setState(LifecycleState.STARTING);

        globalNamingResources.start();

        // Start our defined Services
        synchronized (servicesLock) {
            for (Service service : services) {
                service.start();
            }
        }

        if (periodicEventDelay > 0) {
            monitorFuture = getUtilityExecutor().scheduleWithFixedDelay(
                    () -> startPeriodicLifecycleEvent(), 0, 60, TimeUnit.SECONDS);
        }
    }


    protected void startPeriodicLifecycleEvent() {
        if (periodicLifecycleEventFuture == null || (periodicLifecycleEventFuture != null && periodicLifecycleEventFuture.isDone())) {
            if (periodicLifecycleEventFuture != null && periodicLifecycleEventFuture.isDone()) {
                // There was an error executing the scheduled task, get it and log it
                try {
                    periodicLifecycleEventFuture.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.error(sm.getString("standardServer.periodicEventError"), e);
                }
            }
            periodicLifecycleEventFuture = getUtilityExecutor().scheduleAtFixedRate(
                    () -> fireLifecycleEvent(Lifecycle.PERIODIC_EVENT, null), periodicEventDelay, periodicEventDelay, TimeUnit.SECONDS);
        }
    }


    /**
     * Stop nested components ({@link Service}s) and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that needs to be reported
     */
    @Override
    protected void stopInternal() throws LifecycleException {

        setState(LifecycleState.STOPPING);

        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
        if (periodicLifecycleEventFuture != null) {
            periodicLifecycleEventFuture.cancel(false);
            periodicLifecycleEventFuture = null;
        }

        fireLifecycleEvent(CONFIGURE_STOP_EVENT, null);

        // Stop our defined Services
        for (Service service : services) {
            service.stop();
        }

        globalNamingResources.stop();

        stopAwait();
    }

    /**
     * Invoke a pre-startup initialization. This is used to allow connectors
     * to bind to restricted ports under Unix operating environments.
     * 调用启动前初始化。这用于允许连接器绑定到Unix操作环境下受限制的端口。
     */
    @Override
    protected void initInternal() throws LifecycleException {

        System.out.println("======> Server init.");
        super.initInternal();

        // Initialize utility executor
        reconfigureUtilityExecutor(getUtilityThreadsInternal(utilityThreads));
        register(utilityExecutor, "type=UtilityExecutor");

        // Register global String cache
        // Note although the cache is global, if there are multiple Servers
        // present in the JVM (may happen when embedding) then the same cache
        // will be registered under multiple names
        onameStringCache = register(new StringCache(), "type=StringCache");

        // Register the MBeanFactory
        MBeanFactory factory = new MBeanFactory();
        factory.setContainer(this);
        onameMBeanFactory = register(factory, "type=MBeanFactory");

        // Register the naming resources 将全局jndi服务注册到jmx容器中，托管给MBeanServer管理
        globalNamingResources.init();

        // Populate the extension validator with JARs from common and shared class loaders
        /***
         * 在Catalina#load时，已经设置过server的catalina实例了，所以下面会进入
         */
        if (getCatalina() != null) {
            /**
             * 这里的cl分两种情况
             * 1：如果我们在conf/catalina.properties中配置了shared.loader="${catalina.home}/shared/*.jar"
             *   那么，从catalina中取出的父类加载器就是sharedLoader，它不等于commonLoader
             * 2: 如果没有配置,sharedLoader等同于commonLoader
             */
            ClassLoader cl = getCatalina().getParentClassLoader();
            // Walk the class loader hierarchy. Stop at the system class loader.
            // This will add the shared (if present) and common class loaders
            /**
             * 验证tomcat公共的jar和应用间共享的jar包资源，判断是否含有manifest.mf清单文件，不包含的将会被过忽略掉
             * 如果不配置sharedLoader的路径的话，下面这个循环只会走一次，因为sharedLoader就是commonLoader
             * 而commonLoader的parent就是scl（系统加载器，即AppClassLoader）
             */
            while (cl != null && cl != ClassLoader.getSystemClassLoader()) {
                if (cl instanceof URLClassLoader) {
                    /**下面肯定会走的，因为cl不管是commonLoader也好还是sharedLoader也好，都是URLClassLoader的子类型*/
                    URL[] urls = ((URLClassLoader) cl).getURLs();
                    for (URL url : urls) {
                        /**
                         * 遍历资源url，这个是什么，取决于你catalina.properties文件中配置的
                         * common.loader = "${catalina.base}/lib","${catalina.base}/lib/*.jar","${catalina.home}/lib","${catalina.home}/lib/*.jar"
                         * server.loader =
                         * shared.loader =
                         *
                         * 需要注意的是，代码调试的时候，是没有lib目录的，tomcat自身所需的依赖全部来自于项目的pom文件
                         */
                        if (url.getProtocol().equals("file")) {
                            try {
                                File f = new File (url.toURI());
                                if (f.isFile() && f.getName().endsWith(".jar")) {
                                    /**
                                     * ExtensionValidator: 验证jar资源的，如果jar包中有manifest，则添加到containerManifestResources列表中
                                     * 这个类有个static代码块，会首先载入jre环境下的jar，也就是优先验证java.class.path下面的所有jar资源
                                     * Java打包文件(jar文件)中一般会包含清单文件(META-INF/MANIFEST.MF)，该文件能够包含主类以及加载类路径等信息。
                                     * 只要包含了正确的MANIFEST.MF，才能使用java -jar xxx.jar 直接调用执行
                                     * 通常我们不会自己打包jar，而是通过第三方工具，比如maven
                                     */
                                    ExtensionValidator.addSystemResource(f);
                                }
                            } catch (URISyntaxException | IOException e) {
                                // Ignore
                            }
                        }
                    }
                }
                cl = cl.getParent();
            }
        }
        /**
         * Initialize our defined Services
         * 初始化我们自定义的services，一个Server包含多个service
         */
        for (Service service : services) {
            service.init();
        }
    }

    @Override
    protected void destroyInternal() throws LifecycleException {
        // Destroy our defined Services
        for (Service service : services) {
            service.destroy();
        }

        globalNamingResources.destroy();

        unregister(onameMBeanFactory);

        unregister(onameStringCache);

        if (utilityExecutor != null) {
            utilityExecutor.shutdownNow();
            unregister("type=UtilityExecutor");
            utilityExecutor = null;
        }

        super.destroyInternal();
    }

    /**
     * Return the parent class loader for this component.
     */
    @Override
    public ClassLoader getParentClassLoader() {
        if (parentClassLoader != null) {
            return parentClassLoader;
        }
        if (catalina != null) {
            return catalina.getParentClassLoader();
        }
        return ClassLoader.getSystemClassLoader();
    }

    /**
     * Set the parent class loader for this server.
     *
     * @param parent The new parent class loader
     */
    @Override
    public void setParentClassLoader(ClassLoader parent) {
        ClassLoader oldParentClassLoader = this.parentClassLoader;
        this.parentClassLoader = parent;
        support.firePropertyChange("parentClassLoader", oldParentClassLoader,
                                   this.parentClassLoader);
    }


    private ObjectName onameStringCache;
    private ObjectName onameMBeanFactory;

    /**
     * Obtain the MBean domain for this server. The domain is obtained using
     * the following search order:
     * <ol>
     * <li>Name of first {@link org.apache.catalina.Engine}.</li>
     * <li>Name of first {@link Service}.</li>
     * </ol>
     */
    @Override
    protected String getDomainInternal() {

        String domain = null;

        Service[] services = findServices();
        if (services.length > 0) {
            Service service = services[0];
            if (service != null) {
                domain = service.getDomain();
            }
        }
        return domain;
    }


    @Override
    protected final String getObjectNameKeyProperties() {
        return "type=Server";
    }

    @Override
    public ScheduledExecutorService getUtilityExecutor() {
        return utilityExecutorWrapper;
    }
}
