package com.java.myapp;

import java.util.Map;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/** Observes a submitted filter's response without retaining transport URLs, bodies or account data. */
final class PromsPageActivity {
    private static final String KEY="__trueOpsFilterActivityV3";
    private static final String INSTALL=String.join("\n",
            "var key=arguments[0],query=arguments[1],s=window[key];",
            "if(!s){",
            "  s={generation:0,current:null,scope:null,tables:[],timers:{}};window[key]=s;",
            "  var nativeTimeout=window.setTimeout,nativeClear=window.clearTimeout;",
            "  function mark(g){if(!g)return;s.scope=g;nativeTimeout(function(){if(s.scope===g)s.scope=null;},0);}s.mark=mark;",
            "  function scalar(v,q){if(v===null||typeof v==='undefined')return false;return String(v).trim().replace(/^%+|%+$/g,'').toLowerCase()===q.toLowerCase();}",
            "  function objectQuery(value,q){if(value===null||typeof value==='undefined')return false;if(typeof value!=='object')return scalar(value,q);for(var k in value){if(Object.prototype.hasOwnProperty.call(value,k)&&objectQuery(value[k],q))return true;}return false;}",
            "  function carriesQuery(value,q){",
            "    if(!q||value===null||typeof value==='undefined')return false;",
            "    if(typeof value==='object'&&typeof value.forEach==='function'){var found=false;value.forEach(function(v){if(scalar(v,q))found=true;});return found;}",
            "    if(typeof value!=='string')return objectQuery(value,q);",
            "    if(scalar(value,q))return true;",
            "    try{if(objectQuery(JSON.parse(value),q))return true;}catch(ignored){}",
            "    try{var u=new URL(value,location.href),found=false;u.searchParams.forEach(function(v){if(scalar(v,q))found=true;});if(found)return true;var parts=u.pathname.split('/');for(var i=0;i<parts.length;i++)if(scalar(decodeURIComponent(parts[i]),q))return true;}catch(ignored){}",
            "    try{var form=new URLSearchParams(value),found=false;form.forEach(function(v){if(scalar(v,q))found=true;});return found;}catch(ignored){}return false;",
            "  }",
            "  function group(matched){var g=s.current;return g&&(matched||s.scope===g)?g:null;}",
            "  function done(g,failed,matched){if(!g)return;mark(g);g.pending--;g.completed++;if(matched)g.matchedCompleted++;if(failed)g.failed++;}",
            "  window.setTimeout=function(callback,delay){",
            "    var g=s.scope,args=Array.prototype.slice.call(arguments,2);",
            "    if(!g||typeof callback!=='function')return nativeTimeout.apply(this,arguments);",
            "    g.timers++;var id=nativeTimeout(function(){delete s.timers[id];mark(g);try{return callback.apply(this,args);}finally{g.timers--; }},delay);",
            "    s.timers[id]=g;return id;",
            "  };",
            "  window.clearTimeout=function(id){var g=s.timers[id];if(g){g.timers--;delete s.timers[id];}return nativeClear.apply(this,arguments);};",
            "  ['input','change','keydown','keyup'].forEach(function(event){document.addEventListener(event,function(e){if(e.target&&e.target.id==='txtSearch')mark(s.current);},true);});",
            "  if(window.XMLHttpRequest){",
            "    var originalOpen=XMLHttpRequest.prototype.open,originalSend=XMLHttpRequest.prototype.send,hints=new WeakMap();",
            "    XMLHttpRequest.prototype.open=function(){var g=s.current;hints.set(this,{id:g?g.id:0,matched:g?carriesQuery(String(arguments[1]),g.query):false});return originalOpen.apply(this,arguments);};",
            "    XMLHttpRequest.prototype.send=function(){",
            "      var active=s.current,hint=hints.get(this),matched=!!(active&&((hint&&hint.id===active.id&&hint.matched)||carriesQuery(arguments[0],active.query)));",
            "      var g=group(matched),x=this,finished=false;if(g){g.started++;g.pending++;}",
            "      function response(){mark(g);}function finish(){if(finished)return;finished=true;done(g,x.status===0||x.status>=400,matched);x.removeEventListener('loadend',finish);x.removeEventListener('readystatechange',response,true);}",
            "      x.addEventListener('readystatechange',response,true);x.addEventListener('loadend',finish);",
            "      try{return originalSend.apply(x,arguments);}catch(error){finish();throw error;}",
            "    };",
            "  }",
            "  if(typeof window.fetch==='function'){",
            "    var originalFetch=window.fetch;window.fetch=function(){",
            "      var active=s.current,url=typeof arguments[0]==='string'?arguments[0]:(arguments[0]&&arguments[0].url),body=arguments[1]&&arguments[1].body;",
            "      var matched=!!(active&&(carriesQuery(url,active.query)||carriesQuery(body,active.query))),g=group(matched);if(g){g.started++;g.pending++;}",
            "      try{return originalFetch.apply(this,arguments).then(function(response){mark(g);",
            "        if(g){try{response.clone().arrayBuffer().then(function(){done(g,!response.ok,matched);},function(){done(g,true,matched);});}catch(error){done(g,true,matched);}}return response;",
            "      },function(error){done(g,true,matched);throw error;});}catch(error){done(g,true,matched);throw error;}",
            "    };",
            "  }",
            "  s.installAngular=function(){",
            "    if(!window.angular)return;var input=document.getElementById('txtSearch'),scope=input&&angular.element(input).scope();",
            "    while(scope&&(!Object.prototype.hasOwnProperty.call(scope,'searchTablePA')||!scope.tabPA||typeof scope.tabPA.reload!=='function'))scope=scope.$parent;",
            "    if(!scope||s.angularTable===scope.tabPA)return;",
            "    var table=scope.tabPA,originalSearch=scope.searchTablePA,originalReload=table.reload;s.angularTable=table;s.angularScope=scope;",
            "    scope.searchTablePA=function(){var old=s.filterCall,g=s.current;s.filterCall=g&&scalar(scope.txtSearch,g.query)?g:null;mark(s.filterCall);try{return originalSearch.apply(this,arguments);}finally{s.filterCall=old;}};",
            "    table.reload=function(){",
            "      var g=s.filterCall,matched=!!(g&&g===s.current&&scalar(scope.txtSearch,g.query));if(matched){g.angularPending++;g.angularStarted++;mark(g);}",
            "      try{var promise=originalReload.apply(this,arguments);",
            "        if(matched){if(promise&&typeof promise.then==='function'){promise.then(function(){mark(g);g.angularPending--;g.angularCompleted++;},function(){mark(g);g.angularPending--;g.failed++;});}else{g.angularPending--;g.failed++;}}return promise;",
            "      }catch(error){if(matched){g.angularPending--;g.failed++;}throw error;}",
            "    };",
            "  };",
            "  s.observer=new MutationObserver(function(records){",
            "    var g=s.current;if(!g||!g.submitted||s.scope!==g)return;",
            "    for(var i=0;i<records.length;i++){var r=records[i],n=r.target.nodeType===1?r.target:r.target.parentElement,table=n&&n.closest?n.closest('table'):null;",
            "      if(table&&s.tables.indexOf(table)>=0){g.rows++;continue;}",
            "      for(var j=0;j<r.addedNodes.length;j++){var added=r.addedNodes[j];if(added.nodeType===1&&((added.matches&&added.matches(\"td[data-title-text='Request No.']\"))||(added.querySelector&&added.querySelector(\"td[data-title-text='Request No.']\")))){g.rows++;break;}}",
            "    }",
            "  });s.observer.observe(document.body,{subtree:true,childList:true,characterData:true});",
            "}",
            "s.installAngular();",
            "s.tables=[];var cells=document.querySelectorAll(\"td[data-title-text='Request No.']\");",
            "for(var i=0;i<cells.length;i++){var table=cells[i].closest('table');if(table&&s.tables.indexOf(table)<0)s.tables.push(table);}",
            "if(!s.tables.length){var tables=document.querySelectorAll('table');for(var i=0;i<tables.length;i++)s.tables.push(tables[i]);}",
            "s.observer.takeRecords();s.current={id:++s.generation,query:query,submitted:false,started:0,pending:0,completed:0,matchedCompleted:0,failed:0,rows:0,timers:0,angularStarted:0,angularPending:0,angularCompleted:0};",
            "return s.current.id;");

