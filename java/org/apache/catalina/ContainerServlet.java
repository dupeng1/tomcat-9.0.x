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
 * A <b>ContainerServlet</b> is a servlet that has access to Catalina
 * internal functionality, and is loaded from the Catalina class loader
 * instead of the web application class loader.  The property setter
 * methods must be called by the container whenever a new instance of
 * this servlet is put into service.
 * ：是一个可以访问Catalina内部功能的servlet，
 *  并且是从Catalina类装入器而不是web应用程序类装入器中装入的。
 *  每当将此servlet的新实例放入服务中时，容器必须调用属性setter方法。
 * @author Craig R. McClanahan
 */
public interface ContainerServlet {

    /**
     * Obtain the Wrapper with which this Servlet is associated.
     * 获取与此Servlet关联的Wrapper。
     * @return The Wrapper with which this Servlet is associated.
     */
    public Wrapper getWrapper();


    /**
     * Set the Wrapper with which this Servlet is associated.
     * 设置与此Servlet关联的Wrapper。
     * @param wrapper The new associated Wrapper
     */
    public void setWrapper(Wrapper wrapper);
}
