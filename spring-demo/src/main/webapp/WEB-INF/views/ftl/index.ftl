<!DOCTYPE html>
<html lang="en">
<head>
    <title>Gradle + Spring MVC</title>

    <#assign coreCss="${request.contextPath}/resources/core/css/hello.css"  />
    <#assign bootstrapCss="${request.contextPath}/resources/core/css/bootstrap.min.css"  />
    <link href="${bootstrapCss}" rel="stylesheet"/>
    <link href="${coreCss}" rel="stylesheet"/>
</head>

<nav class="navbar navbar-inverse navbar-fixed-top">
    <div class="container">
        <div class="navbar-header">
            <a class="navbar-brand" href="#">Project Name</a>
        </div>
    </div>
</nav>

<div class="jumbotron">
    <div class="container">
        <h1>${title}</h1>
        <p>
            <#if msg??>
                Hello ${msg}
            <#else>
                Welcome Welcome!
            </#if>
        </p>
        <p>
            <a class="btn btn-primary btn-lg" href="#" role="button">Learn more</a>
        </p>
    </div>
</div>

<div class="container">

    <div class="row">
        <div class="col-md-4">
            <h2>Heading</h2>
            <p>ABC</p>
            <p>
                <a class="btn btn-default" href="#" role="button">View details</a>
            </p>
        </div>
        <div class="col-md-4">
            <h2>Heading</h2>
            <p>ABC</p>
            <p>
                <a class="btn btn-default" href="#" role="button">View details</a>
            </p>
        </div>
        <div class="col-md-4">
            <h2>Heading</h2>
            <p>ABC</p>
            <p>
                <a class="btn btn-default" href="#" role="button">View details</a>
            </p>
        </div>
    </div>


    <hr>
    <footer>
        <p>&copy; Mkyong.com 2015</p>
    </footer>
</div>

<#assign coreJs="${request.contextPath}/resources/core/js/hello.js"  />
<#assign bootstrapJs="${request.contextPath}/resources/core/js/bootstrap.min.js"  />
<script src="https://ajax.googleapis.com/ajax/libs/jquery/1.11.2/jquery.min.js"></script>
<script src="${coreJs}"></script>
<script src="${bootstrapJs}"></script>


</body>
</html>