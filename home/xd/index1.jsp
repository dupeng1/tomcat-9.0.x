<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib uri="http://tomcat.apache.org/jsp2-example-taglib" prefix="ELFunc" %>
<html>
<head>
    <title>通过XML描述符部署应用</title>
</head>
<body>
<%
    String name="appleyk";
    /**EL函数只能处理四大域中的属性值及String常量*/
    pageContext.setAttribute("name", name);
%>
<span>计算结果：${1+1}</span>
<span>调用自定义的El函数类实现字符串转大写：${ELFunc:strToUpper(name)}</span>
<h1>通过XML描述符部署应用</h1>
</body>
</html>
