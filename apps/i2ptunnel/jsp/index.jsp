<%@page contentType="text/html" %>
<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">

<html><head>
<title>I2PTunnel status</title>
</head><body>

<jsp:useBean class="net.i2p.i2ptunnel.WebStatusPageHelper" id="helper" scope="request" />
<jsp:setProperty name="helper" property="*" />
<h2>Messages since last page load:</h2>
<b><jsp:getProperty name="helper" property="actionResults" /></b>
 
<jsp:getProperty name="helper" property="summaryList" />

<form action="edit.jsp">
<b>Add new:</b> 
 <select name="type">
  <option value="httpclient">HTTP proxy</option>
  <option value="client">Client tunnel</option>
  <option value="server">Server tunnel</option>
  <option value="httpserver">HTTP server tunnel</option>
 </select> <input type="submit" value="GO" />
</form>

</body>
</html>
