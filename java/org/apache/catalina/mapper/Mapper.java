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
package org.apache.catalina.mapper;

import org.apache.catalina.*;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.buf.Ascii;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.res.StringManager;

import javax.servlet.http.MappingMatch;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mapper, which implements the servlet API mapping rules (which are derived
 * from the HTTP rules).
 * 它实现了servlet API 映射规则（从HTTP 规则派生而来）
 * 映射关系最核心的、最重要的类。完成url与Host，Context，Wrapper映射关系的初始化、变更、存储及映射
 * @author Remy Maucherat
 */
// Mapper作为uri映射到容器的工具，扮演的角色就是一个映射组件，它会缓存所有容器信息，同事提供映射规则，将一个uri按照映射规则映射到具体的Host、Context、Wrapper，并最终通过Wrapper找到逻辑处理单元
// 1、Mapper组件主要的职责是负责Tomcat的请求路由，每个客户端请求到达Tomcat后，都将由Mapper路由到对应的处理逻辑（Servlet）上
// 2、www.baidu.com/web1/index.html，请求路径分析出对应的层级：Host/Context/Wrapper
// 3、对应的配置如下<Host name="www.baidu.com" appBase="webapps"><context path="web1"></context></Host>
// 4、按照容器等级，首先Mapper组件包含了N个Host容器的引用，然后每个Host会有N个Cotext容器的引用，最后每个Context容器包含N个Wrapper
// 容器的引用例如，如果使用Mapper组件查找tomcat.apache. org/tomcat-7.0-doc/search，
// 它首先会匹配名为tomcat.apache.org的Host，然后从中继续匹配名为tomcat-7.0-doc的Context，
// 最后匹配名为search的Wrapper（Servlet）
public final class Mapper {

    private static final Log log = LogFactory.getLog(Mapper.class);

    private static final StringManager sm = StringManager.getManager(Mapper.class);

    // ----------------------------------------------------- Instance Variables


    /**
     * Array containing the virtual hosts definitions.
     */
    // Package private to facilitate testing
    /**定义所有的Host组合，表示一个Engine下所有Host , 初始化空，mapperListener会逐一添加*/
    volatile MappedHost[] hosts = new MappedHost[0];

    /**
     * Default host name. 默认的主机名称
     */
    private volatile String defaultHostName = null;
    /**默认的主机映射*/
    private volatile MappedHost defaultHost = null;

    /**
     * Mapping from Context object to Context version to support
     * RequestDispatcher mappings.
     * 从Context对象映射到Context版本以支持RequestDispatcher映射。
     */
    private final Map<Context, ContextVersion> contextObjectToContextVersionMap =
            new ConcurrentHashMap<>();


    // --------------------------------------------------------- Public Methods

    /**
     * Set default host.
     *
     * @param defaultHostName Default host name
     */
    public synchronized void setDefaultHostName(String defaultHostName) {
        this.defaultHostName = renameWildcardHost(defaultHostName);
        if (this.defaultHostName == null) {
            defaultHost = null;
        } else {
            defaultHost = exactFind(hosts, this.defaultHostName);
        }
    }


