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
package org.apache.catalina.startup;

import org.apache.catalina.*;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.security.DeployXmlPermission;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.IOTools;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.UriUtil;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;

import javax.management.ObjectName;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Startup event listener for a <b>Host</b> that configures the properties
 * of that Host, and the associated defined contexts.
 * Host的启动事件监听器，用于配置该主机的属性以及相关的已定义上下文。
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class HostConfig implements LifecycleListener {

    private static final Log log = LogFactory.getLog(HostConfig.class);

    /**
     * The string resources for this package.
     */
    protected static final StringManager sm = StringManager.getManager(HostConfig.class);

    /**
     * The resolution, in milliseconds, of file modification times.
     */
    protected static final long FILE_MODIFICATION_RESOLUTION_MS = 1000;


    // ----------------------------------------------------- Instance Variables

    /**
     * The Java class name of the Context implementation we should use.
     */
    protected String contextClass = "org.apache.catalina.core.StandardContext";


    /**
     * The Host we are associated with.
     */
    protected Host host = null;


    /**
     * The JMX ObjectName of this component.
     */
    protected ObjectName oname = null;


    /**
     * Should we deploy XML Context config files packaged with WAR files and
     * directories?
     * 我们是否应该部署与WAR文件和目录打包的XML上下文配置文件?
     */
    protected boolean deployXML = false;


    /**
     * Should XML files be copied to
     * $CATALINA_BASE/conf/<engine>/<host> by default when a web application is deployed?
     * 部署web应用程序时，[appname].XML文件是否默认复制到CATALINA_BASE/conf/[enginename]/[hostname]
     */
    protected boolean copyXML = false;


    /**
     * Should we unpack WAR files when auto-deploying applications in the
     * <code>appBase</code> directory?
     * 当自动部署<code>appBase<code>目录中的应用程序时，我们是否应该解包WAR文件?
     */
    protected boolean unpackWARs = false;


    /**
     * Map of deployed applications. 已部署应用程序的映射。
     */
    protected final Map<String, DeployedApplication> deployed =
            new ConcurrentHashMap<>();


    /**
     * List of applications which are being serviced, and shouldn't be
     * deployed/undeployed/redeployed at the moment.
     * @deprecated Unused. Will be removed in Tomcat 10.1.x onwards. Replaced
     *             by the private <code>servicedSet</code> field.
     * 这个将在10.1.x被废弃，新的请使用servicedSet
     */
    @Deprecated
    protected final ArrayList<String> serviced = new ArrayList<>();


    /**
     * Set of applications which are being serviced, and shouldn't be
     * deployed/undeployed/redeployed at the moment.
     * 正在被服务的应用程序集，目前不应该被重新部署。
     */
    private Set<String> servicedSet = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());

    /**
     * The <code>Digester</code> instance used to parse context descriptors.
     */
    protected Digester digester = createDigester(contextClass);
    private final Object digesterLock = new Object();

    /**
     * The list of Wars in the appBase to be ignored because they are invalid
     * (e.g. contain /../ sequences).
     */
    protected final Set<String> invalidWars = new HashSet<>();

    // ------------------------------------------------------------- Properties


    /**
     * @return the Context implementation class name.
     */
    public String getContextClass() {
        return this.contextClass;
    }


    /**
     * Set the Context implementation class name.
     * 设置Context实现类名。
     * @param contextClass The new Context implementation class name.
     */
    public void setContextClass(String contextClass) {

        String oldContextClass = this.contextClass;
        this.contextClass = contextClass;

        if (!oldContextClass.equals(contextClass)) {
            synchronized (digesterLock) {
                digester = createDigester(getContextClass());
            }
        }
    }


    /**
     * @return the deploy XML config file flag for this component.
     */
    public boolean isDeployXML() {
        return this.deployXML;
    }


    /**
     * Set the deploy XML config file flag for this component.
     *
     * @param deployXML The new deploy XML flag
     */
    public void setDeployXML(boolean deployXML) {
        this.deployXML = deployXML;
    }


    private boolean isDeployThisXML(File docBase, ContextName cn) {
        boolean deployThisXML = isDeployXML();
        if (Globals.IS_SECURITY_ENABLED && !deployThisXML) {
            // When running under a SecurityManager, deployXML may be overridden
            // on a per Context basis by the granting of a specific permission
            Policy currentPolicy = Policy.getPolicy();
            if (currentPolicy != null) {
                URL contextRootUrl;
                try {
                    contextRootUrl = docBase.toURI().toURL();
                    CodeSource cs = new CodeSource(contextRootUrl, (Certificate[]) null);
                    PermissionCollection pc = currentPolicy.getPermissions(cs);
                    Permission p = new DeployXmlPermission(cn.getBaseName());
                    if (pc.implies(p)) {
                        deployThisXML = true;
                    }
                } catch (MalformedURLException e) {
                    // Should never happen
                    log.warn(sm.getString("hostConfig.docBaseUrlInvalid"), e);
                }
            }
        }

        return deployThisXML;
    }


    /**
     * @return the copy XML config file flag for this component.
     */
    public boolean isCopyXML() {
        return this.copyXML;
    }


    /**
     * Set the copy XML config file flag for this component.
     *
     * @param copyXML The new copy XML flag
     */
    public void setCopyXML(boolean copyXML) {

        this.copyXML= copyXML;

    }


    /**
     * @return the unpack WARs flag.
     */
    public boolean isUnpackWARs() {
        return this.unpackWARs;
    }


    /**
     * Set the unpack WARs flag.
     *
     * @param unpackWARs The new unpack WARs flag
     */
    public void setUnpackWARs(boolean unpackWARs) {
        this.unpackWARs = unpackWARs;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Process the START event for an associated Host.
     *
     * @param event The lifecycle event that has occurred
     */
    @Override
    public void lifecycleEvent(LifecycleEvent event) {

        // Identify the host we are associated with
        try {
            host = (Host) event.getLifecycle();
            if (host instanceof StandardHost) { /**设置监听器参数*/
                setCopyXML(((StandardHost) host).isCopyXML());
                setDeployXML(((StandardHost) host).isDeployXML());
                setUnpackWARs(((StandardHost) host).isUnpackWARs());
                setContextClass(((StandardHost) host).getContextClass());
            }
        } catch (ClassCastException e) {
            log.error(sm.getString("hostConfig.cce", event.getLifecycle()), e);
            return;
        }

        // Process the event that has occurred
        if (event.getType().equals(Lifecycle.PERIODIC_EVENT)) {
            /**热部署*/
            check();
        } else if (event.getType().equals(Lifecycle.BEFORE_START_EVENT)) {
            beforeStart();
        } else if (event.getType().equals(Lifecycle.START_EVENT)) {
            start();
        } else if (event.getType().equals(Lifecycle.STOP_EVENT)) {
            stop();
        }
    }


    /**
     * Add a serviced application to the list and indicates if the application
     * was already present in the list.
     *
     * @param name the context name
     *
     * @return {@code true} if the application was not already in the list
     */
    public boolean tryAddServiced(String name) {
        if (servicedSet.add(name)) {
            synchronized (this) {
                serviced.add(name);
            }
            return true;
        }
        return false;
    }


    /**
     * Add a serviced application to the list if it is not already present. If
     * the application is already in the list of serviced applications this
     * method is a NO-OP.
     *
     * @param name the context name
     *
     * @deprecated Unused. This method will be removed in Tomcat 10.1.x onwards.
     *             Use {@link #tryAddServiced} instead.
     */
    @Deprecated
    public void addServiced(String name) {
        servicedSet.add(name);
        synchronized (this) {
            serviced.add(name);
        }
    }


    /**
     * Is application serviced ?
     *
     * @param name the context name
     *
     * @return state of the application
     *
     * @deprecated Unused. This method will be removed in Tomcat 10.1.x onwards.
     *             Use {@link #tryAddServiced} instead.
     */
    @Deprecated
    public boolean isServiced(String name) {
        return servicedSet.contains(name);
    }


    /**
     * Removed a serviced application from the list.
     * @param name the context name
     */
    public void removeServiced(String name) {
        servicedSet.remove(name);
        synchronized (this) {
            serviced.remove(name);
        }
    }


    /**
     * Get the instant where an application was deployed.
     * @param name the context name
     * @return 0L if no application with that name is deployed, or the instant
     *  on which the application was deployed
     */
    public long getDeploymentTime(String name) {
        DeployedApplication app = deployed.get(name);
        if (app == null) {
            return 0L;
        }

        return app.timestamp;
    }


    /**
     * Has the specified application been deployed? Note applications defined
     * in server.xml will not have been deployed.
     * @param name the context name
     * @return <code>true</code> if the application has been deployed and
     *  <code>false</code> if the application has not been deployed or does not
     *  exist
     */
    public boolean isDeployed(String name) {
        return deployed.containsKey(name);
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Create the digester which will be used to parse context config files.
     * @param contextClassName The class which will be used to create the
     *  context instance
     * @return the digester
     */
    protected static Digester createDigester(String contextClassName) {
        Digester digester = new Digester();
        digester.setValidating(false);
        // Add object creation rule
        digester.addObjectCreate("Context", contextClassName, "className");
        // Set the properties on that object (it doesn't matter if extra
        // properties are set)
        digester.addSetProperties("Context");
        return digester;
    }

    protected File returnCanonicalPath(String path) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(host.getCatalinaBase(), path);
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file;
        }
    }


    /**
     * Get the name of the configBase.
     * For use with JMX management.
     * @return the config base
     */
    public String getConfigBaseName() {
        return host.getConfigBaseFile().getAbsolutePath();
    }


    /**
     * Deploy applications for any directories or WAR files that are found
     * in our "application root" directory.
     * 为【应用程序根 ： appBase】目录中的任何目录或WAR文件部署相应的应用程序
     */
    protected void deployApps() {
        File appBase = host.getAppBaseFile();
        File configBase = host.getConfigBaseFile();
        String[] filteredAppPaths = filterAppPaths(appBase.list());

        /** 部署优先级顺序： xml > war > folder
         * Deploy XML descriptors from configBase （在Tomcat中，任何context都可以通过xml描述符来部署）
         * 按照描述文件方式部署，描述文件位置是$CATALINA_BASE/conf/[engineName]/[hostName]/[webappName].xml，
         * 会按照配置文件中的描述去创建Context，并添加资源信息
         */
        deployDescriptors(configBase, configBase.list());
        /**
         * 2、war部署（指定了应用必须以.war结尾而不是以.jar结尾，比如我们springboot编译后的war包
         *   直白点就是war仅仅作为一个工程项目来用，但是jar包不但可以作为一个独立的项目跑
         *   也可以作为其他应用的依赖部件，因此，如果.jar部署的话，
         *   tomcat是无法区分这个jar的用途究竟是独立的还是被其他应用引用的，所以才会引出一个war出来
         *   会首先尝试看是否已经有解压的app目录，如果过有用已解压的部署，否则会使用war包部署，
         *   会先尝试使用META-INF/context.xml来创建Context，如果没有这个文件则使用反射创建一个默认的Context。
         */
        deployWARs(appBase, filteredAppPaths);
        /**
         * 3、文件夹部署,可以看成deployWARs的简化版本，去除了检查使用war包部署的过程，其他流程一致。
         */
        deployDirectories(appBase, filteredAppPaths);
    }


    /**
     * Filter the list of application file paths to remove those that match
     * the regular expression defined by {@link Host#getDeployIgnore()}.
     * 过滤掉与正则表达式匹配的应用路径，余下的就是要部署的
     * @param unfilteredAppPaths    The list of application paths to filter
     *
     * @return  The filtered list of application paths
     */
    protected String[] filterAppPaths(String[] unfilteredAppPaths) {
        Pattern filter = host.getDeployIgnorePattern();
        if (filter == null || unfilteredAppPaths == null) {
            return unfilteredAppPaths;
        }

        List<String> filteredList = new ArrayList<>();
        Matcher matcher = null;
        for (String appPath : unfilteredAppPaths) {
            if (matcher == null) {
                matcher = filter.matcher(appPath);
            } else {
                matcher.reset(appPath);
            }
            if (matcher.matches()) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("hostConfig.ignorePath", appPath));
                }
            } else {
                filteredList.add(appPath);
            }
        }
        return filteredList.toArray(new String[0]);
    }


    /**
     * Deploy applications for any directories or WAR files that are found
     * in our "application root" directory.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param name The context name which should be deployed
     */
    protected void deployApps(String name) {

        File appBase = host.getAppBaseFile();
        File configBase = host.getConfigBaseFile();
        ContextName cn = new ContextName(name, false);
        String baseName = cn.getBaseName();

        if (deploymentExists(cn.getName())) {
            return;
        }

        // Deploy XML descriptor from configBase
        File xml = new File(configBase, baseName + ".xml");
        if (xml.exists()) {
            deployDescriptor(cn, xml);
            return;
        }
        // Deploy WAR
        File war = new File(appBase, baseName + ".war");
        if (war.exists()) {
            deployWAR(cn, war);
            return;
        }
        // Deploy expanded folder
        File dir = new File(appBase, baseName);
        if (dir.exists()) {
            deployDirectory(cn, dir);
        }
    }


    /**
     * Deploy XML context descriptors.
     * @param configBase The config base
     * @param files The XML descriptors which should be deployed
     */
    protected void deployDescriptors(File configBase, String[] files) {

        if (files == null) {
            return;
        }

        /**{@link InlineExecutorService}*/
        ExecutorService es = host.getStartStopExecutor();
        List<Future<?>> results = new ArrayList<>();

        for (String file : files) {
            File contextXml = new File(configBase, file);
            if (file.toLowerCase(Locale.ENGLISH).endsWith(".xml")) {
                ContextName cn = new ContextName(file, true);
                if (tryAddServiced(cn.getName())) {
                    try {
                        if (deploymentExists(cn.getName())) {
                            removeServiced(cn.getName());
                            continue;
                        }
                        // DeployDescriptor will call removeServiced
                        results.add(es.submit(new DeployDescriptor(this, cn, contextXml)));
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        removeServiced(cn.getName());
                        throw t;
                    }
                }
            }
        }

        for (Future<?> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.deployDescriptor.threaded.error"), e);
            }
        }
    }


    /**
     * Deploy specified context descriptor.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param cn The context name
     * @param contextXml The descriptor
     */
    @SuppressWarnings("null") // context is not null
    protected void deployDescriptor(ContextName cn, File contextXml) {

        DeployedApplication deployedApp = new DeployedApplication(cn.getName(), true);

        long startTime = 0;
        // Assume this is a configuration descriptor and deploy it
        if (log.isInfoEnabled()) {
           startTime = System.currentTimeMillis();
           log.info(sm.getString("hostConfig.deployDescriptor", contextXml.getAbsolutePath()));
        }

        Context context = null;
        boolean isExternalWar = false;
        boolean isExternal = false;
        File expandedDocBase = null;

        try (FileInputStream fis = new FileInputStream(contextXml)) {
            synchronized (digesterLock) {
                try {
                    context = (Context) digester.parse(fis);
                } catch (Exception e) {
                    log.error(sm.getString("hostConfig.deployDescriptor.error", contextXml.getAbsolutePath()), e);
                } finally {
                    digester.reset();
                    if (context == null) {
                        context = new FailedContext();
                    }
                }
            }

            if (context.getPath() != null) {
                log.warn(sm.getString("hostConfig.deployDescriptor.path", context.getPath(),
                        contextXml.getAbsolutePath()));
            }

            Class<?> clazz = Class.forName(host.getConfigClass());
            LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
            context.addLifecycleListener(listener);

            context.setConfigFile(contextXml.toURI().toURL());
            context.setName(cn.getName());
            context.setPath(cn.getPath());
            context.setWebappVersion(cn.getVersion());
            // Add the associated docBase to the redeployed list if it's a WAR
            if (context.getDocBase() != null) {
                File docBase = new File(context.getDocBase());
                if (!docBase.isAbsolute()) {
                    docBase = new File(host.getAppBaseFile(), context.getDocBase());
                }
                // If external docBase, register .xml as redeploy first
                if (!docBase.getCanonicalFile().toPath().startsWith(host.getAppBaseFile().toPath())) {
                    isExternal = true;
                    deployedApp.redeployResources.put(
                            contextXml.getAbsolutePath(), Long.valueOf(contextXml.lastModified()));
                    deployedApp.redeployResources.put(
                            docBase.getAbsolutePath(), Long.valueOf(docBase.lastModified()));
                    if (docBase.getAbsolutePath().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                        isExternalWar = true;
                    }
                    // Check that a WAR or DIR in the appBase is not 'hidden'
                    File war = new File(host.getAppBaseFile(), cn.getBaseName() + ".war");
                    if (war.exists()) {
                        log.warn(sm.getString("hostConfig.deployDescriptor.hiddenWar",
                                contextXml.getAbsolutePath(), war.getAbsolutePath()));
                    }
                    File dir = new File(host.getAppBaseFile(), cn.getBaseName());
                    if (dir.exists()) {
                        log.warn(sm.getString("hostConfig.deployDescriptor.hiddenDir",
                                contextXml.getAbsolutePath(), dir.getAbsolutePath()));
                    }
                } else {
                    log.warn(sm.getString("hostConfig.deployDescriptor.localDocBaseSpecified", docBase));
                    // Ignore specified docBase
                    context.setDocBase(null);
                }
            }

            host.addChild(context);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployDescriptor.error", contextXml.getAbsolutePath()), t);
        } finally {
            // Get paths for WAR and expanded WAR in appBase

            // default to appBase dir + name
            expandedDocBase = new File(host.getAppBaseFile(), cn.getBaseName());
            if (context.getDocBase() != null && !context.getDocBase().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                // first assume docBase is absolute
                expandedDocBase = new File(context.getDocBase());
                if (!expandedDocBase.isAbsolute()) {
                    // if docBase specified and relative, it must be relative to appBase
                    expandedDocBase = new File(host.getAppBaseFile(), context.getDocBase());
                }
            }

            boolean unpackWAR = unpackWARs;
            if (unpackWAR && context instanceof StandardContext) {
                unpackWAR = ((StandardContext) context).getUnpackWAR();
            }

            // Add the eventual unpacked WAR and all the resources which will be
            // watched inside it
            if (isExternalWar) {
                if (unpackWAR) {
                    deployedApp.redeployResources.put(
                            expandedDocBase.getAbsolutePath(), Long.valueOf(expandedDocBase.lastModified()));
                    addWatchedResources(deployedApp, expandedDocBase.getAbsolutePath(), context);
                } else {
                    addWatchedResources(deployedApp, null, context);
                }
            } else {
                // Find an existing matching war and expanded folder
                if (!isExternal) {
                    File warDocBase = new File(expandedDocBase.getAbsolutePath() + ".war");
                    if (warDocBase.exists()) {
                        deployedApp.redeployResources.put(
                                warDocBase.getAbsolutePath(), Long.valueOf(warDocBase.lastModified()));
                    } else {
                        // Trigger a redeploy if a WAR is added
                        deployedApp.redeployResources.put(warDocBase.getAbsolutePath(), Long.valueOf(0));
                    }
                }
                if (unpackWAR) {
                    deployedApp.redeployResources.put(
                            expandedDocBase.getAbsolutePath(), Long.valueOf(expandedDocBase.lastModified()));
                    addWatchedResources(deployedApp, expandedDocBase.getAbsolutePath(), context);
                } else {
                    addWatchedResources(deployedApp, null, context);
                }
                if (!isExternal) {
                    // For external docBases, the context.xml will have been
                    // added above.
                    deployedApp.redeployResources.put(
                            contextXml.getAbsolutePath(), Long.valueOf(contextXml.lastModified()));
                }
            }
            // Add the global redeploy resources (which are never deleted) at
            // the end so they don't interfere with the deletion process
            addGlobalRedeployResources(deployedApp);
        }

        if (host.findChild(context.getName()) != null) {
            deployed.put(context.getName(), deployedApp);
        }

        if (log.isInfoEnabled()) {
            log.info(sm.getString("hostConfig.deployDescriptor.finished",
                    contextXml.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
        }
    }


    /**
     * Deploy WAR files. 部署war文件
     * @param appBase The base path for applications 应用程序的基本路径
     * @param files The WARs to deploy
     */
    protected void deployWARs(File appBase, String[] files) {

        if (files == null) {
            return;
        }

        ExecutorService es = host.getStartStopExecutor();
        List<Future<?>> results = new ArrayList<>();

        for (String file : files) {
            if (file.equalsIgnoreCase("META-INF")) {
                continue;
            }
            if (file.equalsIgnoreCase("WEB-INF")) {
                continue;
            }

            File war = new File(appBase, file);
            if (file.toLowerCase(Locale.ENGLISH).endsWith(".war") && war.isFile() && !invalidWars.contains(file)) {
                ContextName cn = new ContextName(file, true);
                if (tryAddServiced(cn.getName())) {
                    try {
                        if (deploymentExists(cn.getName())) {
                            DeployedApplication app = deployed.get(cn.getName());
                            boolean unpackWAR = unpackWARs;
                            if (unpackWAR && host.findChild(cn.getName()) instanceof StandardContext) {
                                unpackWAR = ((StandardContext) host.findChild(cn.getName())).getUnpackWAR();
                            }
                            if (!unpackWAR && app != null) {
                                // Need to check for a directory that should not be
                                // there
                                File dir = new File(appBase, cn.getBaseName());
                                if (dir.exists()) {
                                    if (!app.loggedDirWarning) {
                                        log.warn(sm.getString("hostConfig.deployWar.hiddenDir",
                                                dir.getAbsoluteFile(), war.getAbsoluteFile()));
                                        app.loggedDirWarning = true;
                                    }
                                } else {
                                    app.loggedDirWarning = false;
                                }
                            }
                            removeServiced(cn.getName());
                            continue;
                        }

                        // Check for WARs with /../ /./ or similar sequences in the name
                        if (!validateContextPath(appBase, cn.getBaseName())) {
                            log.error(sm.getString("hostConfig.illegalWarName", file));
                            invalidWars.add(file);
                            removeServiced(cn.getName());
                            continue;
                        }

                        // DeployWAR will call removeServiced
                        results.add(es.submit(new DeployWar(this, cn, war)));
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        removeServiced(cn.getName());
                        throw t;
                    }
                }
            }
        }

        for (Future<?> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.deployWar.threaded.error"), e);
            }
        }
    }


    private boolean validateContextPath(File appBase, String contextPath) {
        // More complicated than the ideal as the canonical path may or may
        // not end with File.separator for a directory

        StringBuilder docBase;
        String canonicalDocBase = null;

        try {
            String canonicalAppBase = appBase.getCanonicalPath();
            docBase = new StringBuilder(canonicalAppBase);
            if (canonicalAppBase.endsWith(File.separator)) {
                docBase.append(contextPath.substring(1).replace('/', File.separatorChar));
            } else {
                docBase.append(contextPath.replace('/', File.separatorChar));
            }
            // At this point docBase should be canonical but will not end
            // with File.separator

            canonicalDocBase = (new File(docBase.toString())).getCanonicalPath();

            // If the canonicalDocBase ends with File.separator, add one to
            // docBase before they are compared
            if (canonicalDocBase.endsWith(File.separator)) {
                docBase.append(File.separator);
            }
        } catch (IOException ioe) {
            return false;
        }

        // Compare the two. If they are not the same, the contextPath must
        // have /../ like sequences in it
        return canonicalDocBase.equals(docBase.toString());
    }

    /**
     * Deploy packed WAR.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param cn The context name
     * @param war The WAR file
     */
    protected void deployWAR(ContextName cn, File war) {

        /**Constants.ApplicationContextXml = "META-INF/context.xml" */
        File xml = new File(host.getAppBaseFile(), cn.getBaseName() + "/" + Constants.ApplicationContextXml);
        /**Constants.WarTracker = /META-INF/war-tracker */
        File warTracker = new File(host.getAppBaseFile(), cn.getBaseName() + Constants.WarTracker);

        boolean xmlInWar = false; // 判断在war中的META-INF下是否有context.xml，如果有按xml描述符的方式部署
        try (JarFile jar = new JarFile(war)) {
            JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
            if (entry != null) {
                xmlInWar = true;
            }
        } catch (IOException e) {
            /* Ignore */
        }

        // If there is an expanded directory then any xml in that directory
        // should only be used if the directory is not out of date and
        // unpackWARs is true. Note the code below may apply further limits
        boolean useXml = false;
        // If the xml file exists then expandedDir must exists so no need to
        // test that here
        if (xml.exists() && unpackWARs && (!warTracker.exists() || warTracker.lastModified() == war.lastModified())) {
            useXml = true;
        }

        Context context = null;
        boolean deployThisXML = isDeployThisXML(war, cn); // 没什么太大变数的话，该值就是true

        try {
            if (deployThisXML && useXml && !copyXML) { // 如果不存在context.xml,useXML必为
                synchronized (digesterLock) {
                    try {
                        context = (Context) digester.parse(xml);
                    } catch (Exception e) {
                        log.error(sm.getString("hostConfig.deployDescriptor.error", war.getAbsolutePath()), e);
                    } finally {
                        digester.reset();
                        if (context == null) {
                            context = new FailedContext();
                        }
                    }
                }
                context.setConfigFile(xml.toURI().toURL());
            } else if (deployThisXML && xmlInWar) { // xmlInWar条件false，这个不会走
                synchronized (digesterLock) {
                    try (JarFile jar = new JarFile(war)) {
                        JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
                        try (InputStream istream = jar.getInputStream(entry)) {
                            context = (Context) digester.parse(istream);
                        }
                    } catch (Exception e) {
                        log.error(sm.getString("hostConfig.deployDescriptor.error", war.getAbsolutePath()), e);
                    } finally {
                        digester.reset();
                        if (context == null) {
                            context = new FailedContext();
                        }
                        context.setConfigFile(UriUtil.buildJarUrl(war, Constants.ApplicationContextXml));
                    }
                }
            } else if (!deployThisXML && xmlInWar) { // deployThisXML为true，整个分支条件为false也不会走
                // Block deployment as META-INF/context.xml may contain security
                // configuration necessary for a secure deployment.
                log.error(sm.getString("hostConfig.deployDescriptor.blocked",
                        cn.getPath(), Constants.ApplicationContextXml,
                        new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml")));
            } else { // 部署war，最终会走到这个条件分支，这个地方直接上来先实例化context
                context = (Context) Class.forName(contextClass).getConstructor().newInstance();
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployWar.error", war.getAbsolutePath()), t);
        } finally {
            if (context == null) {
                context = new FailedContext();
            }
        }

        boolean copyThisXml = false;
        if (deployThisXML) {
            if (host instanceof StandardHost) {
                copyThisXml = ((StandardHost) host).isCopyXML();
            }

            // If Host is using default value Context can override it. context可以覆盖host的copyXML属性
            if (!copyThisXml && context instanceof StandardContext) {
                copyThisXml = ((StandardContext) context).getCopyXML();
            }

            if (xmlInWar && copyThisXml) {
                // Change location of XML file to config base
                xml = new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml");
                try (JarFile jar = new JarFile(war)) {
                    JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
                    try (InputStream istream = jar.getInputStream(entry);
                            OutputStream ostream = new FileOutputStream(xml)) {
                        IOTools.flow(istream, ostream);
                    }
                } catch (IOException e) {
                    /* Ignore */
                }
            }
        }

        DeployedApplication deployedApp = new DeployedApplication(
                cn.getName(), xml.exists() && deployThisXML && copyThisXml);

        long startTime = 0;
        // Deploy the application in this WAR file
        if(log.isInfoEnabled()) {
            startTime = System.currentTimeMillis();
            log.info(sm.getString("hostConfig.deployWar", war.getAbsolutePath()));
        }

        try {
            // Populate redeploy resources with the WAR file
            deployedApp.redeployResources.put(war.getAbsolutePath(), Long.valueOf(war.lastModified()));

            if (deployThisXML && xml.exists() && copyThisXml) {
                deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
            } else {
                // In case an XML file is added to the config base later
                deployedApp.redeployResources.put(
                        (new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml")).getAbsolutePath(),
                        Long.valueOf(0));
            }

            /**给context添加监听器 ==> ContextConfig，流程给通过xml描述符部署context一样*/
            Class<?> clazz = Class.forName(host.getConfigClass());
            LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
            context.addLifecycleListener(listener);

            context.setName(cn.getName());
            context.setPath(cn.getPath());
            context.setWebappVersion(cn.getVersion());
            context.setDocBase(cn.getBaseName() + ".war");
            /**
             * 这个地方会调用context的生命周期方法，然后触发context的状态改变
             * 从而触发context的生命周期监听器对象contextConfig的事件回调，如下面的：
             * {@link ContextConfig#beforeStart()}
             * 这个方法里面最终会触发war文件的解压
             * 所以你看，生命周期接口设计的有多棒啊！
             */
            host.addChild(context);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployWar.error", war.getAbsolutePath()), t);
        } finally {
            // If we're unpacking WARs, the docBase will be mutated after
            // starting the context
            boolean unpackWAR = unpackWARs;
            if (unpackWAR && context instanceof StandardContext) {
                unpackWAR = ((StandardContext) context).getUnpackWAR();
            }
            if (unpackWAR && context.getDocBase() != null) {
                File docBase = new File(host.getAppBaseFile(), cn.getBaseName());
                deployedApp.redeployResources.put(docBase.getAbsolutePath(), Long.valueOf(docBase.lastModified()));
                addWatchedResources(deployedApp, docBase.getAbsolutePath(), context);
                if (deployThisXML && !copyThisXml && (xmlInWar || xml.exists())) {
                    deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
                }
            } else {
                // Passing null for docBase means that no resources will be
                // watched. This will be logged at debug level.
                addWatchedResources(deployedApp, null, context);
            }
            // Add the global redeploy resources (which are never deleted) at
            // the end so they don't interfere with the deletion process
            addGlobalRedeployResources(deployedApp);
        }

        deployed.put(cn.getName(), deployedApp);

        if (log.isInfoEnabled()) {
            log.info(sm.getString("hostConfig.deployWar.finished",
                    war.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
        }
    }


    /**
     * Deploy exploded webapps.
     * @param appBase The base path for applications
     * @param files The exploded webapps that should be deployed
     */
    protected void deployDirectories(File appBase, String[] files) {

        if (files == null) {
            return;
        }

        ExecutorService es = host.getStartStopExecutor();
        List<Future<?>> results = new ArrayList<>();

        for (String file : files) {
            if (file.equalsIgnoreCase("META-INF")) {
                continue;
            }
            if (file.equalsIgnoreCase("WEB-INF")) {
                continue;
            }

            File dir = new File(appBase, file);
            if (dir.isDirectory()) {
                ContextName cn = new ContextName(file, false);

                if (tryAddServiced(cn.getName())) {
                    try {
                        if (deploymentExists(cn.getName())) {
                            removeServiced(cn.getName());
                            continue;
                        }

                        // DeployDirectory will call removeServiced
                        /**多个应用并行部署，即一个文件夹对应一个部署线程*/
                        results.add(es.submit(new DeployDirectory(this, cn, dir)));
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        removeServiced(cn.getName());
                        throw t;
                    }
                }
            }
        }

        for (Future<?> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.deployDir.threaded.error"), e);
            }
        }
    }


    /**
     * Deploy exploded webapp.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param cn The context name
     * @param dir The path to the root folder of the weapp
     */
    protected void deployDirectory(ContextName cn, File dir) {

        long startTime = 0;
        // Deploy the application in this directory
        if( log.isInfoEnabled() ) {
            startTime = System.currentTimeMillis();
            log.info(sm.getString("hostConfig.deployDir", dir.getAbsolutePath()));
        }

        Context context = null;
        File xml = new File(dir, Constants.ApplicationContextXml);
        File xmlCopy = new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml");

        DeployedApplication deployedApp;
        boolean copyThisXml = isCopyXML();
        boolean deployThisXML = isDeployThisXML(dir, cn);

        try {
            if (deployThisXML && xml.exists()) {
                synchronized (digesterLock) {
                    try {
                        context = (Context) digester.parse(xml);
                    } catch (Exception e) {
                        log.error(sm.getString("hostConfig.deployDescriptor.error", xml), e);
                        context = new FailedContext();
                    } finally {
                        digester.reset();
                        if (context == null) {
                            context = new FailedContext();
                        }
                    }
                }

                if (copyThisXml == false && context instanceof StandardContext) {
                    // Host is using default value. Context may override it.
                    copyThisXml = ((StandardContext) context).getCopyXML();
                }

                if (copyThisXml) {
                    Files.copy(xml.toPath(), xmlCopy.toPath());
                    context.setConfigFile(xmlCopy.toURI().toURL());
                } else {
                    context.setConfigFile(xml.toURI().toURL());
                }
            } else if (!deployThisXML && xml.exists()) {
                // Block deployment as META-INF/context.xml may contain security
                // configuration necessary for a secure deployment.
                log.error(sm.getString("hostConfig.deployDescriptor.blocked", cn.getPath(), xml, xmlCopy));
                context = new FailedContext();
            } else {
                context = (Context) Class.forName(contextClass).getConstructor().newInstance();
            }

            Class<?> clazz = Class.forName(host.getConfigClass());
            LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
            /**
             * 给当前的StandardContext添加生命周期监听器{@link ContextConfig}
             * 这个ContextConfig后面会载入servlet的class
             */
            context.addLifecycleListener(listener);

            context.setName(cn.getName());
            context.setPath(cn.getPath());
            /**为应用设置版本*/
            context.setWebappVersion(cn.getVersion());
            context.setDocBase(cn.getBaseName());
            host.addChild(context);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployDir.error", dir.getAbsolutePath()), t);
        } finally {
            deployedApp = new DeployedApplication(cn.getName(), xml.exists() && deployThisXML && copyThisXml);

            // Fake re-deploy resource to detect if a WAR is added at a later
            // point
            deployedApp.redeployResources.put(dir.getAbsolutePath() + ".war", Long.valueOf(0));
            deployedApp.redeployResources.put(dir.getAbsolutePath(), Long.valueOf(dir.lastModified()));
            if (deployThisXML && xml.exists()) {
                if (copyThisXml) {
                    deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(xmlCopy.lastModified()));
                } else {
                    deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
                    // Fake re-deploy resource to detect if a context.xml file is
                    // added at a later point
                    deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(0));
                }
            } else {
                // Fake re-deploy resource to detect if a context.xml file is
                // added at a later point
                deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(0));
                if (!xml.exists()) {
                    deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(0));
                }
            }
            addWatchedResources(deployedApp, dir.getAbsolutePath(), context);
            // Add the global redeploy resources (which are never deleted) at
            // the end so they don't interfere with the deletion process
            addGlobalRedeployResources(deployedApp);
        }

        deployed.put(cn.getName(), deployedApp);

        if( log.isInfoEnabled() ) {
            log.info(sm.getString("hostConfig.deployDir.finished",
                    dir.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
        }
    }


    /**
     * Check if a webapp is already deployed in this host.
     *
     * @param contextName of the context which will be checked
     * @return <code>true</code> if the specified deployment exists
     */
    protected boolean deploymentExists(String contextName) {
        return deployed.containsKey(contextName) || (host.findChild(contextName) != null);
    }


    /**
     * Add watched resources to the specified Context.
     * 将监视的资源添加到指定的Context中。
     * @param app HostConfig deployed app
     * @param docBase web app docBase
     * @param context web application context
     */
    protected void addWatchedResources(DeployedApplication app, String docBase, Context context) {
        // FIXME: Feature idea. Add support for patterns (ex: WEB-INF/*,
        //        WEB-INF/*.xml), where we would only check if at least one
        //        resource is newer than app.timestamp
        File docBaseFile = null;
        if (docBase != null) {
            docBaseFile = new File(docBase);
            if (!docBaseFile.isAbsolute()) {
                docBaseFile = new File(host.getAppBaseFile(), docBase);
            }
        }
        String[] watchedResources = context.findWatchedResources();
        for (String watchedResource : watchedResources) {
            File resource = new File(watchedResource);
            if (!resource.isAbsolute()) {
                if (docBase != null) {
                    resource = new File(docBaseFile, watchedResource);
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Ignoring non-existent WatchedResource '" +
                                resource.getAbsolutePath() + "'");
                    }
                    continue;
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("Watching WatchedResource '" +
                        resource.getAbsolutePath() + "'");
            }
            app.reloadResources.put(resource.getAbsolutePath(),
                    Long.valueOf(resource.lastModified()));
        }
    }


    protected void addGlobalRedeployResources(DeployedApplication app) {
        // Redeploy resources processing is hard-coded to never delete this file
        File hostContextXml =
                new File(getConfigBaseName(), Constants.HostContextXml);
        if (hostContextXml.isFile()) {
            app.redeployResources.put(hostContextXml.getAbsolutePath(),
                    Long.valueOf(hostContextXml.lastModified()));
        }

        // Redeploy resources in CATALINA_BASE/conf are never deleted
        File globalContextXml =
                returnCanonicalPath(Constants.DefaultContextXml);
        if (globalContextXml.isFile()) {
            app.redeployResources.put(globalContextXml.getAbsolutePath(),
                    Long.valueOf(globalContextXml.lastModified()));
        }
    }


    /**
     * Check resources for redeployment and reloading.
     * 检查资源文件是否被修改用于应用的重新部署和资源的重新加载
     * @param app   The web application to check
     * @param skipFileModificationResolutionCheck
     *              When checking files for modification should the check that
     *              requires that any file modification must have occurred at
     *              least as long ago as the resolution of the file time stamp
     *              be skipped
     */
    protected synchronized void checkResources(DeployedApplication app,
            boolean skipFileModificationResolutionCheck) {
        String[] resources =
            app.redeployResources.keySet().toArray(new String[0]);
        // Offset the current time by the resolution of File.lastModified()
        long currentTimeWithResolutionOffset =
                System.currentTimeMillis() - FILE_MODIFICATION_RESOLUTION_MS;
        for (int i = 0; i < resources.length; i++) {
            File resource = new File(resources[i]);
            if (log.isDebugEnabled()) {
                log.debug("Checking context[" + app.name +
                        "] redeploy resource " + resource);
            }
            long lastModified =
                    app.redeployResources.get(resources[i]).longValue();
            if (resource.exists() || lastModified == 0) {
                // File.lastModified() has a resolution of 1s (1000ms). The last
                // modified time has to be more than 1000ms ago to ensure that
                // modifications that take place in the same second are not
                // missed. See Bug 57765.
                if (resource.lastModified() != lastModified && (!host.getAutoDeploy() ||
                        resource.lastModified() < currentTimeWithResolutionOffset ||
                        skipFileModificationResolutionCheck)) {
                    if (resource.isDirectory()) {
                        // No action required for modified directory
                        app.redeployResources.put(resources[i],
                                Long.valueOf(resource.lastModified()));
                    } else if (app.hasDescriptor &&
                            resource.getName().toLowerCase(
                                    Locale.ENGLISH).endsWith(".war")) {
                        // Modified WAR triggers a reload if there is an XML
                        // file present
                        // The only resource that should be deleted is the
                        // expanded WAR (if any)
                        Context context = (Context) host.findChild(app.name);
                        String docBase = context.getDocBase();
                        if (!docBase.toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                            // This is an expanded directory
                            File docBaseFile = new File(docBase);
                            if (!docBaseFile.isAbsolute()) {
                                docBaseFile = new File(host.getAppBaseFile(),
                                        docBase);
                            }
                            reload(app, docBaseFile, resource.getAbsolutePath());
                        } else {
                            reload(app, null, null);
                        }
                        // Update times
                        app.redeployResources.put(resources[i],
                                Long.valueOf(resource.lastModified()));
                        app.timestamp = System.currentTimeMillis();
                        boolean unpackWAR = unpackWARs;
                        if (unpackWAR && context instanceof StandardContext) {
                            unpackWAR = ((StandardContext) context).getUnpackWAR();
                        }
                        if (unpackWAR) {
                            addWatchedResources(app, context.getDocBase(), context);
                        } else {
                            addWatchedResources(app, null, context);
                        }
                        return;
                    } else {
                        // Everything else triggers a redeploy
                        // (just need to undeploy here, deploy will follow)
                        undeploy(app);
                        deleteRedeployResources(app, resources, i, false);
                        return;
                    }
                }
            } else {
                // There is a chance the the resource was only missing
                // temporarily eg renamed during a text editor save
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e1) {
                    // Ignore
                }
                // Recheck the resource to see if it was really deleted
                if (resource.exists()) {
                    continue;
                }
                // Undeploy application
                undeploy(app);
                deleteRedeployResources(app, resources, i, true);
                return;
            }
        }
        resources = app.reloadResources.keySet().toArray(new String[0]);
        boolean update = false;
        for (String s : resources) {
            File resource = new File(s);
            if (log.isDebugEnabled()) {
                log.debug("Checking context[" + app.name + "] reload resource " + resource);
            }
            long lastModified = app.reloadResources.get(s).longValue();
            // File.lastModified() has a resolution of 1s (1000ms). The last
            // modified time has to be more than 1000ms ago to ensure that
            // modifications that take place in the same second are not
            // missed. See Bug 57765.
            if ((resource.lastModified() != lastModified &&
                    (!host.getAutoDeploy() ||
                            resource.lastModified() < currentTimeWithResolutionOffset ||
                            skipFileModificationResolutionCheck)) ||
                    update) {
                if (!update) {
                    // Reload application
                    reload(app, null, null);
                    update = true;
                }
                // Update times. More than one file may have been updated. We
                // don't want to trigger a series of reloads.
                app.reloadResources.put(s,
                        Long.valueOf(resource.lastModified()));
            }
            app.timestamp = System.currentTimeMillis();
        }
    }


    /*
     * Note: If either of fileToRemove and newDocBase are null, both will be
     *       ignored.
     */
    private void reload(DeployedApplication app, File fileToRemove, String newDocBase) {
        if(log.isInfoEnabled()) {
            log.info(sm.getString("hostConfig.reload", app.name));
        }
        Context context = (Context) host.findChild(app.name);
        if (context.getState().isAvailable()) {
            if (fileToRemove != null && newDocBase != null) {
                context.addLifecycleListener(
                        new ExpandedDirectoryRemovalListener(fileToRemove, newDocBase));
            }
            // Reload catches and logs exceptions
            context.reload();
        } else {
            // If the context was not started (for example an error
            // in web.xml) we'll still get to try to start
            if (fileToRemove != null && newDocBase != null) {
                ExpandWar.delete(fileToRemove);
                context.setDocBase(newDocBase);
            }
            try {
                context.start();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.context.restart", app.name), e);
            }
        }
    }


    private void undeploy(DeployedApplication app) {
        if (log.isInfoEnabled()) {
            log.info(sm.getString("hostConfig.undeploy", app.name));
        }
        Container context = host.findChild(app.name);
        try {
            host.removeChild(context);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.warn(sm.getString
                     ("hostConfig.context.remove", app.name), t);
        }
        deployed.remove(app.name);
    }


    private void deleteRedeployResources(DeployedApplication app, String[] resources, int i,
            boolean deleteReloadResources) {

        // Delete other redeploy resources
        for (int j = i + 1; j < resources.length; j++) {
            File current = new File(resources[j]);
            // Never delete per host context.xml defaults
            if (Constants.HostContextXml.equals(current.getName())) {
                continue;
            }
            // Only delete resources in the appBase or the
            // host's configBase
            if (isDeletableResource(app, current)) {
                if (log.isDebugEnabled()) {
                    log.debug("Delete " + current);
                }
                ExpandWar.delete(current);
            }
        }

        // Delete reload resources (to remove any remaining .xml descriptor)
        if (deleteReloadResources) {
            String[] resources2 = app.reloadResources.keySet().toArray(new String[0]);
            for (String s : resources2) {
                File current = new File(s);
                // Never delete per host context.xml defaults
                if (Constants.HostContextXml.equals(current.getName())) {
                    continue;
                }
                // Only delete resources in the appBase or the host's
                // configBase
                if (isDeletableResource(app, current)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Delete " + current);
                    }
                    ExpandWar.delete(current);
                }
            }
        }
    }


    /*
     * Delete any resource that would trigger the automatic deployment code to
     * re-deploy the application. This means deleting:
     * - any resource located in the appBase
     * - any deployment descriptor located under the configBase
     * - symlinks in the appBase or configBase for either of the above
     */
    private boolean isDeletableResource(DeployedApplication app, File resource) {
        // The resource may be a file, a directory or a symlink to a file or
        // directory.

        // Check that the resource is absolute. This should always be the case.
        if (!resource.isAbsolute()) {
            log.warn(sm.getString("hostConfig.resourceNotAbsolute", app.name, resource));
            return false;
        }

        // Determine where the resource is located
        String canonicalLocation;
        try {
            canonicalLocation = resource.getParentFile().getCanonicalPath();
        } catch (IOException e) {
            log.warn(sm.getString(
                    "hostConfig.canonicalizing", resource.getParentFile(), app.name), e);
            return false;
        }

        String canonicalAppBase;
        try {
            canonicalAppBase = host.getAppBaseFile().getCanonicalPath();
        } catch (IOException e) {
            log.warn(sm.getString(
                    "hostConfig.canonicalizing", host.getAppBaseFile(), app.name), e);
            return false;
        }

        if (canonicalLocation.equals(canonicalAppBase)) {
            // Resource is located in the appBase so it may be deleted
            return true;
        }

        String canonicalConfigBase;
        try {
            canonicalConfigBase = host.getConfigBaseFile().getCanonicalPath();
        } catch (IOException e) {
            log.warn(sm.getString(
                    "hostConfig.canonicalizing", host.getConfigBaseFile(), app.name), e);
            return false;
        }

        if (canonicalLocation.equals(canonicalConfigBase) &&
                resource.getName().endsWith(".xml")) {
            // Resource is an xml file in the configBase so it may be deleted
            return true;
        }

        // All other resources should not be deleted
        return false;
    }


    public void beforeStart() { /**启动前，先保证应用webapps和Catalina/主机名配置文件夹存在，不存在就创建*/
        if (host.getCreateDirs()) {
            File[] dirs = new File[] {host.getAppBaseFile(),host.getConfigBaseFile()};
            for (File dir : dirs) {
                if (!dir.mkdirs() && !dir.isDirectory()) {
                    log.error(sm.getString("hostConfig.createDirs", dir));
                }
            }
        }
    }


    /**
     * Process a "start" event for this Host. 为这个主机处理一个“开始”事件。
     */
    public void start() {
        System.out.println("======> StandardHost#listener-HostConfig start.");
        if (log.isDebugEnabled()) {
            log.debug(sm.getString("hostConfig.start"));
        }

        try {
            /**拿到jmx容器中的host组件的bean对象名称：Catalina:type=Host,host=localhost*/
            ObjectName hostON = host.getObjectName();
            /**重新定义当前hostConfig的oname,实际上就是把type从host替换成Deployer，因为监听器干的事情就是部署应用*/
            oname = new ObjectName(hostON.getDomain() + ":type=Deployer,host=" + host.getName());
            /**将当前监听器注册到jmx容器中*/
            Registry.getRegistry(null, null).registerComponent(this, oname, this.getClass().getName());
        } catch (Exception e) {
            log.warn(sm.getString("hostConfig.jmx.register", oname), e);
        }

        /**host.getAppBaseFile() : CATALINA/appBase 地址对应的file, 如果file不是文件夹，报错*/
        if (!host.getAppBaseFile().isDirectory()) {
            log.error(sm.getString("hostConfig.appBase", host.getName(),
                    host.getAppBaseFile().getPath()));
            /**应用的部署地址一旦有问题，就要暂停主机启动时自动部署应用的功能*/
            host.setDeployOnStartup(false);
            host.setAutoDeploy(false);
        }

        if (host.getDeployOnStartup()) {
            deployApps();/**整个HostConfig监听器比较核心的方法，部署应用*/
        }
    }


    /**
     * Process a "stop" event for this Host.
     */
    public void stop() {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("hostConfig.stop"));
        }

        if (oname != null) {
            try {
                Registry.getRegistry(null, null).unregisterComponent(oname);
            } catch (Exception e) {
                log.warn(sm.getString("hostConfig.jmx.unregister", oname), e);
            }
        }
        oname = null;
    }


    /**
     * Check status of all webapps.
     */
    protected void check() {

        if (host.getAutoDeploy()) {
            // Check for resources modification to trigger redeployment
            DeployedApplication[] apps = deployed.values().toArray(new DeployedApplication[0]);
            for (DeployedApplication app : apps) {
                if (tryAddServiced(app.name)) {
                    try {
                        /**检查资源以进行重新部署和重新加载、对旧版本的进行检查卸载、最后进行热部署应用*/
                        checkResources(app, false);
                    } finally {
                        removeServiced(app.name);
                    }
                }
            }

            // Check for old versions of applications that can now be undeployed
            if (host.getUndeployOldVersions()) {
                checkUndeploy();
            }

            // Hotdeploy applications
            deployApps();
        }
    }


    /**
     * Check status of a specific web application and reload, redeploy or deploy
     * it as necessary. This method is for use with functionality such as
     * management web applications that upload new/updated web applications and
     * need to trigger the appropriate action to deploy them. This method
     * assumes that any uploading/updating has been completed before this method
     * is called. Any action taken as a result of the checks will complete
     * before this method returns.
     *
     * @param name The name of the web application to check
     */
    public void check(String name) {
        if (tryAddServiced(name)) {
            try {
                DeployedApplication app = deployed.get(name);
                if (app != null) {
                    checkResources(app, true);
                }
                deployApps(name);
            } finally {
                removeServiced(name);
            }
        }
    }

    /**
     * Check for old versions of applications using parallel deployment that are
     * now unused (have no active sessions) and undeploy any that are found.
     */
    public synchronized void checkUndeploy() {
        if (deployed.size() < 2) {
            return;
        }

        // Need ordered set of names
        SortedSet<String> sortedAppNames = new TreeSet<>(deployed.keySet());

        Iterator<String> iter = sortedAppNames.iterator();

        ContextName previous = new ContextName(iter.next(), false);
        do {
            ContextName current = new ContextName(iter.next(), false);

            if (current.getPath().equals(previous.getPath())) {
                // Current and previous are same path - current will always
                // be a later version
                Context previousContext = (Context) host.findChild(previous.getName());
                Context currentContext = (Context) host.findChild(current.getName());
                if (previousContext != null && currentContext != null &&
                        currentContext.getState().isAvailable() &&
                        tryAddServiced(previous.getName())) {
                    try {
                        Manager manager = previousContext.getManager();
                        if (manager != null) {
                            int sessionCount;
                            if (manager instanceof DistributedManager) {
                                sessionCount = ((DistributedManager) manager).getActiveSessionsFull();
                            } else {
                                sessionCount = manager.getActiveSessions();
                            }
                            if (sessionCount == 0) {
                                if (log.isInfoEnabled()) {
                                    log.info(sm.getString("hostConfig.undeployVersion", previous.getName()));
                                }
                                DeployedApplication app = deployed.get(previous.getName());
                                String[] resources = app.redeployResources.keySet().toArray(new String[0]);
                                // Version is unused - undeploy it completely
                                // The -1 is a 'trick' to ensure all redeploy
                                // resources are removed
                                undeploy(app);
                                deleteRedeployResources(app, resources, -1, true);
                            }
                        }
                    } finally {
                        removeServiced(previous.getName());
                    }
                }
            }
            previous = current;
        } while (iter.hasNext());
    }

    /**
     * Add a new Context to be managed by us.
     * Entry point for the admin webapp, and other JMX Context controllers.
     * @param context The context instance
     */
    public void manageApp(Context context)  {

        String contextName = context.getName();

        if (deployed.containsKey(contextName)) {
            return;
        }

        DeployedApplication deployedApp =
                new DeployedApplication(contextName, false);

        // Add the associated docBase to the redeployed list if it's a WAR
        boolean isWar = false;
        if (context.getDocBase() != null) {
            File docBase = new File(context.getDocBase());
            if (!docBase.isAbsolute()) {
                docBase = new File(host.getAppBaseFile(), context.getDocBase());
            }
            deployedApp.redeployResources.put(docBase.getAbsolutePath(),
                    Long.valueOf(docBase.lastModified()));
            if (docBase.getAbsolutePath().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                isWar = true;
            }
        }
        host.addChild(context);
        // Add the eventual unpacked WAR and all the resources which will be
        // watched inside it
        boolean unpackWAR = unpackWARs;
        if (unpackWAR && context instanceof StandardContext) {
            unpackWAR = ((StandardContext) context).getUnpackWAR();
        }
        if (isWar && unpackWAR) {
            File docBase = new File(host.getAppBaseFile(), context.getBaseName());
            deployedApp.redeployResources.put(docBase.getAbsolutePath(),
                        Long.valueOf(docBase.lastModified()));
            addWatchedResources(deployedApp, docBase.getAbsolutePath(), context);
        } else {
            addWatchedResources(deployedApp, null, context);
        }
        deployed.put(contextName, deployedApp);
    }

    /**
     * Remove a webapp from our control.
     * Entry point for the admin webapp, and other JMX Context controllers.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param contextName The context name
     */
    public void unmanageApp(String contextName) {
        deployed.remove(contextName);
        host.removeChild(host.findChild(contextName));
    }

    // ----------------------------------------------------- Instance Variables


    /**
     * This class represents the state of a deployed application, as well as
     * the monitored resources.
     */
    protected static class DeployedApplication {
        public DeployedApplication(String name, boolean hasDescriptor) {
            this.name = name;
            this.hasDescriptor = hasDescriptor;
        }

        /**
         * Application context path. The assertion is that
         * (host.getChild(name) != null).
         */
        public final String name;

        /**
         * Does this application have a context.xml descriptor file on the
         * host's configBase?
         */
        public final boolean hasDescriptor;

        /**
         * Any modification of the specified (static) resources will cause a
         * redeployment of the application. If any of the specified resources is
         * removed, the application will be undeployed. Typically, this will
         * contain resources like the context.xml file, a compressed WAR path.
         * The value is the last modification time.
         * 对指定(静态)资源的任何修改都将导致应用程序的重新部署。如果删除了任何指定的资源，
         * 应用程序将被取消部署。通常，这将包含context.xml文件等资源，这是一个压缩的WAR路径。最后一次修改时间。
         */
        public final LinkedHashMap<String, Long> redeployResources =
                new LinkedHashMap<>();

        /**
         * Any modification of the specified (static) resources will cause a
         * reload of the application. This will typically contain resources
         * such as the web.xml of a webapp, but can be configured to contain
         * additional descriptors.
         * The value is the last modification time.
         */
        public final HashMap<String, Long> reloadResources = new HashMap<>();

        /**
         * Instant where the application was last put in service.
         */
        public long timestamp = System.currentTimeMillis();

        /**
         * In some circumstances, such as when unpackWARs is true, a directory
         * may be added to the appBase that is ignored. This flag indicates that
         * the user has been warned so that the warning is not logged on every
         * run of the auto deployer.
         */
        public boolean loggedDirWarning = false;
    }

    /**部署描述符的任务（线程）*/
    private static class DeployDescriptor implements Runnable {

        private HostConfig config;
        private ContextName cn;
        private File descriptor;

        public DeployDescriptor(HostConfig config, ContextName cn,
                File descriptor) {
            this.config = config;
            this.cn = cn;
            this.descriptor= descriptor;
        }

        @Override
        public void run() {
            try {
                config.deployDescriptor(cn, descriptor);
            } finally {
                config.removeServiced(cn.getName());
            }
        }
    }

    private static class DeployWar implements Runnable {

        private HostConfig config;
        private ContextName cn;
        private File war;

        public DeployWar(HostConfig config, ContextName cn, File war) {
            this.config = config;
            this.cn = cn;
            this.war = war;
        }

        @Override
        public void run() {
            try {
                config.deployWAR(cn, war);
            } finally {
                config.removeServiced(cn.getName());
            }
        }
    }

    /**部署文件夹（webapps下面的每一个目录都是一个context）*/
    private static class DeployDirectory implements Runnable {
        private HostConfig config;
        private ContextName cn;
        private File dir;

        public DeployDirectory(HostConfig config, ContextName cn, File dir) {
            this.config = config;
            this.cn = cn;
            this.dir = dir;
        }

        @Override
        public void run() {
            try {
                config.deployDirectory(cn, dir);
            } finally {
                config.removeServiced(cn.getName());
            }
        }
    }


    /*
     * The purpose of this class is to provide a way for HostConfig to get
     * a Context to delete an expanded WAR after the Context stops. This is to
     * resolve this issue described in Bug 57772. The alternative solutions
     * require either duplicating a lot of the Context.reload() code in
     * HostConfig or adding a new reload(boolean) method to Context that allows
     * the caller to optionally delete any expanded WAR.
     *
     * The LifecycleListener approach offers greater flexibility and enables the
     * behaviour to be changed / extended / removed in future without changing
     * the Context API.
     */
    private static class ExpandedDirectoryRemovalListener implements LifecycleListener {

        private final File toDelete;
        private final String newDocBase;

        /**
         * Create a listener that will ensure that any expanded WAR is removed
         * and the docBase set to the specified WAR.
         *
         * @param toDelete The file (a directory representing an expanded WAR)
         *                 to be deleted
         * @param newDocBase The new docBase for the Context
         */
        public ExpandedDirectoryRemovalListener(File toDelete, String newDocBase) {
            this.toDelete = toDelete;
            this.newDocBase = newDocBase;
        }

        @Override
        public void lifecycleEvent(LifecycleEvent event) {
            if (Lifecycle.AFTER_STOP_EVENT.equals(event.getType())) {
                // The context has stopped.
                Context context = (Context) event.getLifecycle();

                // Remove the old expanded WAR.
                ExpandWar.delete(toDelete);

                // Reset the docBase to trigger re-expansion of the WAR.
                context.setDocBase(newDocBase);

                // Remove this listener from the Context else it will run every
                // time the Context is stopped.
                context.removeLifecycleListener(this);
            }
        }
    }
}