    private static final String SUBMIT=String.join("\n",
            "var s=window[arguments[0]],id=arguments[1],input=arguments[2];",
            "if(!s||!s.current||s.current.id!==id)return false;",
            "s.observer.takeRecords();s.current.rows=0;s.current.submitted=true;",
            "if(String(input.value).trim().toLowerCase()!==String(s.current.query).trim().toLowerCase())return false;",
            "s.mark(s.current);",
            "try{if(window.angular){var ngModel=angular.element(input).controller('ngModel');if(ngModel&&ngModel.$commitViewValue){ngModel.$commitViewValue();if(String(ngModel.$viewValue).trim().toLowerCase()!==String(s.current.query).trim().toLowerCase())return false;}}}catch(error){return false;}",
            "return true;");

    private static final String READ=String.join("\n",
            "var s=window[arguments[0]],id=arguments[1];if(!s||!s.current||s.current.id!==id)return null;",
            "var g=s.current,angularPending=0,jqueryPending=0,tableLoading=false,dataReady=true,queryMatches=true;",
            "try{if(window.angular){var root=document.querySelector('[ng-app]')||document.body;var injector=angular.element(root).injector()||angular.element(document.body).injector();if(injector)angularPending=injector.get('$http').pendingRequests.length;}}catch(ignored){}",
            "if(window.jQuery&&typeof jQuery.active==='number')jqueryPending=jQuery.active;",
            "if(s.angularTable){var scope=s.angularScope;dataReady=!!(scope&&Array.isArray(scope.tableTmp)&&scope.pa&&Array.isArray(scope.pa.tasks));",
            "  if(typeof s.angularTable.settings==='function')tableLoading=!!s.angularTable.settings().$loading;",
            "  if(dataReady&&g.submitted){var fields=['requestNo','projectCode','projectName','requesterName','typeDisplay','receivedDate'],query=String(g.query).toLowerCase();for(var i=0;i<scope.pa.tasks.length;i++){var item=scope.pa.tasks[i],matched=false;for(var j=0;j<fields.length;j++){var value=item&&item[fields[j]];if(typeof value==='string'&&value.toLowerCase().indexOf(query)>=0){matched=true;break;}}if(!matched){queryMatches=false;break;}}}",
            "}",
            "return {documentReady:document.readyState==='complete',started:g.started,pending:g.pending,completed:g.completed,matchedCompleted:g.matchedCompleted,failed:g.failed,rows:g.rows,timers:g.timers,submitted:g.submitted,angularPending:angularPending,jqueryPending:jqueryPending,tableLoading:tableLoading,dataReady:dataReady,queryMatches:queryMatches,filterPending:g.angularPending,filterCompleted:g.angularCompleted,knownFilter:!!s.angularTable};");

