package com.java.myapp;

import java.util.Map;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;

/** Binds attachment loading to the selected activity without logging private identifiers. */
final class Tk07AttachmentLoad {
    private static final String KEY = "__trueOpsTk07AttachmentLoadV3";
    private static final String BEGIN = String.join("\n",
            "var key=arguments[0],ref=String(arguments[1]).toLowerCase(),s=window[key];",
            "if(!s){s={generation:0,current:null,owners:[]};window[key]=s;",
            "function id(value){return value==null?'':String(value).trim();}function sameRef(a,b){return id(a).toLowerCase()===id(b).toLowerCase();}",
            "function task(scope){while(scope){if(scope.activeTask&&typeof scope.activeTask==='object')return scope.activeTask;scope=scope.$parent;}return null;}",
            "function attachments(owner){if(!owner)return null;var root=owner.$root;if(root&&Array.isArray(root.attachmentsAct))return root.attachmentsAct;while(owner){if(Object.prototype.hasOwnProperty.call(owner,'attachmentsAct')&&Array.isArray(owner.attachmentsAct))return owner.attachmentsAct;owner=owner.$parent;}return null;}s.attachments=attachments;",
            "function dto(row){var names=['actId','woId','actCode','actDesc','woStep'],result={};if(!row)return null;for(var i=0;i<names.length;i++){var name=names[i];if(typeof row[name]!=='string')return null;result[name]=id(row[name]);}return result.actId&&result.woId&&result.woStep.toUpperCase()==='NT_IE_TK07'?result:null;}",
            "function sameDto(data,expected){var value=dto(data);return !!(value&&expected&&value.actId===expected.actId&&value.woId===expected.woId&&value.actCode===expected.actCode&&value.actDesc===expected.actDesc&&value.woStep===expected.woStep);}",
            "function context(owner,data,g){var current=task(owner),model=owner&&owner.woModel;return !!(g&&sameDto(data,g.expected)&&model&&id(model.woId)===id(data.woId)&&current&&sameRef(current.requestNo,g.ref)&&id(current.woId)===id(data.woId));}s.context=context;",
            "s.bind=function(){var g=s.current;if(!g||!window.angular)return;var links=document.querySelectorAll('a'),found=[];for(var i=0;i<links.length;i++)if(links[i].textContent.replace(/\\s+/g,' ').trim()==='NT_IE_TK07')found.push(links[i]);",
            "if(found.length!==1){if(found.length>1)g.failed++;return;}var link=found[0],handler=link.getAttribute('ng-click')||link.getAttribute('data-ng-click')||'';if(!/^\\s*selectActivity\\s*\\(\\s*a\\s*\\)\\s*;?\\s*$/.test(handler))return;g.knownActivity=true;",
            "var owner,linkScope;try{owner=linkScope=angular.element(link).scope();}catch(ignored){return;}while(owner&&!Object.prototype.hasOwnProperty.call(owner,'selectActivity'))owner=owner.$parent;if(!owner||typeof owner.selectActivity!=='function')return;g.owner=owner;var row=linkScope&&linkScope.a,expected=dto(row);if(!g.expected&&expected)g.expected=expected;if(sameDto(row,g.expected))g.linkRow=row;",
            "if(s.owners.indexOf(owner)>=0)return;s.owners.push(owner);var original=owner.selectActivity;owner.selectActivity=function(data){var current=s.current;if(current&&current.owner===owner){if(data===current.linkRow&&context(owner,data,current)){current.actId=id(data.actId);current.woId=id(data.woId);current.beforeAttachments=attachments(owner);current.bound=true;}else{current.bound=false;current.failed++;}}return original.apply(this,arguments);};};",
            "function relevant(url,method,g){if(!g||id(method).toUpperCase()!=='GET')return false;var value;try{value=new URL(url,location.href);}catch(ignored){return false;}if(value.origin!==location.origin)return false;",
            "if(g.knownActivity){var prefix='/proms/pmsAttachmentController/list/wo/',part=value.pathname.indexOf(prefix)===0?value.pathname.substring(prefix.length):'';try{part=decodeURIComponent(part);}catch(ignored){return false;}return !!(g.bound&&part&&part.indexOf('/')<0&&part===g.actId&&!value.search&&!value.hash&&context(g.owner,g.expected,g));}",
            "return /^(127\\.0\\.0\\.1|localhost|\\[::1\\])$/.test(location.hostname)&&value.pathname==='/attachments'&&sameRef(value.searchParams.get('ref'),g.ref);}",
            "function payload(g,value){if(!g.knownActivity)return true;try{if(typeof value==='string')value=JSON.parse(value);}catch(ignored){return false;}if(!Array.isArray(value))return false;var items=[],seen=Object.create(null);for(var i=0;i<value.length;i++){var row=value[i];if(!row||typeof row.fileName!=='string'||typeof row.attachmentId!=='string'||!id(row.fileName)||!id(row.attachmentId)||seen[id(row.attachmentId)])return false;seen[id(row.attachmentId)]=true;items.push({name:id(row.fileName),id:id(row.attachmentId)});}g.responseItems=items;return true;}",
            "function start(g){if(s.current===g)s.observer.takeRecords();g.rowChanges=0;g.emptyChanges=0;g.responseReady=false;g.started++;g.pending++;}",
            "function finish(g,failed){g.pending--;g.completed++;if(failed)g.failed++;if(s.current===g&&g.pending===0&&g.completed===g.started){s.observer.takeRecords();g.rowChanges=0;g.emptyChanges=0;g.responseReady=!g.failed;}}",
            "if(window.XMLHttpRequest){var open=XMLHttpRequest.prototype.open,send=XMLHttpRequest.prototype.send;",
            "XMLHttpRequest.prototype.open=function(){this[key]={generation:s.current,matched:relevant(arguments[1],arguments[0],s.current)};return open.apply(this,arguments);};",
            "XMLHttpRequest.prototype.send=function(body){var g=s.current,x=this,record=x[key],matched=!!(record&&record.generation===g&&record.matched),ended=false;if(matched)start(g);",
            "function ready(){if(x.readyState===4)done();}function done(){if(ended)return;ended=true;if(matched){var valid=false;try{valid=payload(g,x.responseType==='json'?x.response:x.responseText);}catch(ignored){}finish(g,x.status<200||x.status>=300||!valid);}x.removeEventListener('loadend',done);x.removeEventListener('readystatechange',ready,true);}x.addEventListener('readystatechange',ready,true);x.addEventListener('loadend',done);try{return send.apply(x,arguments);}catch(error){done();throw error;}};}",
            "if(typeof window.fetch==='function'){var fetch=window.fetch;window.fetch=function(input,options){var g=s.current,method=options&&options.method||(input&&input.method)||'GET',matched=relevant(input&&input.url?input.url:input,method,g);if(matched)start(g);",
            "var ended=false;function done(failed){if(ended)return;ended=true;if(matched)finish(g,failed);}",
            "try{return fetch.apply(this,arguments).then(function(response){if(matched){",
            "['json','text','arrayBuffer','blob','formData'].forEach(function(name){var read=response[name];if(typeof read==='function')response[name]=function(){return read.apply(response,arguments).then(function(value){done(!response.ok||!payload(g,value));return value;},function(error){done(true);throw error;});};});",
            "try{response.clone().text().then(function(value){done(!response.ok||!payload(g,value));},function(){done(true);});}catch(error){done(true);}}return response;},function(error){done(true);throw error;});}catch(error){done(true);throw error;}};}",
            "s.observer=new MutationObserver(function(records){s.bind();var g=s.current;if(!g||!g.responseReady||g.pending!==0||g.completed!==g.started)return;for(var i=0;i<records.length;i++){var r=records[i],nodes=[r.target];for(var j=0;j<r.addedNodes.length;j++)nodes.push(r.addedNodes[j]);for(var k=0;k<r.removedNodes.length;k++)nodes.push(r.removedNodes[k]);",
            "for(var n=0;n<nodes.length;n++){var node=nodes[n].nodeType===1?nodes[n]:nodes[n].parentElement;if(!node)continue;var pane=(node.closest&&node.closest('#activity_attachment'))||(node.querySelector&&node.querySelector('#activity_attachment'));if(!pane)continue;",
            "if(g.started>0&&((node.matches&&node.matches(\"tr[ng-repeat*='attachmentsAct']\"))||(node.closest&&node.closest(\"tr[ng-repeat*='attachmentsAct']\"))||(node.querySelector&&node.querySelector(\"tr[ng-repeat*='attachmentsAct']\"))))g.rowChanges++;",
            "if(g.started>0&&/no attachments?|no records|no data/i.test(pane.textContent))g.emptyChanges++;}}});s.observer.observe(document.body,{subtree:true,childList:true,characterData:true});}",
            "s.observer.takeRecords();s.current={id:++s.generation,ref:ref,started:0,pending:0,completed:0,failed:0,rowChanges:0,emptyChanges:0,responseReady:false,bound:false,knownActivity:false};s.bind();return s.current.id;");
    private static final String READ = String.join("\n",
            "var s=window[arguments[0]],g=s&&s.current;if(!g||g.id!==arguments[1])return null;",
            "if(g.knownActivity){var selected=g.owner&&g.owner.activityTemp,items=s.attachments(g.owner);if(!g.bound||!selected||String(selected.actId).trim()!==g.actId||!s.context(g.owner,selected,g)||!items||items===g.beforeAttachments)return null;",
            "var rows=document.querySelectorAll(\"#activity_attachment tr[ng-repeat*='attachmentsAct']\"),visible=[];for(var i=0;i<rows.length;i++)if(rows[i].getClientRects().length)visible.push(rows[i]);if(items.length!==visible.length||Boolean(arguments[2])!==(items.length===0)||!Array.isArray(g.responseItems)||items.length!==g.responseItems.length)return null;",
            "function text(value){return String(value).replace(/\\s+/g,' ').trim();}var seen=Object.create(null);for(var j=0;j<items.length;j++){var item=items[j],cell=visible[j].querySelector('td:nth-child(2)'),found=false;if(!item||typeof item.fileName!=='string'||typeof item.attachmentId!=='string'||!cell||text(cell.innerText)!==text(item.fileName)||seen[item.attachmentId.trim()])return null;seen[item.attachmentId.trim()]=true;for(var k=0;k<g.responseItems.length;k++)if(g.responseItems[k].id===String(item.attachmentId).trim()&&g.responseItems[k].name===String(item.fileName).trim()){found=true;break;}if(!found)return null;}}",
            "return {started:g.started,pending:g.pending,completed:g.completed,failed:g.failed,rowChanges:g.rowChanges,emptyChanges:g.emptyChanges};");
    private Tk07AttachmentLoad() { }
    static long begin(WebDriver driver, String reference) {
        Object result = ((JavascriptExecutor) driver).executeScript(BEGIN, KEY, reference);
        if (!(result instanceof Number)) throw new IllegalStateException("Could not observe current TK07 attachment loading.");
        return ((Number) result).longValue();
    }
    static boolean complete(WebDriver driver, long generation, boolean empty) {
        Object result = ((JavascriptExecutor) driver).executeScript(READ, KEY, generation, empty);
        if (!(result instanceof Map)) return false;
        Map<?, ?> values = (Map<?, ?>) result;
        if (count(values, "failed") > 0 || count(values, "pending") > 0) return false;
        // Do not trust an unchanged cached list. An empty placeholder requires
        // the selected activity's completed request AND a post-request empty-state transition.
        return count(values, "started") > 0 && count(values, "completed") == count(values, "started")
                && (empty ? count(values, "emptyChanges") > 0 : count(values, "rowChanges") > 0);
    }
    private static long count(Map<?, ?> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }
}
