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
package org.apache.catalina;


import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.UnavailableException;


/**
 * A <b>Wrapper</b> is a Container that represents an individual servlet
 * definition from the deployment descriptor of the web application.  It
 * provides a convenient mechanism to use Interceptors that see every single
 * request to the servlet represented by this definition.
 * <p>
 * Implementations of Wrapper are responsible for managing the servlet life
 * cycle for their underlying servlet class, including calling init() and
 * destroy() at appropriate times, as well as respecting the existence of
 * the SingleThreadModel declaration on the servlet class itself.
 * <p>
 * The parent Container attached to a Wrapper will generally be an
 * implementation of Context, representing the servlet context (and
 * therefore the web application) within which this servlet executes.
 * <p>
 * Child Containers are not allowed on Wrapper implementations, so the
 * <code>addChild()</code> method should throw an
 * <code>IllegalArgumentException</code>.
 *
 * A <b>Wrapper<b>是一个容器，表示来自web应用程序部署描述符的单个servlet定义。
 * 它提供了一种使用拦截器的方便机制，拦截器可以查看此定义表示的对servlet的每个请求。
 * Wrapper的实现负责管理底层servlet类的servlet生命周期，包括在适当的时候调用init()和destroy()，
 * 以及尊重servlet类本身的SingleThreadModel声明的存在。
 * 附加到Wrapper的父容器通常是Context的实现，表示servlet上下文(因此是web应用程序)，servlet在其中执行。
 * 子容器在Wrapper实现上是不允许的，（Wrapper可以看做是Catalina层次结构中最下层的容器，因此其无法再添加child容器）
 * 因此addChild()方法应该抛出一个IllegalArgumentException。
 *
 * @author Craig R. McClanahan
 */
public interface Wrapper extends Container {

    /**
     * Container event for adding a wrapper.
     */
    public static final String ADD_MAPPING_EVENT = "addMapping";

    /**
     * Container event for removing a wrapper.
     */
    public static final String REMOVE_MAPPING_EVENT = "removeMapping";

    // ------------------------------------------------------------- Properties


    /**
     * @return the available date/time for this servlet, in milliseconds since
     * the epoch.  If this date/time is in the future, any request for this
     * servlet will return an SC_SERVICE_UNAVAILABLE error.  If it is zero,
     * the servlet is currently available.  A value equal to Long.MAX_VALUE
     * is considered to mean that unavailability is permanent.
     */
    public long getAvailable();


    /**
     * Set the available date/time for this servlet, in milliseconds since the
     * epoch.  If this date/time is in the future, any request for this servlet
     * will return an SC_SERVICE_UNAVAILABLE error.  A value equal to
     * Long.MAX_VALUE is considered to mean that unavailability is permanent.
     *
     * @param available The new available date/time
     */
    public void setAvailable(long available);


    /**
     * @return the load-on-startup order value (negative value means
     * load on first call).
     * 返回启动时加载顺序值(负值表示第一次调用时加载)。
     */
    public int getLoadOnStartup();


    /**
     * Set the load-on-startup order value (negative value means
     * load on first call).
     *
     * @param value New load-on-startup value
     */
    public void setLoadOnStartup(int value);


    /**
     * @return the run-as identity for this servlet.
     */
    public String getRunAs();


    /**
     * Set the run-as identity for this servlet.
     *
     * @param runAs New run-as identity value
     */
    public void setRunAs(String runAs);


    /**
     * @return the fully qualified servlet class name for this servlet.
     * 返回此servlet的完全限定servlet类名
     */
    public String getServletClass();


    /**
     * Set the fully qualified servlet class name for this servlet.
     *
     * @param servletClass Servlet class name
     */
    public void setServletClass(String servletClass);


    /**
     * Gets the names of the methods supported by the underlying servlet.
     *
     * This is the same set of methods included in the Allow response header
     * in response to an OPTIONS request method processed by the underlying
     * servlet.
     *
     * @return Array of names of the methods supported by the underlying
     *         servlet
     * 获取底层servlet支持的方法的名称。这与Allow响应头中包含的方法集相同，用于响应底层servlet处理的OPTIONS请求方法
     * @throws ServletException If the target servlet cannot be loaded
     */
    public String[] getServletMethods() throws ServletException;


    /**
     * @return <code>true</code> if this Servlet is currently unavailable.
     */
    public boolean isUnavailable();


    /**
     * @return the associated Servlet instance.
     * 获取与此Wrapper关联的Servlet实例
     */
    public Servlet getServlet();


    /**
     * Set the associated Servlet instance
     *
     * @param servlet The associated Servlet
     */
    public void setServlet(Servlet servlet);

    // --------------------------------------------------------- Public Methods


    /**
     * Add a new servlet initialization parameter for this servlet.
     *
     * @param name Name of this initialization parameter to add
     * @param value Value of this initialization parameter to add
     */
    public void addInitParameter(String name, String value);


    /**
     * Add a mapping associated with the Wrapper.
     *
     * @param mapping The new wrapper mapping
     */
    public void addMapping(String mapping);


    /**
     * Add a new security role reference record to the set of records for
     * this servlet.
     *
     * @param name Role name used within this servlet
     * @param link Role name used within the web application
     */
    public void addSecurityReference(String name, String link);


