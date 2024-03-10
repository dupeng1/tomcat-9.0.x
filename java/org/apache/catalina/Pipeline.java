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

import java.util.Set;

/**
 * <p>Interface describing a collection of Valves that should be executed
 * in sequence when the <code>invoke()</code> method is invoked.  It is
 * required that a Valve somewhere in the pipeline (usually the last one)
 * must process the request and create the corresponding response, rather
 * than trying to pass the request on.</p>
 *
 * <p>There is generally a single Pipeline instance associated with each
 * Container.  The container's normal request processing functionality is
 * generally encapsulated in a container-specific Valve, which should always
 * be executed at the end of a pipeline.  To facilitate this, the
 * <code>setBasic()</code> method is provided to set the Valve instance that
 * will always be executed last.  Other Valves will be executed in the order
 * that they were added, before the basic Valve is executed.</p>
 *
 * @author Craig R. McClanahan
 * @author Peter Donald
 */
public interface Pipeline extends Contained {

    /**
     * @return the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).
     * 获取基本阀门，即管道中最后一个执行的阀门，也就是尾结点
     */
    public Valve getBasic();


    /**
     * <p>Set the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).  Prior to setting the basic Valve,
     * the Valve's <code>setContainer()</code> will be called, if it
     * implements <code>Contained</code>, with the owning Container as an
     * argument.  The method may throw an <code>IllegalArgumentException</code>
     * if this Valve chooses not to be associated with this Container, or
     * <code>IllegalStateException</code> if it is already associated with
     * a different Container.</p>
     * 设置基本阀门，但是有条件判断，就是判断当前valve是否关联container或者关联了不同的container
     * @param valve Valve to be distinguished as the basic Valve
     */
    public void setBasic(Valve valve);


    /**
     * 添加阀门
     * 在与此 Container 关联的管道末端添加一个新 Valve。在添加 Valve 之前，
     * 如果 Valve 实现了 <code>Contained<code>，则将调用 Valve 的 <code>setContainer()<code> 方法，
     * 并将拥有的 Container 作为参数。如果此 Valve 选择不与此 Container 关联，则该方法可能会抛出>IllegalStateException
     *
     * <p>Add a new Valve to the end of the pipeline associated with this
     * Container.  Prior to adding the Valve, the Valve's
     * <code>setContainer()</code> method will be called, if it implements
     * <code>Contained</code>, with the owning Container as an argument.
     * The method may throw an
     * <code>IllegalArgumentException</code> if this Valve chooses not to
     * be associated with this Container, or <code>IllegalStateException</code>
     * if it is already associated with a different Container.</p>
     *
     * <p>Implementation note: Implementations are expected to trigger the
     * {@link Container#ADD_VALVE_EVENT} for the associated container if this
     * call is successful.</p>
     *
     * @param valve Valve to be added
     *
     * @exception IllegalArgumentException if this Container refused to
     *  accept the specified Valve
     * @exception IllegalArgumentException if the specified Valve refuses to be
     *  associated with this Container
     * @exception IllegalStateException if the specified Valve is already
     *  associated with a different Container
     */
    public void addValve(Valve valve);


    /**
     * 返回管道中与此容器关联的一组阀门，包括基本阀门（如果有）。如果没有这样的阀门，则返回一个长度为零的数组
     * @return the set of Valves in the pipeline associated with this
     * Container, including the basic Valve (if any).  If there are no
     * such Valves, a zero-length array is returned.
     */
    public Valve[] getValves();


    /**
     * 如果找到，则从与此 Container 关联的管道中删除指定的 Valve；否则，什么也不做。如果找到并删除了 Valve，
     * 且其实现了 <code>Contained<code>类， 则会调用 Valve 的 <code>setContainer(null)<code> 方法。
     * Remove the specified Valve from the pipeline associated with this
     * Container, if it is found; otherwise, do nothing.  If the Valve is
     * found and removed, the Valve's <code>setContainer(null)</code> method
     * will be called if it implements <code>Contained</code>.
     *
     * <p>Implementation note: Implementations are expected to trigger the
     * {@link Container#REMOVE_VALVE_EVENT} for the associated container if this
     * call is successful.</p>
     *
     * @param valve Valve to be removed
     */
    public void removeValve(Valve valve);


    /**
     * @return the Valve instance that has been distinguished as the basic
     * Valve for this Pipeline (if any).
     * 获取第一个阀门（这玩意一看就知道pipeline是一个链表）
     */
    public Valve getFirst();


    /**
     * Returns true if all the valves in this pipeline support async, false otherwise
     * @return true if all the valves in this pipeline support async, false otherwise
     * 如果此管道中的所有阀门都支持异步，则返回 true，否则返回 false
     */
    public boolean isAsyncSupported();


    /**
     * Identifies the Valves, if any, in this Pipeline that do not support
     * async.
     * 查找非异步执行的所有阀门，并放置到result参数中，所以result不允许为null（也就是搞一个空列表）
     * @param result The Set to which the fully qualified class names of each
     *               Valve in this Pipeline that does not support async will be
     *               added
     */
    public void findNonAsyncValves(Set<String> result);
}