    private static final String NATIVE_ROWS=String.join("\n",
            "var s=window[arguments[0]],id=arguments[1];if(!s||!s.current||s.current.id!==id||!s.angularScope||!s.angularTable)return null;",
            "var g=s.current,scope=s.angularScope,table=s.angularTable;",
            "function norm(v){return String(v===null||typeof v==='undefined'?'':v).replace(/\\s+/g,' ').trim().toLowerCase();}",
            "if(g.angularPending!==0||g.angularCompleted<1||norm(scope.txtSearch)!==norm(g.query))return null;",
            "if(!scope.pa||!Array.isArray(scope.pa.tasks)||typeof table.total!=='function')return null;",
            "var tasks=scope.pa.tasks,total=table.total();if(typeof total==='number'&&total>0&&tasks.length===0)return {unverified:true};if(typeof total!=='number'||total<0||total!==Math.floor(total)||total!==tasks.length||total>100000)return null;",
            "var rows=[];for(var i=0;i<tasks.length;i++){var item=tasks[i];if(!item)return null;",
            "  var fields=['requestNo','projectName','requesterName','typeDisplay'];for(var j=0;j<fields.length;j++)if(typeof item[fields[j]]!=='string')return null;",
            "  if(!item.requestNo.trim())return null;rows.push({reference:item.requestNo.trim(),project:item.projectName,projectCode:typeof item.projectCode==='string'?item.projectCode:null,requester:item.requesterName,requestType:item.typeDisplay});",
            "}",
            "var first=null,links=document.querySelectorAll(\"td[data-title-text='Request No.'] a\");",
            "for(var i=0;i<links.length;i++){if(links[i].getClientRects().length&&getComputedStyle(links[i]).visibility!=='hidden'&&links[i].textContent.trim()){first=links[i];break;}}",
            "if(!rows.length)return first?{unverified:true}:rows;if(!first)return {unverified:true};var tr=first.closest('tr');if(!tr)return {unverified:true};",
            "function text(selector){var field=tr.querySelector(selector);return field?norm(field.textContent):null;}",
            "var ref=norm(first.textContent),project=text(\"td[data-title-text='Project Name']\"),requester=text(\"td[data-title-text='Requester']\"),type=text(\"td[data-title-text='Request Type'] div\");",
            "if(type===null){var cells=tr.querySelectorAll('td[title]');for(var i=0;i<cells.length;i++){if(cells[i].getAttribute('title')===\"'Request Type'\"){var div=cells[i].querySelector('div');type=norm(div?div.textContent:cells[i].textContent);break;}}}",
            "var projection=null,identityMatched=false;for(var i=0;i<rows.length;i++){var row=rows[i];if(norm(row.reference)===ref&&norm(row.requester)===requester&&norm(row.requestType)===type){identityMatched=true;if(norm(row.project)===project){projection='name';break;}if(row.projectCode!==null&&norm(row.projectCode+' : '+row.project)===project){projection='code-name';break;}}}",
            "if(!identityMatched)return {unverified:true};if(projection===null)return null;if(projection==='code-name'){for(var i=0;i<rows.length;i++){if(rows[i].projectCode===null)return null;rows[i].project=rows[i].projectCode+' : '+rows[i].project;}}",
            "return rows;");

