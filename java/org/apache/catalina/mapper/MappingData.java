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

import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.tomcat.util.buf.MessageBytes;

import javax.servlet.http.MappingMatch;

/**
 * Mapping data.
 * url映射后的数据，表示一个uri具体映射到哪个host，哪个context，哪个wrapper。
 * @author Remy Maucherat
 */
public class MappingData {
    /**一个uri仅能匹配到一个虚拟主机，对应StandardHost*/
    public Host host = null;
    /**如果部署的应用就一个版本，那这个context就不为null，对应StandardContext*/
    public Context context = null;
    public int contextSlashCount = 0;
    /**如果部署的应用存在多个版本，那这个contexts就不为null，一般情况下部署的应用就一个*/
    public Context[] contexts = null;
    /**uri最后匹配到的wrapper对象，对应StandardWrapper*/
    public Wrapper wrapper = null;
    /**映射路径中是否包含通配符，否则为false不包含*/
    public boolean jspWildCard = false;

    /**
     * @deprecated Unused. This will be removed in Tomcat 10.
     */
    @Deprecated
    public final MessageBytes contextPath = MessageBytes.newInstance();
    public final MessageBytes requestPath = MessageBytes.newInstance();
    public final MessageBytes wrapperPath = MessageBytes.newInstance();
    public final MessageBytes pathInfo = MessageBytes.newInstance();

    /**重定向*/
    public final MessageBytes redirectPath = MessageBytes.newInstance();

    // Fields used by ApplicationMapping to implement javax.servlet.http.HttpServletMapping
    public MappingMatch matchType = null;

    public void recycle() {
        host = null;
        context = null;
        contextSlashCount = 0;
        contexts = null;
        wrapper = null;
        jspWildCard = false;
        contextPath.recycle();
        requestPath.recycle();
        wrapperPath.recycle();
        pathInfo.recycle();
        redirectPath.recycle();
        matchType = null;
    }
}
