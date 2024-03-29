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
 * A <b>Cluster</b> works as a Cluster client/server for the local host
 * Different Cluster implementations can be used to support different
 * ways to communicate within the Cluster. A Cluster implementation is
 * responsible for setting up a way to communicate within the Cluster
 * and also supply "ClientApplications" with <code>ClusterSender</code>
 * used when sending information in the Cluster and
 * <code>ClusterInfo</code> used for receiving information in the Cluster.
 * 集群作为本地主机的集群客户端/服务器工作 不同的集群实现可用于支持集群内不同的通信方式。
 * Cluster 实现负责设置在 Cluster 内进行通信的方式，并且还提供“ClientApplications”以及在 Cluster
 * 中发送信息时使用的ClusterSender用于在 Cluster 中接收信息
 * Tomcat集群模式，可以设置不同Tomcat之间的session共享，也就是用户访问tomcatA，登录tomcatA会将登录的用户会话信息
 * 同步到tomcatB上，同步的方式是广播，tomcat官方建议，不超过4个节点可以用，超过了用这种就会耗性能，消耗带宽，出现网络延迟等问题
 * 默认实现类是 {@link org.apache.catalina.ha.tcp.SimpleTcpCluster}
 * @author Bip Thelin
 * @author Remy Maucherat
 */
public interface Cluster extends Contained {

    /**
     * Return the name of the cluster that this Server is currently
     * configured to operate within.
     *
     * @return The name of the cluster associated with this server
     */
    public String getClusterName();


    /**
     * Set the name of the cluster to join, if no cluster with
     * this name is present create one.
     *
     * @param clusterName The clustername to join
     */
    public void setClusterName(String clusterName);


    /**
     * Create a new manager which will use this cluster to replicate its
     * sessions.
     *
     * @param name Name (key) of the application with which the manager is
     * associated
     *
     * @return The newly created Manager instance
     */
    public Manager createManager(String name);


    /**
     * Register a manager with the cluster. If the cluster is not responsible
     * for creating a manager, then the container will at least notify the
     * cluster that this manager is participating in the cluster.
     * @param manager Manager
     */
    public void registerManager(Manager manager);


    /**
     * Removes a manager from the cluster
     * @param manager Manager
     */
    public void removeManager(Manager manager);


    /**
     * Execute a periodic task, such as reloading, etc. This method will be
     * invoked inside the classloading context of this container. Unexpected
     * throwables will be caught and logged.
     */
    public void backgroundProcess();
}
