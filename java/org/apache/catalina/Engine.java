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

/**
 * An <b>Engine</b> is a Container that represents the entire Catalina servlet
 * engine.  It is useful in the following types of scenarios:
 * <ul>
 * <li>You wish to use Interceptors that see every single request processed
 *     by the entire engine.
 * <li>You wish to run Catalina in with a standalone HTTP connector, but still
 *     want support for multiple virtual hosts.
 * </ul>
 * In general, you would not use an Engine when deploying Catalina connected
 * to a web server (such as Apache), because the Connector will have
 * utilized the web server's facilities to determine which Context (or
 * perhaps even which Wrapper) should be utilized to process this request.
 * <p>
 * The child containers attached to an Engine are generally implementations
 * of Host (representing a virtual host) or Context (representing individual
 * an individual servlet context), depending upon the Engine implementation.
 * <p>
 * If used, an Engine is always the top level Container in a Catalina
 * hierarchy. Therefore, the implementation's <code>setParent()</code> method
 * should throw <code>IllegalArgumentException</code>.
 *
 * <b>Engine<b>是表示整个Catalina servlet引擎的容器。
 * 你希望使用拦截器来查看整个引擎处理的每个请求。
 * 您希望使用独立的HTTP连接器运行Catalina，但仍然希望支持多个虚拟主机。
 * 一般来说，当部署连接到web服务器(如Apache)的Catalina时，您不会使用引擎，
 * 因为连接器将利用web服务器的设施来确定应该使用哪个上下文(甚至可能是哪个包装器)来处理这个请求。
 * 附加到引擎的子容器通常是主机(表示虚拟主机)或上下文(表示单个servlet上下文)的实现，
 * 这取决于引擎的实现。如果使用，引擎始终是Catalina层次结构中的顶级容器。因此，
 * 实现的方法setParent()应该抛出IllegalArgumentException。
 *
 * @author Craig R. McClanahan
 */
public interface Engine extends Container {

    /**
     * @return the default host name for this Engine.
     * 获取默认的虚拟主机名（默认是：localhost）
     */
    public String getDefaultHost();


    /**
     * Set the default hostname for this Engine.
     * 设置默认的虚拟主机名
     * @param defaultHost The new default host
     */
    public void setDefaultHost(String defaultHost);


    /**
     * @return the JvmRouteId for this engine.
     */
    public String getJvmRoute();


    /**
     * Set the JvmRouteId for this engine.
     *
     * @param jvmRouteId the (new) JVM Route ID. Each Engine within a cluster
     *        must have a unique JVM Route ID.
     */
    public void setJvmRoute(String jvmRouteId);


    /**
     * @return the <code>Service</code> with which we are associated (if any).
     * 获取与当前引擎关联的Service组件
     */
    public Service getService();


    /**
     * Set the <code>Service</code> with which we are associated (if any).
     * 设置我们关联的Service(如果有的话)。
     * @param service The service that owns this Engine
     */
    public void setService(Service service);
}
