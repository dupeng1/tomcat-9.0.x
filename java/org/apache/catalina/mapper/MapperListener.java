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
package org.apache.catalina.mapper;

import org.apache.catalina.*;
import org.apache.catalina.util.LifecycleMBeanBase;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

import java.util.ArrayList;
import java.util.List;


/**
 * Mapper listener.
 * 实现了ContainerListener与 LifecycleListener接口，监听tomcat组件的变化，
 * 当有Host，Context及Wrapper变更时，调用Mapper相关方法，增加或者删除Host，Context，Wrapper等。
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class MapperListener extends LifecycleMBeanBase implements ContainerListener, LifecycleListener {

    private static final Log log = LogFactory.getLog(MapperListener.class);


    // ----------------------------------------------------- Instance Variables
    /**
     * Associated mapper. 关联一个mapper，StandardService传递过来的
     */
    private final Mapper mapper;

    /**
     * Associated service 关联一个StandardService
     */
    private final Service service;


    /**
     * The string manager for this package.
     * 异常日志处理&国际化
     */
    private static final StringManager sm = StringManager.getManager(Constants.Package);

    /**
     * The domain (effectively the engine) this mapper is associated with
     */
    private final String domain = null;


    // ----------------------------------------------------------- Constructors

    /**
     * Create mapper listener.
     *
     * @param service The service this listener is associated with
     */
    public MapperListener(Service service) {
        this.service = service;
        /**从service组件中取*/
        this.mapper = service.getMapper();
    }


    // ------------------------------------------------------- Lifecycle Methods

    @Override
    public void startInternal() throws LifecycleException {

        setState(LifecycleState.STARTING);

        Engine engine = service.getContainer();
        if (engine == null) {
            return;
        }

        /**mapper对象设置默认主机名称*/
        findDefaultHost();
        /**
         * 递归为每一个容器对象添加mapperListener，
         * 这样所有的container的listener都指向了mapperListener
         * 好处就是只有一个mapper对象，可以把所有的映射全部保存下来（集中管理）
         */
        addListeners(engine);
        /**拿到servlet引擎的子容器数组，那不就是hosts吗*/
        Container[] conHosts = engine.findChildren();
        for (Container conHost : conHosts) {
            Host host = (Host) conHost;
            /**如果主机的状态不处于NEW，就往mapper中*/
            if (!LifecycleState.NEW.equals(host.getState())) {
                /**
                 * Registering the host will register the context and wrappers
                 * 注册应用和servlet的包装器，也是套娃调用
                 */
                registerHost(host);
            }
        }
    }


    @Override
    public void stopInternal() throws LifecycleException {
        setState(LifecycleState.STOPPING);

        Engine engine = service.getContainer();
        if (engine == null) {
            return;
        }
        removeListeners(engine);
    }


    @Override
    protected String getDomainInternal() {
        if (service instanceof LifecycleMBeanBase) {
            return service.getDomain();
        } else {
            return null;
        }
    }


    @Override
    protected String getObjectNameKeyProperties() {
        // Same as connector but Mapper rather than Connector
        return "type=Mapper";
    }

    // --------------------------------------------- Container Listener methods

    @Override
    public void containerEvent(ContainerEvent event) {

        if (Container.ADD_CHILD_EVENT.equals(event.getType())) {
            Container child = (Container) event.getData();
            addListeners(child);
            // If child is started then it is too late for life-cycle listener
            // to register the child so register it here
            if (child.getState().isAvailable()) {
                if (child instanceof Host) {
                    registerHost((Host) child);
                } else if (child instanceof Context) {
                    registerContext((Context) child);
                } else if (child instanceof Wrapper) {
                    // Only if the Context has started. If it has not, then it
                    // will have its own "after_start" life-cycle event later.
                    if (child.getParent().getState().isAvailable()) {
                        registerWrapper((Wrapper) child);
                    }
                }
            }
        } else if (Container.REMOVE_CHILD_EVENT.equals(event.getType())) {
            Container child = (Container) event.getData();
            removeListeners(child);
            // No need to unregister - life-cycle listener will handle this when
            // the child stops
        } else if (Host.ADD_ALIAS_EVENT.equals(event.getType())) {
            // Handle dynamically adding host aliases
            mapper.addHostAlias(((Host) event.getSource()).getName(),
                    event.getData().toString());
        } else if (Host.REMOVE_ALIAS_EVENT.equals(event.getType())) {
            // Handle dynamically removing host aliases
            mapper.removeHostAlias(event.getData().toString());
        } else if (Wrapper.ADD_MAPPING_EVENT.equals(event.getType())) {
            // Handle dynamically adding wrappers
            Wrapper wrapper = (Wrapper) event.getSource();
            Context context = (Context) wrapper.getParent();
            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }
            String version = context.getWebappVersion();
            String hostName = context.getParent().getName();
            String wrapperName = wrapper.getName();
            String mapping = (String) event.getData();
            boolean jspWildCard = ("jsp".equals(wrapperName) && mapping.endsWith("/*"));
            mapper.addWrapper(hostName, contextPath, version, mapping, wrapper,
                    jspWildCard, context.isResourceOnlyServlet(wrapperName));
        } else if (Wrapper.REMOVE_MAPPING_EVENT.equals(event.getType())) {
            // Handle dynamically removing wrappers
            Wrapper wrapper = (Wrapper) event.getSource();

            Context context = (Context) wrapper.getParent();
            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }
            String version = context.getWebappVersion();
            String hostName = context.getParent().getName();

            String mapping = (String) event.getData();

            mapper.removeWrapper(hostName, contextPath, version, mapping);
        } else if (Context.ADD_WELCOME_FILE_EVENT.equals(event.getType())) {
            // Handle dynamically adding welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            String welcomeFile = (String) event.getData();

            mapper.addWelcomeFile(hostName, contextPath,
                    context.getWebappVersion(), welcomeFile);
        } else if (Context.REMOVE_WELCOME_FILE_EVENT.equals(event.getType())) {
            // Handle dynamically removing welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            String welcomeFile = (String) event.getData();

            mapper.removeWelcomeFile(hostName, contextPath,
                    context.getWebappVersion(), welcomeFile);
        } else if (Context.CLEAR_WELCOME_FILES_EVENT.equals(event.getType())) {
            // Handle dynamically clearing welcome files
            Context context = (Context) event.getSource();

            String hostName = context.getParent().getName();

            String contextPath = context.getPath();
            if ("/".equals(contextPath)) {
                contextPath = "";
            }

            mapper.clearWelcomeFiles(hostName, contextPath,
                    context.getWebappVersion());
        }
    }


    // ------------------------------------------------------ Protected Methods

    private void findDefaultHost() {

        Engine engine = service.getContainer();
        String defaultHost = engine.getDefaultHost();

        boolean found = false;

        if (defaultHost != null && defaultHost.length() > 0) {
            Container[] containers = engine.findChildren();

            for (Container container : containers) {
                Host host = (Host) container;
                if (defaultHost.equalsIgnoreCase(host.getName())) {
                    found = true;
                    break;
                }

                String[] aliases = host.findAliases();
                for (String alias : aliases) {
                    if (defaultHost.equalsIgnoreCase(alias)) {
                        found = true;
                        break;
                    }
                }
            }
        }

        if (found) {
            mapper.setDefaultHostName(defaultHost);
        } else {
            log.error(sm.getString("mapperListener.unknownDefaultHost", defaultHost, service));
        }
    }


    /**
     * Register host. 注册虚拟主机
     */
    private void registerHost(Host host) {

        String[] aliases = host.findAliases();
        /**先添加主机信息*/
        mapper.addHost(host.getName(), aliases, host);

        for (Container container : host.findChildren()) {
            if (container.getState().isAvailable()) {
                /**再注册应用*/
                registerContext((Context) container);
            }
        }

        // Default host may have changed
        findDefaultHost();

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerHost",
                    host.getName(), domain, service));
        }
    }


    /**
     * Unregister host.
     */
    private void unregisterHost(Host host) {

        String hostname = host.getName();

        mapper.removeHost(hostname);

        // Default host may have changed
        findDefaultHost();

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.unregisterHost", hostname,
                    domain, service));
        }
    }


    /**
     * Unregister wrapper.
     */
    private void unregisterWrapper(Wrapper wrapper) {

        Context context = ((Context) wrapper.getParent());
        String contextPath = context.getPath();
        String wrapperName = wrapper.getName();

        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String version = context.getWebappVersion();
        String hostName = context.getParent().getName();

        String[] mappings = wrapper.findMappings();

        for (String mapping : mappings) {
            mapper.removeWrapper(hostName, contextPath, version,  mapping);
        }

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.unregisterWrapper",
                    wrapperName, contextPath, service));
        }
    }


    /**
     * Register context. 注册应用
     */
    private void registerContext(Context context) {

        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        Host host = (Host)context.getParent();

        /**web应用根路径下的资源对象*/
        WebResourceRoot resources = context.getResources();
        String[] welcomeFiles = context.findWelcomeFiles();
        /**一个应用（context）下所包含的wrappers对应的mapping映射，下文会进行填充*/
        List<WrapperMappingInfo> wrappers = new ArrayList<>();

        /**
         * 这里需要强调下，一个应用最起码也得有2个servlet（一个servlet对应一个wrapper），
         * 也就是如果你放个静态的应用（比如：index1.jsp、index.html等），它也是有如下两个servlet的
         * {@link org.apache.catalina.servlets.DefaultServlet} 和 {@link org.apache.jasper.servlet.JspServlet}
         * 这两个是在 conf/web.xml 中 配置的，也就是所有应用都会添加这两个，而且这两个servlet都是有mapping配置的
         * 其中DefaultServlet的mapping是 "/"
         * 其中JspServlet的mapping默认是 "*.jsp" 和 “*.jspx”
         */
        Container[] childWrappers = context.findChildren();
        System.out.println(String.format("应用【%s】下面共有 %d 个 wrapper容器对象",
            context.getPath().equals("") ? "ROOT" : context.getPath(),childWrappers.length));
        /**遍历应用下面的所有wrapper容器*/
        for (Container container : childWrappers) {
            /**填充wrappers映射*/
            prepareWrapperMappingInfo(context, (Wrapper) container, wrappers);
            if(log.isDebugEnabled()) {
                log.debug(sm.getString("mapperListener.registerWrapper",
                        container.getName(), contextPath, service));
            }
        }

        /**往mapper对象中添加contextVersion对象*/
        mapper.addContextVersion(host.getName(), host, contextPath,
                context.getWebappVersion(), context, welcomeFiles, resources,
                wrappers);

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerContext",
                    contextPath, service));
        }
    }


    /**
     * Unregister context.
     */
    private void unregisterContext(Context context) {

        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String hostName = context.getParent().getName();

        if (context.getPaused()) {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("mapperListener.pauseContext",
                        contextPath, service));
            }

            mapper.pauseContextVersion(context, hostName, contextPath,
                    context.getWebappVersion());
        } else {
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("mapperListener.unregisterContext",
                        contextPath, service));
            }

            mapper.removeContextVersion(context, hostName, contextPath,
                    context.getWebappVersion());
        }
    }


    /**
     * Register wrapper. 注册wrapper
     */
    private void registerWrapper(Wrapper wrapper) {

        Context context = (Context) wrapper.getParent();
        String contextPath = context.getPath();
        if ("/".equals(contextPath)) {
            contextPath = "";
        }
        String version = context.getWebappVersion();
        String hostName = context.getParent().getName();

        List<WrapperMappingInfo> wrappers = new ArrayList<>();
        prepareWrapperMappingInfo(context, wrapper, wrappers);
        mapper.addWrappers(hostName, contextPath, version, wrappers);

        if(log.isDebugEnabled()) {
            log.debug(sm.getString("mapperListener.registerWrapper",
                    wrapper.getName(), contextPath, service));
        }
    }

    /**
     * Populate <code>wrappers</code> list with information for registration of
     * mappings for this wrapper in this context.
     *
     */
    private void prepareWrapperMappingInfo(Context context, Wrapper wrapper,List<WrapperMappingInfo> wrappers) {
        String wrapperName = wrapper.getName();
        boolean resourceOnly = context.isResourceOnlyServlet(wrapperName);
        String[] mappings = wrapper.findMappings();
        for (String mapping : mappings) {
            /**jsp通配符 （首先，wrapper的名称必须等于jsp，其次mapping必须以 /* 结尾，jsp通配符才会等于true）*/
            boolean jspWildCard = (wrapperName.equals("jsp") && mapping.endsWith("/*"));
            wrappers.add(new WrapperMappingInfo(mapping, wrapper, jspWildCard,resourceOnly));
        }
    }

    @Override
    public void lifecycleEvent(LifecycleEvent event) {
        if (event.getType().equals(Lifecycle.AFTER_START_EVENT)) {
            Object obj = event.getSource();
            if (obj instanceof Wrapper) {
                Wrapper w = (Wrapper) obj;
                // Only if the Context has started. If it has not, then it will
                // have its own "after_start" event later.
                if (w.getParent().getState().isAvailable()) {
                    registerWrapper(w);
                }
            } else if (obj instanceof Context) {
                Context c = (Context) obj;
                // Only if the Host has started. If it has not, then it will
                // have its own "after_start" event later.
                if (c.getParent().getState().isAvailable()) {
                    registerContext(c);
                }
            } else if (obj instanceof Host) {
                registerHost((Host) obj);
            }
        } else if (event.getType().equals(Lifecycle.BEFORE_STOP_EVENT)) {
            Object obj = event.getSource();
            if (obj instanceof Wrapper) {
                unregisterWrapper((Wrapper) obj);
            } else if (obj instanceof Context) {
                unregisterContext((Context) obj);
            } else if (obj instanceof Host) {
                unregisterHost((Host) obj);
            }
        }
    }


    /**
     * Add this mapper to the container and all child containers
     * 将mapperListener实例递归添加到每一个容器（包括容器的子容器）中
     * @param container
     */
    private void addListeners(Container container) {
        /**添加容器监听器 {@link ContainerListener}*/
        container.addContainerListener(this);
        /**添加生命周期监听器 {@link LifecycleListener}*/
        container.addLifecycleListener(this);
        for (Container child : container.findChildren()) {
            addListeners(child);
        }
    }


    /**
     * Remove this mapper from the container and all child containers
     * 将MapperListener从所有容器对象中的容器/生命周期监听器列表中移除
     * @param container
     */
    private void removeListeners(Container container) {
        container.removeContainerListener(this);
        container.removeLifecycleListener(this);
        for (Container child : container.findChildren()) {
            removeListeners(child);
        }
    }
}
