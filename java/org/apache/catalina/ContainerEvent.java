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

import java.util.EventObject;

/**
 * General event for notifying listeners of significant changes on a Container.
 * 将容器上的重大更改通知监听器的一般事件。
 * EventObject： jdk自带的，像Spring框架中的ApplicationEvent也是继承了EventObject类
 *               这个事件类主要是定义事件反生的源头，比如tomcat中发生源就是对应的组件容器，
 *               而spring事件发生源就是ioc容器！
 * @author Craig R. McClanahan
 */
public final class ContainerEvent extends EventObject {

    private static final long serialVersionUID = 1L;

    /**
     * The event data associated with this event.
     * 事件数据
     */
    private final Object data;


    /**
     * The event type this instance represents.
     * 事件类型
     */
    private final String type;


    /**
     * Construct a new ContainerEvent with the specified parameters.
     *
     * @param container Container on which this event occurred
     * @param type Event type
     * @param data Event data
     */
    public ContainerEvent(Container container, String type, Object data) {
        super(container);
        this.type = type;
        this.data = data;
    }


    /**
     * Return the event data of this event.
     *
     * @return The data, if any, associated with this event.
     */
    public Object getData() {
        return this.data;
    }


    /**
     * Return the Container on which this event occurred.
     *
     * @return The Container on which this event occurred.
     */
    public Container getContainer() {
        return (Container) getSource();
    }


    /**
     * Return the event type of this event.
     *
     * @return The event type of this event. Although this is a String, it is
     *         safe to rely on the value returned by this method remaining
     *         consistent between point releases.
     */
    public String getType() {
        return this.type;
    }


    /**
     * Return a string representation of this event.
     */
    @Override
    public String toString() {
        return "ContainerEvent['" + getContainer() + "','" +
                getType() + "','" + getData() + "']";
    }
}
