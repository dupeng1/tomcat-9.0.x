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
 * The list of valid states for components that implement {@link Lifecycle}.
 * See {@link Lifecycle} for the state transition diagram.
 * 实现了Lifecycle接口的组件有效状态列表（定义的太TM的细了！！！）
 */
public enum LifecycleState {
    NEW(false, null), // 实例化
    INITIALIZING(false, Lifecycle.BEFORE_INIT_EVENT), // 初始化前
    INITIALIZED(false, Lifecycle.AFTER_INIT_EVENT),// 初始化后
    STARTING_PREP(false, Lifecycle.BEFORE_START_EVENT),// 启动前
    STARTING(true, Lifecycle.START_EVENT),// 启动中
    STARTED(true, Lifecycle.AFTER_START_EVENT),// 启动后
    STOPPING_PREP(true, Lifecycle.BEFORE_STOP_EVENT),// 停止前
    STOPPING(false, Lifecycle.STOP_EVENT),// 停止中
    STOPPED(false, Lifecycle.AFTER_STOP_EVENT),// 已停止
    DESTROYING(false, Lifecycle.BEFORE_DESTROY_EVENT),// 销毁中
    DESTROYED(false, Lifecycle.AFTER_DESTROY_EVENT),// 已销毁
    FAILED(false, null);// 失败

    private final boolean available;
    private final String lifecycleEvent;

    private LifecycleState(boolean available, String lifecycleEvent) {
        this.available = available;
        this.lifecycleEvent = lifecycleEvent;
    }

    /**
     * May the public methods other than property getters/setters and lifecycle
     * methods be called for a component in this state? It returns
     * <code>true</code> for any component in any of the following states:
     * <ul>
     * <li>{@link #STARTING}</li>
     * <li>{@link #STARTED}</li>
     * <li>{@link #STOPPING_PREP}</li>
     * </ul>
     *
     * @return <code>true</code> if the component is available for use,
     *         otherwise <code>false</code>
     */
    public boolean isAvailable() {
        return available;
    }

    public String getLifecycleEvent() {
        return lifecycleEvent;
    }
}