    private PromsPageActivity() { }

    static long begin(WebDriver driver,String query) {
        if(!(driver instanceof JavascriptExecutor))throw new IllegalStateException("The browser cannot observe pending-list response completion.");
        Object value=((JavascriptExecutor)driver).executeScript(INSTALL,KEY,query);
        if(!(value instanceof Number))throw new IllegalStateException("Cannot observe pending-list response completion.");
        return ((Number)value).longValue();
    }

    static void submit(WebDriver driver,long generation,org.openqa.selenium.WebElement input) {
        if(!Boolean.TRUE.equals(((JavascriptExecutor)driver).executeScript(SUBMIT,KEY,generation,input))) {
            throw new IllegalStateException("The pending search model could not be submitted safely.");
        }
    }

    static boolean responseComplete(WebDriver driver,long generation) {
        Map<?,?> activity=activity(driver,generation);
        if(count(activity,"failed")>0)throw new IllegalStateException("A pending-list request failed. No empty or stale result will be treated as an approval confirmation.");
        if(!Boolean.TRUE.equals(activity.get("documentReady"))||!Boolean.TRUE.equals(activity.get("submitted"))||count(activity,"pending")>0
                ||count(activity,"timers")>0||count(activity,"angularPending")>0
                ||count(activity,"jqueryPending")>0||Boolean.TRUE.equals(activity.get("tableLoading"))
                ||!Boolean.TRUE.equals(activity.get("dataReady"))||count(activity,"filterPending")>0)return false;
        if(Boolean.TRUE.equals(activity.get("knownFilter"))) {
            // Production PROMS is a client-side filter. Its own final-query reload must finish;
            // unrelated transport or pending-table activity cannot substitute for this evidence.
            return count(activity,"filterCompleted")>0&&Boolean.TRUE.equals(activity.get("queryMatches"));
        }
        // An unchanged table is valid only after a response carrying this exact final filter value.
        return count(activity,"rows")>0||count(activity,"matchedCompleted")>0||count(activity,"filterCompleted")>0;
    }

    static boolean idle(WebDriver driver,long generation) {
        Map<?,?> activity=activity(driver,generation);
        return Boolean.TRUE.equals(activity.get("documentReady"))&&count(activity,"pending")==0&&count(activity,"timers")==0&&count(activity,"angularPending")==0
                &&count(activity,"jqueryPending")==0&&!Boolean.TRUE.equals(activity.get("tableLoading"))
                &&Boolean.TRUE.equals(activity.get("dataReady"))&&count(activity,"filterPending")==0;
    }

    static Object nativeRows(WebDriver driver,long generation) {
        return ((JavascriptExecutor)driver).executeScript(NATIVE_ROWS,KEY,generation);
    }

    private static Map<?,?> activity(WebDriver driver,long generation) {
        Object result=((JavascriptExecutor)driver).executeScript(READ,KEY,generation);
        if(!(result instanceof Map))throw new IllegalStateException("The pending page changed while searching. Try again after PROMS finishes loading.");
        return (Map<?,?>)result;
    }
    private static long count(Map<?,?> activity,String name) {
        Object value=activity.get(name);
        if(!(value instanceof Number))throw new IllegalStateException("Cannot read pending-list response completion.");
        return ((Number)value).longValue();
    }
}