    /**
     * Allocate an initialized instance of this Servlet that is ready to have
     * its <code>service()</code> method called.  If the Servlet class does
     * not implement <code>SingleThreadModel</code>, the (only) initialized
     * instance may be returned immediately.  If the Servlet class implements
     * <code>SingleThreadModel</code>, the Wrapper implementation must ensure
     * that this instance is not allocated again until it is deallocated by a
     * call to <code>deallocate()</code>.
     * 负责定位该包装器表示的 servlet 的实例
     * Allocate 方法必须考虑一个 servlet 是否实现了{@link javax.servlet.SingleThreadModel}接口
     * @exception ServletException if the Servlet init() method threw
     *  an exception
     * @exception ServletException if a loading error occurs
     * @return a new Servlet instance
     */
    public Servlet allocate() throws ServletException;


    /**
     * Return this previously allocated servlet to the pool of available
     * instances.  If this servlet class does not implement SingleThreadModel,
     * no action is actually required.
     *
     * @param servlet The servlet to be returned
     *
     * @exception ServletException if a deallocation error occurs
     */
    public void deallocate(Servlet servlet) throws ServletException;


    /**
     * @return the value for the specified initialization parameter name,
     * if any; otherwise return <code>null</code>.
     *
     * @param name Name of the requested initialization parameter
     */
    public String findInitParameter(String name);


    /**
     * @return the names of all defined initialization parameters for this
     * servlet.
     */
    public String[] findInitParameters();


    /**
     * @return the mappings associated with this wrapper.
     */
    public String[] findMappings();


    /**
     * @return the security role link for the specified security role
     * reference name, if any; otherwise return <code>null</code>.
     *
     * @param name Security role reference used within this servlet
     */
    public String findSecurityReference(String name);


    /**
     * @return the set of security role reference names associated with
     * this servlet, if any; otherwise return a zero-length array.
     */
    public String[] findSecurityReferences();


    /**
     * Increment the error count value used when monitoring.
     */
    public void incrementErrorCount();


    /**
     * Load and initialize an instance of this Servlet, if there is not already
     * at least one initialized instance.  This can be used, for example, to
     * load Servlets that are marked in the deployment descriptor to be loaded
     * at server startup time.
     * 加载并初始化这个Servlet的一个实例，如果还没有至少一个初始化的实例的话。
     * 例如，这可以用于加载部署描述符中标记的servlet，以便在服务器启动时加载。
     * @exception ServletException if the Servlet init() method threw
     *  an exception or if some other loading problem occurs
     */
    public void load() throws ServletException;


    /**
     * Remove the specified initialization parameter from this Servlet.
     *
     * @param name Name of the initialization parameter to remove
     */
    public void removeInitParameter(String name);


    /**
     * Remove a mapping associated with the wrapper.
     *
     * @param mapping The pattern to remove
     */
    public void removeMapping(String mapping);


    /**
     * Remove any security role reference for the specified role name.
     *
     * @param name Security role used within this servlet to be removed
     */
    public void removeSecurityReference(String name);


    /**
     * Process an UnavailableException, marking this Servlet as unavailable
     * for the specified amount of time.
     *
     * @param unavailable The exception that occurred, or <code>null</code>
     *  to mark this Servlet as permanently unavailable
     */
    public void unavailable(UnavailableException unavailable);


    /**
     * Unload all initialized instances of this servlet, after calling the
     * <code>destroy()</code> method for each instance.  This can be used,
     * for example, prior to shutting down the entire servlet engine, or
     * prior to reloading all of the classes from the Loader associated with
     * our Loader's repository.
     *
     * @exception ServletException if an unload error occurs
     */
    public void unload() throws ServletException;


    /**
     * @return the multi-part configuration for the associated Servlet. If no
     * multi-part configuration has been defined, then <code>null</code> will be
     * returned.
     */
    public MultipartConfigElement getMultipartConfigElement();


    /**
     * Set the multi-part configuration for the associated Servlet. To clear the
     * multi-part configuration specify <code>null</code> as the new value.
     *
     * @param multipartConfig The configuration associated with the Servlet
     */
    public void setMultipartConfigElement(
            MultipartConfigElement multipartConfig);

    /**
     * Does the associated Servlet support async processing? Defaults to
     * <code>false</code>.
     *
     * @return <code>true</code> if the Servlet supports async
     */
    public boolean isAsyncSupported();

    /**
     * Set the async support for the associated Servlet.
     *
     * @param asyncSupport the new value
     */
    public void setAsyncSupported(boolean asyncSupport);

    /**
     * Is the associated Servlet enabled? Defaults to <code>true</code>.
     *
     * @return <code>true</code> if the Servlet is enabled
     */
    public boolean isEnabled();

    /**
     * Sets the enabled attribute for the associated servlet.
     *
     * @param enabled the new value
     */
    public void setEnabled(boolean enabled);

    /**
     * Is the Servlet overridable by a ServletContainerInitializer?
     *
     * @return <code>true</code> if the Servlet can be overridden in a ServletContainerInitializer
     */
    public boolean isOverridable();

    /**
     * Sets the overridable attribute for this Servlet.
     *
     * @param overridable the new value
     */
    public void setOverridable(boolean overridable);
}
