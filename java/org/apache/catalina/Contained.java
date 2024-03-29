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
 * <p>Decoupling interface which specifies that an implementing class is
 * associated with at most one <strong>Container</strong> instance.</p>
 *
 * 解耦接口，指定一个实现类最多只能与一个 <strong>Container<strong> 实例相关联
 * 比如一个管道只能关联一个container，一个valve只能关联一个container等
 * 这个接口其实就是container的getter和setter
 *
 * @author Craig R. McClanahan
 * @author Peter Donald
 */
public interface Contained {

    /**
     * Get the {@link Container} with which this instance is associated.
     * 获取与当前实例相关联的容器对象（四大容器：Engine、Host、Context和Wrapper）
     * @return The Container with which this instance is associated or
     *         <code>null</code> if not associated with a Container
     */
    Container getContainer();


    /**
     * Set the <code>Container</code> with which this instance is associated.
     *
     * @param container The Container instance with which this instance is to
     *  be associated, or <code>null</code> to disassociate this instance
     *  from any Container
     */
    void setContainer(Container container);
}