    /**
     * Add a new host to the mapper.
     * 向映射器添加一个新主机。
     * @param name Virtual host name
     * @param aliases Alias names for the virtual host
     * @param host Host object
     */
    public synchronized void addHost(String name, String[] aliases,
                                     Host host) {
        /**
         * 重新定义主机的name 如果name 以 *. 打头，则去掉 *
         * 比如 *.apache.org --> .apache.org
         */
        name = renameWildcardHost(name);
        MappedHost[] newHosts = new MappedHost[hosts.length + 1];
        /**构建主机映射，MappedHost的父类{@link MapElement}*/
        MappedHost newHost = new MappedHost(name, host);
        if (insertMap(hosts, newHosts, newHost)) {
            hosts = newHosts;
            if (newHost.name.equals(defaultHostName)) {
                defaultHost = newHost;
            }
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("mapper.addHost.success", name));
            }
        } else {
            MappedHost duplicate = hosts[find(hosts, name)];
            if (duplicate.object == host) {
                // The host is already registered in the mapper.
                // E.g. it might have been added by addContextVersion()
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("mapper.addHost.sameHost", name));
                }
                newHost = duplicate;
            } else {
                log.error(sm.getString("mapper.duplicateHost", name,
                        duplicate.getRealHostName()));
                // Do not add aliases, as removeHost(hostName) won't be able to
                // remove them
                return;
            }
        }
        List<MappedHost> newAliases = new ArrayList<>(aliases.length);
        for (String alias : aliases) {
            alias = renameWildcardHost(alias);
            MappedHost newAlias = new MappedHost(alias, newHost);
            if (addHostAliasImpl(newAlias)) {
                newAliases.add(newAlias);
            }
        }
        newHost.addAliases(newAliases);
    }


    /**
     * Remove a host from the mapper.
     *
     * @param name Virtual host name
     */
    public synchronized void removeHost(String name) {
        name = renameWildcardHost(name);
        // Find and remove the old host
        MappedHost host = exactFind(hosts, name);
        if (host == null || host.isAlias()) {
            return;
        }
        MappedHost[] newHosts = hosts.clone();
        // Remove real host and all its aliases
        int j = 0;
        for (int i = 0; i < newHosts.length; i++) {
            if (newHosts[i].getRealHost() != host) {
                newHosts[j++] = newHosts[i];
            }
        }
        hosts = Arrays.copyOf(newHosts, j);
    }

    /**
     * Add an alias to an existing host.
     * @param name  The name of the host
     * @param alias The alias to add
     */
    public synchronized void addHostAlias(String name, String alias) {
        MappedHost realHost = exactFind(hosts, name);
        if (realHost == null) {
            // Should not be adding an alias for a host that doesn't exist but
            // just in case...
            return;
        }
        alias = renameWildcardHost(alias);
        MappedHost newAlias = new MappedHost(alias, realHost);
        if (addHostAliasImpl(newAlias)) {
            realHost.addAlias(newAlias);
        }
    }

    private synchronized boolean addHostAliasImpl(MappedHost newAlias) {
        MappedHost[] newHosts = new MappedHost[hosts.length + 1];
        if (insertMap(hosts, newHosts, newAlias)) {
            hosts = newHosts;
            if (newAlias.name.equals(defaultHostName)) {
                defaultHost = newAlias;
            }
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("mapper.addHostAlias.success",
                        newAlias.name, newAlias.getRealHostName()));
            }
            return true;
        } else {
            MappedHost duplicate = hosts[find(hosts, newAlias.name)];
            if (duplicate.getRealHost() == newAlias.getRealHost()) {
                // A duplicate Alias for the same Host.
                // A harmless redundancy. E.g.
                // <Host name="localhost"><Alias>localhost</Alias></Host>
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("mapper.addHostAlias.sameHost",
                            newAlias.name, newAlias.getRealHostName()));
                }
                return false;
            }
            log.error(sm.getString("mapper.duplicateHostAlias", newAlias.name,
                    newAlias.getRealHostName(), duplicate.getRealHostName()));
            return false;
        }
    }

    /**
     * Remove a host alias
     * @param alias The alias to remove
     */
    public synchronized void removeHostAlias(String alias) {
        alias = renameWildcardHost(alias);
        // Find and remove the alias
        MappedHost hostMapping = exactFind(hosts, alias);
        if (hostMapping == null || !hostMapping.isAlias()) {
            return;
        }
        MappedHost[] newHosts = new MappedHost[hosts.length - 1];
        if (removeMap(hosts, newHosts, alias)) {
            hosts = newHosts;
            hostMapping.getRealHost().removeAlias(hostMapping);
        }

    }

    /**
     * Replace {@link MappedHost#contextList} field in <code>realHost</code> and
     * all its aliases with a new value.
     */
    private void updateContextList(MappedHost realHost,
            ContextList newContextList) {

        realHost.contextList = newContextList;
        for (MappedHost alias : realHost.getAliases()) {
            alias.contextList = newContextList;
        }
    }

    /**
     * Add a new Context to an existing Host.
     * 向现有主机添加新的上下文。
     * @param hostName Virtual host name this context belongs to
     * @param host Host object
     * @param path Context path
     * @param version Context version
     * @param context Context object
     * @param welcomeResources Welcome files defined for this context
     * @param resources Static resources of the context
     * @param wrappers Information on wrapper mappings
     */
    public void addContextVersion(String hostName, Host host, String path,
            String version, Context context, String[] welcomeResources,
            WebResourceRoot resources, Collection<WrapperMappingInfo> wrappers) {

        hostName = renameWildcardHost(hostName);

        MappedHost mappedHost  = exactFind(hosts, hostName);
        if (mappedHost == null) {
            addHost(hostName, new String[0], host);
            mappedHost = exactFind(hosts, hostName);
            if (mappedHost == null) {
                log.error(sm.getString("mapper.addContext.noHost", hostName));
                return;
            }
        }
        /**判断下这个虚拟主机是不是别名性质的（也就是其realHost不等于自身就是别名的，这种情况是有问题的）*/
         if (mappedHost.isAlias()) {
            log.error(sm.getString("mapper.addContext.hostIsAlias", hostName));
            return;
        }
        int slashCount = slashCount(path);
        synchronized (mappedHost) {
            ContextVersion newContextVersion = new ContextVersion(version,
                    path, slashCount, context, resources, welcomeResources);
            if (wrappers != null) {
                addWrappers(newContextVersion, wrappers);
            }

            ContextList contextList = mappedHost.contextList;
            MappedContext mappedContext = exactFind(contextList.contexts, path);
            if (mappedContext == null) {
                mappedContext = new MappedContext(path, newContextVersion);
                ContextList newContextList = contextList.addContext(
                        mappedContext, slashCount);
                if (newContextList != null) {
                    updateContextList(mappedHost, newContextList);
                    contextObjectToContextVersionMap.put(context, newContextVersion);
                }
            } else {
                ContextVersion[] contextVersions = mappedContext.versions;
                ContextVersion[] newContextVersions = new ContextVersion[contextVersions.length + 1];
                if (insertMap(contextVersions, newContextVersions,
                        newContextVersion)) {
                    mappedContext.versions = newContextVersions;
                    contextObjectToContextVersionMap.put(context, newContextVersion);
                } else {
                    // Re-registration after Context.reload()
                    // Replace ContextVersion with the new one
                    int pos = find(contextVersions, version);
                    if (pos >= 0 && contextVersions[pos].name.equals(version)) {
                        contextVersions[pos] = newContextVersion;
                        contextObjectToContextVersionMap.put(context, newContextVersion);
                    }
                }
            }
        }

    }


    /**
     * Remove a context from an existing host.
     *
     * @param ctxt      The actual context
     * @param hostName  Virtual host name this context belongs to
     * @param path      Context path
     * @param version   Context version
     */
    public void removeContextVersion(Context ctxt, String hostName,
            String path, String version) {

        hostName = renameWildcardHost(hostName);
        contextObjectToContextVersionMap.remove(ctxt);

        MappedHost host = exactFind(hosts, hostName);
        if (host == null || host.isAlias()) {
            return;
        }

        synchronized (host) {
            ContextList contextList = host.contextList;
            MappedContext context = exactFind(contextList.contexts, path);
            if (context == null) {
                return;
            }

            ContextVersion[] contextVersions = context.versions;
            ContextVersion[] newContextVersions =
                new ContextVersion[contextVersions.length - 1];
            if (removeMap(contextVersions, newContextVersions, version)) {
                if (newContextVersions.length == 0) {
                    // Remove the context
                    ContextList newContextList = contextList.removeContext(path);
                    if (newContextList != null) {
                        updateContextList(host, newContextList);
                    }
                } else {
                    context.versions = newContextVersions;
                }
            }
        }
    }


    /**
     * Mark a context as being reloaded. Reversion of this state is performed
     * by calling <code>addContextVersion(...)</code> when context starts up.
     *
     * @param ctxt      The actual context
     * @param hostName  Virtual host name this context belongs to
     * @param contextPath Context path
     * @param version   Context version
     */
    public void pauseContextVersion(Context ctxt, String hostName,
            String contextPath, String version) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName,
                contextPath, version, true);
        if (contextVersion == null || !ctxt.equals(contextVersion.object)) {
            return;
        }
        contextVersion.markPaused();
    }


    private ContextVersion findContextVersion(String hostName,
            String contextPath, String version, boolean silent) {
        MappedHost host = exactFind(hosts, hostName);
        if (host == null || host.isAlias()) {
            if (!silent) {
                log.error(sm.getString("mapper.findContext.noHostOrAlias", hostName));
            }
            return null;
        }
        MappedContext context = exactFind(host.contextList.contexts,
                contextPath);
        if (context == null) {
            if (!silent) {
                log.error(sm.getString("mapper.findContext.noContext", contextPath));
            }
            return null;
        }
        ContextVersion contextVersion = exactFind(context.versions, version);
        if (contextVersion == null) {
            if (!silent) {
                log.error(sm.getString("mapper.findContext.noContextVersion", contextPath, version));
            }
            return null;
        }
        return contextVersion;
    }


    public void addWrapper(String hostName, String contextPath, String version,
                           String path, Wrapper wrapper, boolean jspWildCard,
                           boolean resourceOnly) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName,
                contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        addWrapper(contextVersion, path, wrapper, jspWildCard, resourceOnly);
    }

    public void addWrappers(String hostName, String contextPath,
            String version, Collection<WrapperMappingInfo> wrappers) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName,
                contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        addWrappers(contextVersion, wrappers);
    }

    /**
     * Adds wrappers to the given context.
     *
     * @param contextVersion The context to which to add the wrappers
     * @param wrappers Information on wrapper mappings
     */
    private void addWrappers(ContextVersion contextVersion,
            Collection<WrapperMappingInfo> wrappers) {
        for (WrapperMappingInfo wrapper : wrappers) {
            addWrapper(contextVersion, wrapper.getMapping(),
                    wrapper.getWrapper(), wrapper.isJspWildCard(),
                    wrapper.isResourceOnly());
        }
    }

    /**
     * Adds a wrapper to the given context.
     * 向给定的context添加wrapper，四个匹配（通配符匹配、扩展名匹配、默认匹配和精准匹配）
     * @param context The context to which to add the wrapper 应用上下文
     * @param path Wrapper mapping wrapper映射路径
     * @param wrapper The Wrapper object wrapper对象
     * @param jspWildCard true if the wrapper corresponds to the JspServlet
     *   and the mapping path contains a wildcard; false otherwise
     * @param resourceOnly true if this wrapper always expects a physical
     *                     resource to be present (such as a JSP)
     */
    protected void addWrapper(ContextVersion context, String path,
            Wrapper wrapper, boolean jspWildCard, boolean resourceOnly) {

        synchronized (context) {
            if (path.endsWith("/*")) {
                // Wildcard wrapper
                String name = path.substring(0, path.length() - 2);
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.wildcardWrappers;
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.wildcardWrappers = newWrappers;
                    int slashCount = slashCount(newWrapper.name);
                    if (slashCount > context.nesting) {
                        context.nesting = slashCount;
                    }
                }
            } else if (path.startsWith("*.")) {
                // Extension wrapper
                String name = path.substring(2);
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.extensionWrappers;
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.extensionWrappers = newWrappers;
                }
            } else if (path.equals("/")) {
                // Default wrapper
                MappedWrapper newWrapper = new MappedWrapper("", wrapper,
                        jspWildCard, resourceOnly);
                context.defaultWrapper = newWrapper;
            } else {
                // Exact wrapper 精准匹配
                final String name;
                if (path.length() == 0) {
                    // Special case for the Context Root mapping which is
                    // treated as an exact match
                    name = "/";
                } else {
                    name = path;
                }
                MappedWrapper newWrapper = new MappedWrapper(name, wrapper,
                        jspWildCard, resourceOnly);
                MappedWrapper[] oldWrappers = context.exactWrappers;
                MappedWrapper[] newWrappers = new MappedWrapper[oldWrappers.length + 1];
                if (insertMap(oldWrappers, newWrappers, newWrapper)) {
                    context.exactWrappers = newWrappers;
                }
            }
        }
    }


    /**
     * Remove a wrapper from an existing context.
     *
     * @param hostName    Virtual host name this wrapper belongs to
     * @param contextPath Context path this wrapper belongs to
     * @param version     Context version this wrapper belongs to
     * @param path        Wrapper mapping
     */
    public void removeWrapper(String hostName, String contextPath,
            String version, String path) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName,
                contextPath, version, true);
        if (contextVersion == null || contextVersion.isPaused()) {
            return;
        }
        removeWrapper(contextVersion, path);
    }

    protected void removeWrapper(ContextVersion context, String path) {

        if (log.isDebugEnabled()) {
            log.debug(sm.getString("mapper.removeWrapper", context.name, path));
        }

        synchronized (context) {
            if (path.endsWith("/*")) {
                // Wildcard wrapper
                String name = path.substring(0, path.length() - 2);
                MappedWrapper[] oldWrappers = context.wildcardWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers =
                    new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    // Recalculate nesting
                    context.nesting = 0;
                    for (MappedWrapper newWrapper : newWrappers) {
                        int slashCount = slashCount(newWrapper.name);
                        if (slashCount > context.nesting) {
                            context.nesting = slashCount;
                        }
                    }
                    context.wildcardWrappers = newWrappers;
                }
            } else if (path.startsWith("*.")) {
                // Extension wrapper
                String name = path.substring(2);
                MappedWrapper[] oldWrappers = context.extensionWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers =
                    new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    context.extensionWrappers = newWrappers;
                }
            } else if (path.equals("/")) {
                // Default wrapper
                context.defaultWrapper = null;
            } else {
                // Exact wrapper
                String name;
                if (path.length() == 0) {
                    // Special case for the Context Root mapping which is
                    // treated as an exact match
                    name = "/";
                } else {
                    name = path;
                }
                MappedWrapper[] oldWrappers = context.exactWrappers;
                if (oldWrappers.length == 0) {
                    return;
                }
                MappedWrapper[] newWrappers =
                    new MappedWrapper[oldWrappers.length - 1];
                if (removeMap(oldWrappers, newWrappers, name)) {
                    context.exactWrappers = newWrappers;
                }
            }
        }
    }


    /**
     * Add a welcome file to the given context.
     *
     * @param hostName    The host where the given context can be found
     * @param contextPath The path of the given context
     * @param version     The version of the given context
     * @param welcomeFile The welcome file to add
     */
    public void addWelcomeFile(String hostName, String contextPath, String version,
            String welcomeFile) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        int len = contextVersion.welcomeResources.length + 1;
        String[] newWelcomeResources = new String[len];
        System.arraycopy(contextVersion.welcomeResources, 0, newWelcomeResources, 0, len - 1);
        newWelcomeResources[len - 1] = welcomeFile;
        contextVersion.welcomeResources = newWelcomeResources;
    }


    /**
     * Remove a welcome file from the given context.
     *
     * @param hostName    The host where the given context can be found
     * @param contextPath The path of the given context
     * @param version     The version of the given context
     * @param welcomeFile The welcome file to remove
     */
    public void removeWelcomeFile(String hostName, String contextPath,
            String version, String welcomeFile) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null || contextVersion.isPaused()) {
            return;
        }
        int match = -1;
        for (int i = 0; i < contextVersion.welcomeResources.length; i++) {
            if (welcomeFile.equals(contextVersion.welcomeResources[i])) {
                match = i;
                break;
            }
        }
        if (match > -1) {
            int len = contextVersion.welcomeResources.length - 1;
            String[] newWelcomeResources = new String[len];
            System.arraycopy(contextVersion.welcomeResources, 0, newWelcomeResources, 0, match);
            if (match < len) {
                System.arraycopy(contextVersion.welcomeResources, match + 1,
                        newWelcomeResources, match, len - match);
            }
            contextVersion.welcomeResources = newWelcomeResources;
        }
    }


    /**
     * Clear the welcome files for the given context.
     *
     * @param hostName    The host where the context to be cleared can be found
     * @param contextPath The path of the context to be cleared
     * @param version     The version of the context to be cleared
     */
    public void clearWelcomeFiles(String hostName, String contextPath, String version) {
        hostName = renameWildcardHost(hostName);
        ContextVersion contextVersion = findContextVersion(hostName, contextPath, version, false);
        if (contextVersion == null) {
            return;
        }
        contextVersion.welcomeResources = new String[0];
    }


    /**
     * Map the specified host name and URI, mutating the given mapping data.
     * 映射指定的主机名和 URI，改变给定的映射数据。
     * @param host Virtual host name 虚拟主机名，从uri中提取的，
     * @param uri URI
     * @param version The version, if any, included in the request to be mapped
     * @param mappingData This structure will contain the result of the mapping
     *                    operation
     * @throws IOException if the buffers are too small to hold the results of
     *                     the mapping.
     */
    public void map(MessageBytes host, MessageBytes uri, String version,
                    MappingData mappingData) throws IOException {

        // 一般情况下，需要查找的Host名称为请求的serverName，但是如果没有指定Host名称，那么将使用默认Host名称
        if (host.isNull()) {
            String defaultHostName = this.defaultHostName;
            if (defaultHostName == null) {
                return;
            }
            host.getCharChunk().append(defaultHostName);
        }
        host.toChars();
        uri.toChars();
        /**内部映射，映射指定的uri，刚开始进来，mappingData属性都是null的*/
        internalMap(host.getCharChunk(), uri.getCharChunk(), version, mappingData);
    }


    /**
     * Map the specified URI relative to the context,
     * mutating the given mapping data.
     *
     * @param context The actual context
     * @param uri URI
     * @param mappingData This structure will contain the result of the mapping
     *                    operation
     * @throws IOException if the buffers are too small to hold the results of
     *                     the mapping.
     */
    public void map(Context context, MessageBytes uri,
            MappingData mappingData) throws IOException {

        ContextVersion contextVersion =
                contextObjectToContextVersionMap.get(context);
        uri.toChars();
        CharChunk uricc = uri.getCharChunk();
        uricc.setLimit(-1);
        internalMapWrapper(contextVersion, uricc, mappingData);
    }


    // -------------------------------------------------------- Private Methods

    /**
     * Map the specified URI. 映射指定的URI。
     * @throws IOException
     */
    @SuppressWarnings("deprecation") // contextPath
    private final void internalMap(CharChunk host, CharChunk uri,
            String version, MappingData mappingData) throws IOException {

        /**url是统一资源定位符，需要带上协议，比如http，而uri是统一资源标识符，只要能表示应用的请求标识即可*/
        System.out.println("本次请求的uri是："+uri);
        if (mappingData.host != null) {
            /***
             * The legacy code (dating down at least to Tomcat 4.1) just
             * skipped all mapping work in this case. That behaviour has a risk
             * of returning an inconsistent result.
             * I do not see a valid use case for it.
             * 如果传进来的mappingData对象的host不等于空，那一定是有问题的，因为uri进来是要匹配host的，
             * 你不匹配上来就有host显然是有问题的。
             */
            throw new AssertionError();
        }

        /**Virtual host mapping 虚拟主机映射列表*/
        MappedHost[] hosts = this.hosts;
        /**第一步：基于request中获取的host从tomcat映射主机数组中进行匹配得到mappedHost对象*/
        MappedHost mappedHost = exactFindIgnoreCase(hosts, host);
        if (mappedHost == null) {
            // Note: Internally, the Mapper does not use the leading * on a
            //       wildcard host. This is to allow this shortcut.
            int firstDot = host.indexOf('.');
            if (firstDot > -1) {
                int offset = host.getOffset();
                try {
                    host.setOffset(firstDot + offset);
                    mappedHost = exactFindIgnoreCase(hosts, host);
                } finally {
                    // Make absolutely sure this gets reset
                    host.setOffset(offset);
                }
            }
            if (mappedHost == null) {
                /**如果匹配不到，就地取值拿默认的*/
                mappedHost = defaultHost;
                /**如果默认的都没有，那tomcat还工作个毛线啊，直接歇菜*/
                if (mappedHost == null) {
                    return;
                }
            }
        }

        /**如果匹配到了虚拟主机映射，就把匹配到的虚拟主机对象StandardHost取出来赋值给mappingData对象的属性host*/
        mappingData.host = mappedHost.object;

        /**如果uri空是无法匹配到具体的context对象和wrapper对象的，直接返回不处理了*/
        if (uri.isNull()) {
            // Can't map context or wrapper without a uri
            return;
        }

        uri.setLimit(-1);

        /**
         * Context mapping 应用（列表）映射，及一个映射主机包含一个应用列表
         * ContextList有一个重要的成员属性就是：MappedContext[]
         * {@link MappedContext} {@link MapElement}
         * 而MappedContext又继承于MapElement，MapElement中有一个成员 T object
         * 对MappedContext对象来说，其属性object实际指向的是StandardContext
         */
        ContextList contextList = mappedHost.contextList;
        MappedContext[] contexts = contextList.contexts;

        /**遍历下mappedHost下的所有映射应用*/
        if (contexts!=null && contexts.length>1){
            System.out.println("webapps目录下一共有"+contexts.length+"个application.");
            for (MappedContext context : contexts) {
                System.out.println("webapps应用名称："+context.name);
            }
        }

        /**第二步：匹配context，基于uri找到context的位置*/
        // 按照uri查找Mapper.Context最大可能匹配的位置pos
        int pos = find(contexts, uri);
        if (pos == -1) {
            return; // 找不到返回
        }

        int lastSlash = -1;
        int uriEnd = uri.getEnd();
        int length = -1;
        boolean found = false;
        MappedContext context = null;
        while (pos >= 0) {
            /**基于pos位置找到webapps下面的具体应用*/
            context = contexts[pos];
            /**下面这个必须走，因为tomcat的webapps下面，要想访问应用，必须在url路径中带上应用的name*/
            // 如果uri与MappedContext路径相等或者以MappedContext路径+“/”开头，均视为找到匹配的MappedContext
            if (uri.startsWith(context.name)) {
                /**注意，这里的length是应用名称的长度，如果是重定向的uri则两者并不相等，走重定向的uri长度 - length = 1*/
                length = context.name.length();
                if (uri.getLength() == length) {
                    found = true;
                    break;
                } else if (uri.startsWithIgnoreCase("/", length)) {
                    /**走这个，找到就break，跳出循环*/
                    found = true;
                    break;
                }
            }
            // 去除uri最后一个“/”之后的部分
            if (lastSlash == -1) {
                lastSlash = nthSlash(uri, contextList.nesting + 1);
            } else {
                lastSlash = lastSlash(uri);
            }
            uri.setEnd(lastSlash);
            // 按照uri查找Mapper.Context最大可能匹配的位置pos
            pos = find(contexts, uri);
        }
        uri.setEnd(uriEnd);

        if (!found) {
            // 如果第0个Mapper.Context名称为空字符串，则context=contexts[0]
            if (contexts[0].name.equals("")) {
                context = contexts[0];
            } else {
                context = null;
            }
        }
        if (context == null) {
            return;
        }
        // MappedContext存放了路径相同的所有版本的Context（ContextVersion），因此还需要对MappedContext版本进行处理，
        // 如果指定了版本号，则返回版本号相等的ContextVersion，否则返回版本号最大的
        // 最后将ContextVersion中维护的Context保存到MappingData
        /**设置request中的mappingData的contextPath的值*/
        mappingData.contextPath.setString(context.name);

        ContextVersion contextVersion = null;
        /**应用版本数组，每个ContextVersion都对应一个StandardContext*/
        ContextVersion[] contextVersions = context.versions;
        final int versionCount = contextVersions.length;
        /**第三步：匹配应用的版本。如果当前应用存在多个（ >1 ）版本，就按照版本精准匹配*/
        if (versionCount > 1) {
            Context[] contextObjects = new Context[contextVersions.length];
            for (int i = 0; i < contextObjects.length; i++) {
                contextObjects[i] = contextVersions[i].object;
            }
            /**如果存在多个版本，就收集下StandardContext对象列表*/
            mappingData.contexts = contextObjects;
            /**如果传入的版本不等于空，就按照版号本在匹配一把应用*/
            if (version != null) {
                contextVersion = exactFind(contextVersions, version);
            }
        }
        if (contextVersion == null) {
            /**
             * Return the latest version The versions array is known to contain at least one element
             * 就一个版本的，直接取出就行了
             */
            contextVersion = contextVersions[versionCount - 1];
        }
        /**这个赋值很关键很关键很关键，应用版本匹配到后，将其属性object取出赋值给mappingData的context对象*/
        mappingData.context = contextVersion.object;
        /**应用版本对象的插槽（就是索引位置，如果只有一个版本就是 1 ）*/
        mappingData.contextSlashCount = contextVersion.slashCount;

        // Wrapper mapping
        /**第四步：最后匹配wrapper (这个就非常关键了，匹配上了，后面分配servlet的时候会用到)*/
        if (!contextVersion.isPaused()) {
            internalMapWrapper(contextVersion, uri, mappingData);
        }
    }


    /**
     * Wrapper mapping. wrapper映射匹配，多调试几遍，你会发现这个方法写的有点啰嗦，不过条理还是很清晰的
     * @throws IOException if the buffers are too small to hold the results of
     *                     the mapping.
     */
    // 1、依据uri和Context路径计算MappedWrapper匹配路径，例如，如果Context路径为“/myapp”，uri为“/myapp/app1/index.jsp”，那么MappedWrapper的匹配路径为“/app1/index.jsp”；
    // 如果uri为“/myapp”，那么MappedWrapper的匹配路径为“/"
    // 2、先精确查找exactWrappers
    // 3、如果未找到，然后再按照前缀查找wildcardWrappers，算法与MappedContext查找类似，逐步降低精度
    // 4、如果未找到，然后按照扩展名查找extensionWrappers
    // 5、如果未找到，则尝试匹配欢迎文件列表，主要用于我们输入的请求路径是一个目录而非文件的情况
    private final void internalMapWrapper(ContextVersion contextVersion,
                                          CharChunk path,
                                          MappingData mappingData) throws IOException {

        /**uri path 的 start值 */
        int pathOffset = path.getOffset();
        /**uri path 的 end值 */
        int pathEnd = path.getEnd();
        /**是否有servlet path，默认有*/
        boolean noServletPath = false;
        /**应用版本对象中的path长度*/
        int length = contextVersion.path.length();
        /**如果应用版本对象中的path的长度正好等于uri的start-end，说明什么，说明uri只包含应用的名称，那就只能匹配默认的jspServlet了*/
        if (length == (pathEnd - pathOffset)) {
            /** uri 不带 servlet path*/
            noServletPath = true;
        }
        /**
         * servletPath 的 start偏移量值，start之前定位的是context，start之后可以定位到具体的wrapper也就是servlet
         * 比如uri是：/servlet-demo/hello
         * 则 length是contextPath的长度，也就是 /servlet-demo
         * 而 pathOffset + length 偏移量则是从 /hello开始的
         */
        int servletPath = pathOffset + length;
        /**
         * 重置（CharChunk）path的偏移量，涉及到start和end值的修改
         * 重置后path的值会变，主要看下面两个方法就知道了
         * 因为 {@link CharChunk#toString()} --》 {@link CharChunk#toStringInternal()}}
         * 最终path对象转成string的值就是去除了contextPath内容之外的剩余字符串
         */
        path.setOffset(servletPath);

        /**Rule 1 -- Exact Match  匹配规则1 ： 精准匹配*/
        MappedWrapper[] exactWrappers = contextVersion.exactWrappers;
        /**内部映射☞精准匹配Wrapper （如果exactWrappers数组不为空的话）*/
        internalMapExactWrapper(exactWrappers, path, mappingData);

        /**Rule 2 -- Prefix Match 匹配规则2：路径通配符匹配*/
        boolean checkJspWelcomeFiles = false;
        MappedWrapper[] wildcardWrappers = contextVersion.wildcardWrappers;
        /**如果精准匹配没有找到wrapper（找到了就将wrapper设置到mappingData的wrapper属性了），那就路径通配符匹配*/
        if (mappingData.wrapper == null) {
            /**通配符匹配*/
            internalMapWildcardWrapper(wildcardWrappers, contextVersion.nesting,path, mappingData);
            if (mappingData.wrapper != null && mappingData.jspWildCard) {
                char[] buf = path.getBuffer();
                if (buf[pathEnd - 1] == '/') {
                    /**
                     * Path ending in '/' was mapped to JSP servlet based on
                     * wildcard match (e.g., as specified in url-pattern of a
                     * jsp-property-group.
                     * Force the context's welcome files, which are interpreted
                     * as JSP files (since they match the url-pattern), to be
                     * considered. See Bugzilla 27664.
                     */
                    mappingData.wrapper = null;
                    checkJspWelcomeFiles = true;
                } else {
                    // See Bugzilla 27704
                    mappingData.wrapperPath.setChars(buf, path.getStart(),path.getLength());
                    mappingData.pathInfo.recycle();
                }
            }
        }

        /***
         * 条件1： 如果 精准匹配 和 通配符匹配 都没有匹配到wrapper
         * 条件2： 如果uri不带servlet部分的path，比如访问的就是应用的根路径，比如/contextPath
         * 条件3： 应用版本对象的object属性即StandardContext的mapperContextRootRedirectEnabled属性为true
         * mapperContextRootRedirectEnabled：开启映射应用的根地址重定向
         * 3个条件都满足，就在 "" 后面 补充一个 "/" ， 带个 "/" 就是重定向了
         */
        boolean redirect = mappingData.wrapper == null && noServletPath &&
            contextVersion.object.getMapperContextRootRedirectEnabled();

        if(redirect) {
            /***
             * The path is empty, redirect to "/"
             * 只有前端请求的uri访问的是应用根地址的时候才会导致传进来的path是""，这个时候noServletPath必为true
             * 那redirect为true也就顺理成章了，然后就是追加一个 "/" 准备重定向了
             * 这个追加会往path里面的buff（char[] ）添加新的char，这个buff存的是全量内容
             */
            path.append('/');
            pathEnd = path.getEnd();
            mappingData.redirectPath.setChars(path.getBuffer(), pathOffset, pathEnd - pathOffset);
            path.setEnd(pathEnd - 1);
            /**重新项设置完就直接返回了，不再往下走了*/
            return;
        }

        /**Rule 3 -- Extension Match 匹配规则3：扩展匹配，以扩展名的形式匹配URL，比如：*.jsp 或 *.jspx */
        MappedWrapper[] extensionWrappers = contextVersion.extensionWrappers;
        if (mappingData.wrapper == null && !checkJspWelcomeFiles) {
            internalMapExtensionWrapper(extensionWrappers, path, mappingData,
                    true);
        }

        /**Rule 4 -- Welcome resources processing for servlets 匹配规则4： 匹配欢迎页面（index.html index1.jsp ...etc）*/
        if (mappingData.wrapper == null) {
            boolean checkWelcomeFiles = checkJspWelcomeFiles;
            if (!checkWelcomeFiles) {
                char[] buf = path.getBuffer();
                checkWelcomeFiles = (buf[pathEnd - 1] == '/');
            }
            /***
             * conf/web.xml
             * <welcome-file-list>
             *     <welcome-file>index.html</welcome-file>
             *     <welcome-file>index.htm</welcome-file>
             *     <welcome-file>index1.jsp</welcome-file>
             *  </welcome-file-list>
             * 设置默认欢迎界面的路径是从根(web)路径开始查找。
             * 若在根路径（应用）中的文件夹找到（匹配）欢迎文件列表中的文件即可
             */
            if (checkWelcomeFiles) { /**如果访问的是欢迎页面的话，那就遍历匹配*/
                for (int i = 0; (i < contextVersion.welcomeResources.length) && (mappingData.wrapper == null); i++) {
                    path.setOffset(pathOffset);
                    path.setEnd(pathEnd);
                    path.append(contextVersion.welcomeResources[i], 0,contextVersion.welcomeResources[i].length());
                    path.setOffset(servletPath);

                    /***
                     * Rule 4a -- Welcome resources processing for exact macth
                     * 从精准wrappers里面匹配path对应的wrapper
                     */
                    internalMapExactWrapper(exactWrappers, path, mappingData);

                    /***
                     * Rule 4b -- Welcome resources processing for prefix match
                     * 从路径通配符wrappers中匹配path对应的wrapper
                     */
                    if (mappingData.wrapper == null) {
                        internalMapWildcardWrapper(wildcardWrappers, contextVersion.nesting,path, mappingData);
                    }

                    /***
                     * Rule 4c -- Welcome resources processing for physical folder
                     * 如果上面都没有匹配到wrapper的话，那就找应用跟路径下的物理文件进行匹配（index.html,index.htm,index1.jsp）
                     */
                    if (mappingData.wrapper == null && contextVersion.resources != null) {
                        String pathStr = path.toString();
                        WebResource file = contextVersion.resources.getResource(pathStr);
                        if (file != null && file.isFile()) {
                            internalMapExtensionWrapper(extensionWrappers, path,mappingData, true);
                            if (mappingData.wrapper == null && contextVersion.defaultWrapper != null) {
                                mappingData.wrapper =contextVersion.defaultWrapper.object;
                                mappingData.requestPath.setChars(path.getBuffer(), path.getStart(),path.getLength());
                                mappingData.wrapperPath.setChars(path.getBuffer(), path.getStart(),path.getLength());
                                mappingData.requestPath.setString(pathStr);
                                mappingData.wrapperPath.setString(pathStr);
                            }
                        }
                    }
                }
                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }
        }

        /** welcome file processing - take 2
         * Now that we have looked for welcome files with a physical
         * backing, now look for an extension mapping listed
         * but may not have a physical backing to it. This is for
         * the case of index.jsf, index.do, etc.
         * A watered down version of rule 4
         * 现在我们已经查找了具有物理支持的欢迎文件，现在查找列出的扩展映射，
         * 但可能没有物理支持。index后缀可能是jsf或者do等，这些是无法匹配上的。
         * 下面是对规则4的一个淡化版
         */
        if (mappingData.wrapper == null) {
            boolean checkWelcomeFiles = checkJspWelcomeFiles;
            if (!checkWelcomeFiles) {
                char[] buf = path.getBuffer();
                checkWelcomeFiles = (buf[pathEnd - 1] == '/');
            }
            if (checkWelcomeFiles) {
                for (int i = 0; (i < contextVersion.welcomeResources.length)
                         && (mappingData.wrapper == null); i++) {
                    path.setOffset(pathOffset);
                    path.setEnd(pathEnd);
                    path.append(contextVersion.welcomeResources[i], 0,
                                contextVersion.welcomeResources[i].length());
                    path.setOffset(servletPath);
                    internalMapExtensionWrapper(extensionWrappers, path,mappingData, false);
                }
                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }
        }

        /***
         * Rule 7 -- Default servlet
         * 都找不到，那就给个默认的wrapper吧，总不能uri过来没有servlet处理吧，默认的wrapper对应默认的DefaultServlet
         */
        if (mappingData.wrapper == null && !checkJspWelcomeFiles) {
            if (contextVersion.defaultWrapper != null) {
                mappingData.wrapper = contextVersion.defaultWrapper.object;
                mappingData.requestPath.setChars
                    (path.getBuffer(), path.getStart(), path.getLength());
                mappingData.wrapperPath.setChars
                    (path.getBuffer(), path.getStart(), path.getLength());
                mappingData.matchType = MappingMatch.DEFAULT;
            }
            // Redirection to a folder
            char[] buf = path.getBuffer();
            if (contextVersion.resources != null && buf[pathEnd -1 ] != '/') {
                String pathStr = path.toString();
                // Note: Check redirect first to save unnecessary getResource()
                //       call. See BZ 62968.
                if (contextVersion.object.getMapperDirectoryRedirectEnabled()) {
                    WebResource file;
                    // Handle context root
                    if (pathStr.length() == 0) {
                        file = contextVersion.resources.getResource("/");
                    } else {
                        file = contextVersion.resources.getResource(pathStr);
                    }
                    if (file != null && file.isDirectory()) {
                        // Note: this mutates the path: do not do any processing
                        // after this (since we set the redirectPath, there
                        // shouldn't be any)
                        path.setOffset(pathOffset);
                        path.append('/');
                        mappingData.redirectPath.setChars
                            (path.getBuffer(), path.getStart(), path.getLength());
                    } else {
                        mappingData.requestPath.setString(pathStr);
                        mappingData.wrapperPath.setString(pathStr);
                    }
                } else {
                    mappingData.requestPath.setString(pathStr);
                    mappingData.wrapperPath.setString(pathStr);
                }
            }
        }

        path.setOffset(pathOffset);
        path.setEnd(pathEnd);
    }


    /**
     * Exact mapping. 精准匹配
     */
    @SuppressWarnings("deprecation") // contextPath
    private final void internalMapExactWrapper(MappedWrapper[] wrappers, CharChunk path, MappingData mappingData) {
        MappedWrapper wrapper = exactFind(wrappers, path);
        /**如果找到wrapper就填充mappingData属性*/
        if (wrapper != null) {
            mappingData.requestPath.setString(wrapper.name);
            mappingData.wrapper = wrapper.object;
            if (path.equals("/")) {
                // Special handling for Context Root mapped servlet
                mappingData.pathInfo.setString("/");
                mappingData.wrapperPath.setString("");
                // This seems wrong but it is what the spec says...
                mappingData.contextPath.setString("");
                mappingData.matchType = MappingMatch.CONTEXT_ROOT;
            } else {
                mappingData.wrapperPath.setString(wrapper.name);
                mappingData.matchType = MappingMatch.EXACT;
            }
        }
    }


    /**
     * Wildcard mapping. 路径通配符匹配
     */
    private final void internalMapWildcardWrapper
        (MappedWrapper[] wrappers, int nesting, CharChunk path,
         MappingData mappingData) {

        int pathEnd = path.getEnd();

        int lastSlash = -1;
        int length = -1;
        int pos = find(wrappers, path);
        if (pos != -1) {
            boolean found = false;
            while (pos >= 0) {
                if (path.startsWith(wrappers[pos].name)) {
                    length = wrappers[pos].name.length();
                    if (path.getLength() == length) {
                        found = true;
                        break;
                    } else if (path.startsWithIgnoreCase("/", length)) {
                        found = true;
                        break;
                    }
                }
                if (lastSlash == -1) {
                    lastSlash = nthSlash(path, nesting + 1);
                } else {
                    lastSlash = lastSlash(path);
                }
                path.setEnd(lastSlash);
                pos = find(wrappers, path);
            }
            path.setEnd(pathEnd);
            if (found) {
                mappingData.wrapperPath.setString(wrappers[pos].name);
                if (path.getLength() > length) {
                    mappingData.pathInfo.setChars
                        (path.getBuffer(),
                         path.getOffset() + length,
                         path.getLength() - length);
                }
                mappingData.requestPath.setChars
                    (path.getBuffer(), path.getOffset(), path.getLength());
                mappingData.wrapper = wrappers[pos].object;
                mappingData.jspWildCard = wrappers[pos].jspWildCard;
                mappingData.matchType = MappingMatch.PATH;
            }
        }
    }


    /**
     * Extension mappings. 扩展名匹配
     *
     * @param wrappers          Set of wrappers to check for matches
     * @param path              Path to map
     * @param mappingData       Mapping data for result
     * @param resourceExpected  Is this mapping expecting to find a resource
     */
    private final void internalMapExtensionWrapper(MappedWrapper[] wrappers,
            CharChunk path, MappingData mappingData, boolean resourceExpected) {
        char[] buf = path.getBuffer();
        int pathEnd = path.getEnd();
        int servletPath = path.getOffset();
        int slash = -1;
        for (int i = pathEnd - 1; i >= servletPath; i--) {
            if (buf[i] == '/') {
                slash = i;
                break;
            }
        }
        if (slash >= 0) {
            /**如果 path = "/xd/index1.jsp"，这里period找到.的index位置即可*/
            int period = -1;
            for (int i = pathEnd - 1; i > slash; i--) {
                if (buf[i] == '.') {
                    period = i;
                    break;
                }
            }
            if (period >= 0) {
                path.setOffset(period + 1);
                path.setEnd(pathEnd);
                /** 精准查找wrapper，jsp匹配jspServlet，匹配到wrapper就ok了 */
                MappedWrapper wrapper = exactFind(wrappers, path);
                /**如果找到了*/
                if (wrapper != null && (resourceExpected || !wrapper.resourceOnly)) {
                    mappingData.wrapperPath.setChars(buf, servletPath, pathEnd - servletPath);
                    mappingData.requestPath.setChars(buf, servletPath, pathEnd - servletPath);
                    /**填充mappingData的wrapper属性 --> StandardWrapper*/
                    mappingData.wrapper = wrapper.object;
                    /**设置匹配类型为扩展名匹配*/
                    mappingData.matchType = MappingMatch.EXTENSION;
                }
                path.setOffset(servletPath);
                path.setEnd(pathEnd);
            }
        }
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int find(MapElement<T>[] map, CharChunk name) {
        return find(map, name, name.getStart(), name.getEnd());
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     * 在已排序的映射元素数组中查找给定其名称的映射元素。这将返回给定数组中最接近的次等项或相等项的索引
     */
    private static final <T> int find(MapElement<T>[] map, CharChunk name,
                                  int start, int end) {

        int a = 0;
        int b = map.length - 1;

        // Special cases: -1 and 0
        if (b == -1) {
            return -1;
        }

        if (compare(name, start, end, map[0].name) < 0 ) {
            return -1;
        }
        if (b == 0) {
            return 0;
        }

        int i = 0;
        while (true) {
            i = (b + a) >>> 1;
            int result = compare(name, start, end, map[i].name);
            if (result == 1) {
                a = i;
            } else if (result == 0) {
                return i;
            } else {
                b = i;
            }
            if ((b - a) == 1) {
                int result2 = compare(name, start, end, map[b].name);
                if (result2 < 0) {
                    return a;
                } else {
                    return b;
                }
            }
        }

    }

    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     * 在已排序的映射元素数组中查找给定其名称的映射元素。这将返回给定数组中最接近的次等项或相等项的索引。
     */
    private static final <T> int findIgnoreCase(MapElement<T>[] map, CharChunk name) {
        return findIgnoreCase(map, name, name.getStart(), name.getEnd());
    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     */
    private static final <T> int findIgnoreCase(MapElement<T>[] map, CharChunk name,
                                  int start, int end) {

        int a = 0;
        int b = map.length - 1;

        // Special cases: -1 and 0
        if (b == -1) {
            return -1;
        }
        if (compareIgnoreCase(name, start, end, map[0].name) < 0 ) {
            return -1;
        }
        if (b == 0) {
            return 0;
        }

        int i = 0;
        while (true) {
            i = (b + a) >>> 1;
            int result = compareIgnoreCase(name, start, end, map[i].name);
            if (result == 1) {
                a = i;
            } else if (result == 0) {
                return i;
            } else {
                b = i;
            }
            if ((b - a) == 1) {
                int result2 = compareIgnoreCase(name, start, end, map[b].name);
                if (result2 < 0) {
                    return a;
                } else {
                    return b;
                }
            }
        }

    }


    /**
     * Find a map element given its name in a sorted array of map elements.
     * This will return the index for the closest inferior or equal item in the
     * given array.
     * @see #exactFind(MapElement[], String)
     */
    private static final <T> int find(MapElement<T>[] map, String name) {

        int a = 0;
        int b = map.length - 1;

        // Special cases: -1 and 0 如果等于-1，说明映射元素数组里面没有元素啊
        if (b == -1) {
            return -1;
        }

        if (name.compareTo(map[0].name) < 0) {  // 按名称排序的，只需要比较第一个就知道后面有没有了
            return -1;
        }
        /**如果map里就一个元素，不用找了，直接返回索引0，返回到外面去判断*/
        if (b == 0) {
            return 0;
        }

        int i = 0;
        /**循环遍历*/
        while (true) {
            /**除以2，折半查找*/
            i = (b + a) >>> 1;
            /**比较首字母，首字母转为char然后相减*/
            int result = name.compareTo(map[i].name);
            if (result > 0) {
                a = i;
            } else if (result == 0) {
                return i;
            } else {
                b = i;
            }
            if ((b - a) == 1) {
                int result2 = name.compareTo(map[b].name);
                if (result2 < 0) {
                    return a;
                } else {
                    return b;
                }
            }
        }

    }


    /**
     * Find a map element given its name in a sorted array of map elements. This
     * will return the element that you were searching for. Otherwise it will
     * return <code>null</code>.
     * @see #find(MapElement[], String)
     */
    private static final <T, E extends MapElement<T>> E exactFind(E[] map,
            String name) {
        int pos = find(map, name);
        if (pos >= 0) {
            E result = map[pos];
            if (name.equals(result.name)) {
                return result;
            }
        }
        return null;
    }

    /**
     * Find a map element given its name in a sorted array of map elements. This
     * will return the element that you were searching for. Otherwise it will
     * return <code>null</code>.
     */
    private static final <T, E extends MapElement<T>> E exactFind(E[] map,CharChunk name) {
        int pos = find(map, name);
        if (pos >= 0) {
            E result = map[pos];
            if (name.equals(result.name)) {
                return result;
            }
        }
        return null;
    }

    /**
     * Find a map element given its name in a sorted array of map elements. This
     * will return the element that you were searching for. Otherwise it will
     * return <code>null</code>.
     * @see #findIgnoreCase(MapElement[], CharChunk)
     */
    private static final <T, E extends MapElement<T>> E exactFindIgnoreCase(E[] map, CharChunk name) {
        int pos = findIgnoreCase(map, name);
        if (pos >= 0) {
            E result = map[pos];
            if (name.equalsIgnoreCase(result.name)) {
                return result;
            }
        }
        return null;
    }


    /**
     * Compare given char chunk with String.
     * Return -1, 0 or +1 if inferior, equal, or superior to the String.
     */
    private static final int compare(CharChunk name, int start, int end,
                                     String compareTo) {
        int result = 0;
        char[] c = name.getBuffer();
        int len = compareTo.length();
        if ((end - start) < len) {
            len = end - start;
        }
        for (int i = 0; (i < len) && (result == 0); i++) {
            if (c[i + start] > compareTo.charAt(i)) {
                result = 1;
            } else if (c[i + start] < compareTo.charAt(i)) {
                result = -1;
            }
        }
        if (result == 0) {
            if (compareTo.length() > (end - start)) {
                result = -1;
            } else if (compareTo.length() < (end - start)) {
                result = 1;
            }
        }
        return result;
    }


    /**
     * Compare given char chunk with String ignoring case.
     * Return -1, 0 or +1 if inferior, equal, or superior to the String.
     */
    private static final int compareIgnoreCase(CharChunk name, int start, int end,
                                     String compareTo) {
        int result = 0;
        char[] c = name.getBuffer();
        int len = compareTo.length();
        if ((end - start) < len) {
            len = end - start;
        }
        for (int i = 0; (i < len) && (result == 0); i++) {
            if (Ascii.toLower(c[i + start]) > Ascii.toLower(compareTo.charAt(i))) {
                result = 1;
            } else if (Ascii.toLower(c[i + start]) < Ascii.toLower(compareTo.charAt(i))) {
                result = -1;
            }
        }
        if (result == 0) {
            if (compareTo.length() > (end - start)) {
                result = -1;
            } else if (compareTo.length() < (end - start)) {
                result = 1;
            }
        }
        return result;
    }


    /**
     * Find the position of the last slash in the given char chunk.
     */
    private static final int lastSlash(CharChunk name) {
        char[] c = name.getBuffer();
        int end = name.getEnd();
        int start = name.getStart();
        int pos = end;

        while (pos > start) {
            if (c[--pos] == '/') {
                break;
            }
        }

        return pos;
    }


    /**
     * Find the position of the nth slash, in the given char chunk.
     */
    private static final int nthSlash(CharChunk name, int n) {
        char[] c = name.getBuffer();
        int end = name.getEnd();
        int start = name.getStart();
        int pos = start;
        int count = 0;

        while (pos < end) {
            if ((c[pos++] == '/') && ((++count) == n)) {
                pos--;
                break;
            }
        }

        return pos;
    }


    /**
     * Return the slash count in a given string.
     */
    private static final int slashCount(String name) {
        int pos = -1;
        int count = 0;
        while ((pos = name.indexOf('/', pos + 1)) != -1) {
            count++;
        }
        return count;
    }


    /**
     * Insert into the right place in a sorted MapElement array, and prevent
     * duplicates.
     * 插入到已排序的MapElement数组中的正确位置，并防止重复。
     */
    private static final <T> boolean insertMap
        (MapElement<T>[] oldMap, MapElement<T>[] newMap, MapElement<T> newElement) {
        /**如果pos = -1 ，说明不存在*/
        int pos = find(oldMap, newElement.name);
        /**不等于-1说明在旧的映射里有内容，如果名称还一样的话，说明存在，就不插入了*/
        if ((pos != -1) && (newElement.name.equals(oldMap[pos].name))) {
            return false;
        }
        /**数组拷贝，将oldMap0开始的pos+1个元素拷贝到newMap索引为0的位置上*/
        System.arraycopy(oldMap, 0, newMap, 0, pos + 1);
        newMap[pos + 1] = newElement;
        System.arraycopy
            (oldMap, pos + 1, newMap, pos + 2, oldMap.length - pos - 1);
        return true;
    }


    /**
     * Insert into the right place in a sorted MapElement array.
     */
    private static final <T> boolean removeMap
        (MapElement<T>[] oldMap, MapElement<T>[] newMap, String name) {
        int pos = find(oldMap, name);
        if ((pos != -1) && (name.equals(oldMap[pos].name))) {
            System.arraycopy(oldMap, 0, newMap, 0, pos);
            System.arraycopy(oldMap, pos + 1, newMap, pos,
                             oldMap.length - pos - 1);
            return true;
        }
        return false;
    }


    /*
     * To simplify the mapping process, wild card hosts take the form
     * ".apache.org" rather than "*.apache.org" internally. However, for ease
     * of use the external form remains "*.apache.org". Any host name passed
     * into this class needs to be passed through this method to rename and
     * wild card host names from the external to internal form.
     */
    private static String renameWildcardHost(String hostName) {
        if (hostName != null && hostName.startsWith("*.")) {
            return hostName.substring(1);
        } else {
            return hostName;
        }
    }


    // ------------------------------------------------- MapElement Inner Class


    /***
     * 映射元素（不要联想到HashMap，毛线关系都没有）name就是object的名称，object就是对象，比如StandardWrapper等
     * @param <T>
     */
    protected abstract static class MapElement<T> {

        public final String name;
        public final T object;

        public MapElement(String name, T object) {
            this.name = name;
            this.object = object;
        }
    }


    // ------------------------------------------------------- Host Inner Class

    // Mapper对Host提供对应的封装类
    protected static final class MappedHost extends MapElement<Host> {

        /**应用列表*/
        public volatile ContextList contextList;

        /**
         * Link to the "real" MappedHost, shared by all aliases.
         * 因为一个Host，可能是真正的一个Host，也可能只是起了一个别名，而在Mapper中，两者都是MapperHost，
         * 只是真正的MapperHost的realHost是自身，而alias的MapperHost的realHost是其对应的真实的MapperHost
         */
        // 真实Host封装对象
        private final MappedHost realHost;

        /**
         * Links to all registered aliases, for easy enumeration. This field
         * is available only in the "real" MappedHost. In an alias this field
         * is <code>null</code>.
         * 所有注册的别名的MapperHost
         * 对于alias MapperHost，这个值为null。当一个 real MapperHost 没有alias的时候，这个值也为空
         */
        // 当封装的是一个Host且存在缩写时，aliases即为其对应缩写的封装对象
        private final List<MappedHost> aliases;

        /**
         * Constructor used for the primary Host
         *
         * @param name The name of the virtual host
         * @param host The host
         */
        public MappedHost(String name, Host host) {
            super(name, host);
            realHost = this;
            contextList = new ContextList();
            aliases = new CopyOnWriteArrayList<>();
        }

        /**
         * Constructor used for an Alias
         *
         * @param alias    The alias of the virtual host
         * @param realHost The host the alias points to
         */
        public MappedHost(String alias, MappedHost realHost) {
            super(alias, realHost.object);
            this.realHost = realHost;
            this.contextList = realHost.contextList;
            this.aliases = null;
        }

        public boolean isAlias() {
            return realHost != this;
        }

        public MappedHost getRealHost() {
            return realHost;
        }

        public String getRealHostName() {
            return realHost.name;
        }

        public Collection<MappedHost> getAliases() {
            return aliases;
        }

        public void addAlias(MappedHost alias) {
            aliases.add(alias);
        }

        public void addAliases(Collection<? extends MappedHost> c) {
            aliases.addAll(c);
        }

        public void removeAlias(MappedHost alias) {
            aliases.remove(alias);
        }
    }


    // ------------------------------------------------ ContextList Inner Class


    protected static final class ContextList {

        /**应用映射*/
        public final MappedContext[] contexts;
        public final int nesting;

        public ContextList() {
            this(new MappedContext[0], 0);
        }

        private ContextList(MappedContext[] contexts, int nesting) {
            this.contexts = contexts;
            this.nesting = nesting;
        }

        public ContextList addContext(MappedContext mappedContext,
                int slashCount) {
            MappedContext[] newContexts = new MappedContext[contexts.length + 1];
            if (insertMap(contexts, newContexts, mappedContext)) {
                return new ContextList(newContexts, Math.max(nesting,
                        slashCount));
            }
            return null;
        }

        public ContextList removeContext(String path) {
            MappedContext[] newContexts = new MappedContext[contexts.length - 1];
            if (removeMap(contexts, newContexts, path)) {
                int newNesting = 0;
                for (MappedContext context : newContexts) {
                    newNesting = Math.max(newNesting, slashCount(context.name));
                }
                return new ContextList(newContexts, newNesting);
            }
            return null;
        }
    }


    // ---------------------------------------------------- Context Inner Class

    // Mapper对Context提供对应的封装类
    // 为了支持Context多版本，Mapper提供了ContextVersion，当注册一个Context时，MappedContext名称为Context的路径，并且通过一个ContextVersion列表保存所有版本的Context
    protected static final class MappedContext extends MapElement<Void> {
        /**一个MappedContext中可能又有多个ContextVersion，表示多个版本的context*/
        public volatile ContextVersion[] versions;

        public MappedContext(String name, ContextVersion firstVersion) {
            super(name, null);
            this.versions = new ContextVersion[] { firstVersion };
        }
    }

    /**应用版本，tomcat处理request过程中匹配映射数据非常重要的一个类*/
    // ContextVersion保存了单个版本的Context，名称为具体的版本号
    // ContextVersion保存了一个具体Context及其包含的Wrapper封装对象
    // 包括默认Wrapper、精确匹配的Wrapper、通配符匹配的Wrapper、通过扩展名匹配的Wrapper
    protected static final class ContextVersion extends MapElement<Context> {
        /** context 的匹配路径*/
        public final String path;
        /** 没有直接的用处，主要目的是为了计算Context中的nesting*/
        public final int slashCount;
        public final WebResourceRoot resources;
        /**context的欢迎页面，也就是看看是否有匹配的默认首页文件 */
        public String[] welcomeResources;
        /** 默认匹配（仅只有一个，如果多个就不是默认了），当所有都不满足的时候指定的URL:比如：/ */
        public MappedWrapper defaultWrapper = null;
        /** 精准匹配（精准匹配，可能有多个，即一个context下有多组wrapper），完整的匹配到URL 比如 /servlet-demo/hello */
        public MappedWrapper[] exactWrappers = new MappedWrapper[0];
        /** 路径（通配符）匹配，匹配前面大部分URL，后面任意:比如：/servlet-demo/ */
        public MappedWrapper[] wildcardWrappers = new MappedWrapper[0];
        /** 扩展匹配，以扩展名的形式匹配URL，比如：*.jsp */
        public MappedWrapper[] extensionWrappers = new MappedWrapper[0];
        public int nesting = 0;
        /** 这个context是否还可用，是否被暂停 */
        private volatile boolean paused;

        public ContextVersion(String version, String path, int slashCount,
                Context context, WebResourceRoot resources,
                String[] welcomeResources) {
            super(version, context);
            this.path = path;
            this.slashCount = slashCount;
            this.resources = resources;
            this.welcomeResources = welcomeResources;
        }

        public boolean isPaused() {
            return paused;
        }

        public void markPaused() {
            paused = true;
        }
    }

    // ---------------------------------------------------- Wrapper Inner Class

    // Mapper对Wrapper提供对应的封装类
    protected static class MappedWrapper extends MapElement<Wrapper> {

        /**
         *  true if the wrapper corresponds to the JspServlet and the mapping path contains a wildcard; false otherwise
         *  如果wrapper对应的JspServlet映射路径中包含通配符，则为true，否则为false
         */
        public final boolean jspWildCard;
        /**
         * true if this wrapper always expects a physical resource to be present (such as a JSP)
         * 如果此包装器总是期望出现物理资源(例如JSP)，则为true。
         */
        public final boolean resourceOnly;

        public MappedWrapper(String name, Wrapper wrapper, boolean jspWildCard,
                boolean resourceOnly) {
            super(name, wrapper);
            this.jspWildCard = jspWildCard;
            this.resourceOnly = resourceOnly;
        }
    }
}
