package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * 3D 模型预览提供器，基于 three.js 0.163 渲染 glb / gltf / obj / stl / dxf 格式。
 *
 * <p>SPI 类型：{@code preview-3d}。输出嵌入 OrbitControls 的 three.js 预览页面。</p>
 *
 * <p><b>渲染器必须同源自托管，不得依赖公网 CDN</b>：本类早期版本把 three 的
 * ES Module 同时写进 {@code jsUrls}（渲染器把它拼成普通 {@code <script src>}，
 * 而 ESM 被当 classic 脚本加载必然报 {@code Unexpected token 'export'}）
 * 并指向 {@code cdn.jsdelivr.net}。实测该 CDN 在内网不可达，且 {@code await import}
 * 位于 try 之外，失败时 async IIFE 直接 reject —— 页面永远停在「加载 3D 模型中...」，
 * 且没有任何错误提示。现改为从 {@code /preview-vendor/three/**} 同源动态导入
 * （资源随本模块 classpath 提供，由文件服务器的 preview-vendor 通道按约定放行），
 * 并把导入移入 try 以便失败时给出可读原因。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-3d")
public class ThreeModelPreviewProvider implements FileStoragePreviewProvider {

    /**
     * 支持的 3D 模型扩展名（小写）
     */
    private static final Set<String> SUPPORTED = Set.of("glb", "gltf", "obj", "stl", "dxf");

    /**
     * @param ext  文件扩展名
     * @param mime MIME 类型（当前忽略）
     * @return true 表示支持预览
     */
    @Override
    public boolean supports(String ext, String mime) {
        return ext != null && SUPPORTED.contains(ext.toLowerCase(Locale.ENGLISH));
    }

    @Override
    /**
     * Preview
    */
    public PreviewResult preview(byte[] content, String ext, String mime) {
        String b64 = Base64.getEncoder().encodeToString(content);
        return PreviewResult.builder()
                .htmlContent("<div id=\"app\" style=\"width:100%;height:100vh;overflow:hidden\">" +
                        "<div id=\"loading\" style=\"position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);" +
                        "color:#aaa;font-family:sans-serif;font-size:14px\">加载 3D 模型中...</div></div>")
                .embeddedCss("body{margin:0;padding:0;overflow:hidden;background:#1a1a2e}" +
                        "#info{position:fixed;bottom:12px;left:50%;transform:translateX(-50%);" +
                        "color:rgba(255,255,255,0.4);font:12px sans-serif;pointer-events:none;" +
                        "text-align:center;background:rgba(0,0,0,0.3);padding:4px 12px;border-radius:4px}" +
                        "#error{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);" +
                        "color:#e74c3c;font:14px sans-serif;display:none}")
                .embeddedJs(buildScript(b64, ext))
                .build();
    }

    /**
     * 构建script
     *
     * @param b64 b64
     * @param ext ext
     * @return 构建script的结果
     */
    private String buildScript(String b64, String ext) {
        return "(async function(){var b='" + b64 + "';var e='" + ext.toLowerCase() + "';" +
 // 基础64 → array缓冲
                "var p=function(b){for(var a=atob(b),i=a.length,ab=new ArrayBuffer(i),v=new Uint8Array(ab);i--;)v[i]=a.charCodeAt(i);return ab};" +
 // 基础64 → 字符串
                "var ts=function(b){return new TextDecoder().decode(p(b))};" +
 // 导入 Three.js
                // 错误 处理器（必须先定义：渲染器自身加载失败时也要有可读反馈，
                // 否则 async IIFE 直接 reject，页面永远停在「加载 3D 模型中...」）
                "var sh=function(msg){var el=document.getElementById('error');el.textContent=msg;el.style.display='block';" +
                "document.getElementById('loading').style.display='none'};" +
                // 渲染器同源自托管：早期版本把 three 的 ES Module 同时写进 jsUrls
                // （被渲染成普通 <script>，必然报 Unexpected token 'export'）
                // 且指向公网 CDN（内网不可达），结果页面永远卡在加载中。
                // 现改为同源 /preview-vendor/three/** 动态 import。
                "var m,GL,OL,SL,OC;" +
                "try{" +
                "m=await import('/preview-vendor/three/three.module.min.js');" +
                "GL=await import('/preview-vendor/three/GLTFLoader.js');" +
                "OL=await import('/preview-vendor/three/OBJLoader.js');" +
                "SL=await import('/preview-vendor/three/STLLoader.js');" +
                "OC=await import('/preview-vendor/three/OrbitControls.js');" +
                "}catch(ex){return sh('3D 渲染器加载失败: '+ex.message)}" +
                // scene setup
                "var s=new m.Scene();s.background=new m.Color(0x1a1a2e);" +
                "var ca=new m.PerspectiveCamera(45,innerWidth/innerHeight,0.01,1e5);" +
                "var r=new m.WebGLRenderer({antialias:true});r.setSize(innerWidth,innerHeight);r.setPixelRatio(devicePixelRatio);" +
                "var app=document.getElementById('app');app.innerHTML='';app.appendChild(r.domElement);" +
                // lights
                "s.add(new m.AmbientLight(0xffffff,0.6));" +
                "var dl=new m.DirectionalLight(0xffffff,1.2);dl.position.set(10,10,10);s.add(dl);" +
                "var dl2=new m.DirectionalLight(0x4488ff,0.4);dl2.position.set(-10,-5,-10);s.add(dl2);" +
                // controls
                "var co=new OC.OrbitControls(ca,r.domElement);co.enableDamping=true;co.dampingFactor=0.1;" +
 // 助手
                "s.add(new m.GridHelper(10,10,0x444466,0x333355));" +
 // 加载 模型
                "try{var obj;" +
                // GLB
                "if(e==='glb'){obj=await new Promise(function(ok,fail){" +
                "new GL.GLTFLoader().parse(p(b),'',ok,undefined,fail)});" +
                "obj=obj.scene||obj;" +
                // center & scale
                "var bx=new m.Box3().setFromObject(obj);var ct=bx.getCenter(new m.Vector3());" +
                "var sz=bx.getSize(new m.Vector3());var md=Math.max(sz.x,sz.y,sz.z,0.01);" +
                "obj.position.sub(ct);ca.position.set(0,md*0.8,md*2);co.target.set(0,0,0);" +
                // GLTF
                "}else if(e==='gltf'){obj=await new Promise(function(ok,fail){" +
                "new GL.GLTFLoader().parse(ts(b),'',ok,undefined,fail)});" +
                "obj=obj.scene||obj;var bx=new m.Box3().setFromObject(obj);var ct=bx.getCenter(new m.Vector3());" +
                "var sz=bx.getSize(new m.Vector3());var md=Math.max(sz.x,sz.y,sz.z,0.01);" +
                "obj.position.sub(ct);ca.position.set(0,md*0.8,md*2);co.target.set(0,0,0);" +
                // OBJ
                "}else if(e==='obj'){obj=new OL.OBJLoader().parse(ts(b));" +
                "var bx=new m.Box3().setFromObject(obj);var ct=bx.getCenter(new m.Vector3());" +
                "var sz=bx.getSize(new m.Vector3());var md=Math.max(sz.x,sz.y,sz.z,0.01);" +
                "obj.position.sub(ct);ca.position.set(0,md*0.8,md*2);co.target.set(0,0,0);" +
                // STL
                "}else if(e==='stl'){var g=new SL.STLLoader().parse(p(b));" +
                "var me=new m.MeshStandardMaterial({color:0x88aadd,roughness:0.4,metalness:0.2,flatShading:true});" +
                "obj=new m.Mesh(g,me);var bx=new m.Box3().setFromObject(obj);var ct=bx.getCenter(new m.Vector3());" +
                "var sz=bx.getSize(new m.Vector3());var md=Math.max(sz.x,sz.y,sz.z,0.01);" +
                "g.translate(-ct.x,-ct.y,-ct.z);ca.position.set(0,md*0.8,md*2);co.target.set(0,0,0);" +
                // DXF
                "}else if(e==='dxf'){obj=parseDXF(ts(b),m);" +
                "var bx=new m.Box3().setFromObject(obj);var ct=bx.getCenter(new m.Vector3());" +
                "var sz=bx.getSize(new m.Vector3());var md=Math.max(sz.x,sz.y,sz.z,0.01);" +
                "obj.position.sub(ct);ca.position.set(0,md*0.8,md*2);co.target.set(0,0,0);" +
 // 添加 转为 scene
                "}s.add(obj);document.getElementById('loading').style.display='none';" +
 // 信息 bar
                "var info=document.createElement('div');info.id='info';" +
                "info.textContent=e.toUpperCase()+' | Three.js | 鼠标拖拽旋转 / 滚轮缩放';" +
                "document.body.appendChild(info);" +
                // animate
                "!function a(){requestAnimationFrame(a);co.update();r.render(s,ca)}();" +
                "window.addEventListener('resize',function(){ca.aspect=innerWidth/innerHeight;" +
                "ca.updateProjectionMatrix();r.setSize(innerWidth,innerHeight)});" +
                "}catch(ex){sh('加载失败: '+ex.message)}})();" +
                // DXF parser
                "function parseDXF(t,TH){var lines=t.split('\\n'),grps=[];" +
                "for(var i=0;i<lines.length;i++){var c=parseInt(lines[i].trim());" +
                "if(isNaN(c))continue;i++;var v=(lines[i]||'').trim();grps.push({c:c,v:v})}" +
                "var pts=[],curves=[];var inEnt=false;" +
                "for(i=0;i<grps.length;i++){" +
                "if(grps[i].c===0&&grps[i].v==='SECTION'&&i+1<grps.length&&grps[i+1].c===2&&grps[i+1].v==='ENTITIES'){inEnt=true;continue}" +
                "if(inEnt&&grps[i].c===0&&grps[i].v==='ENDSEC')break;" +
                "if(!inEnt||grps[i].c!==0)continue;" +
                "var et=grps[i].v,eg=[];i++;while(i<grps.length&&grps[i].c!==0){eg.push(grps[i]);i++}i--;" +
                "if(et==='LINE'){" +
                "var x1=0,y1=0,z1=0,x2=0,y2=0,z2=0;" +
                "eg.forEach(function(g){if(g.c===10)x1=parseFloat(g.v);if(g.c===20)y1=parseFloat(g.v);" +
                "if(g.c===30)z1=parseFloat(g.v);if(g.c===11)x2=parseFloat(g.v);" +
                "if(g.c===21)y2=parseFloat(g.v);if(g.c===31)z2=parseFloat(g.v)});" +
                "pts.push(x1,y1,z1,x2,y2,z2)" +
                "}else if(et==='LWPOLYLINE'){" +
                "var pxs=[],pys=[],pzs=[],cl=false;" +
                "eg.forEach(function(g){if(g.c===10)pxs.push(parseFloat(g.v));" +
                "if(g.c===20)pys.push(parseFloat(g.v));if(g.c===30)pzs.push(parseFloat(g.v)||0);" +
                "if(g.c===70)cl=!!(parseInt(g.v)&1)});" +
                "for(var k=0;k<pxs.length-1;k++){pts.push(pxs[k],pys[k],pzs[k],pxs[k+1],pys[k+1],pzs[k+1])}" +
                "if(cl&&pxs.length>0)pts.push(pxs[pxs.length-1],pys[pys.length-1],pzs[pzs.length-1],pxs[0],pys[0],pzs[0])" +
                "}else if(et==='CIRCLE'){" +
                "var cx=0,cy=0,cz=0,cr=1;" +
                "eg.forEach(function(g){if(g.c===10)cx=parseFloat(g.v);if(g.c===20)cy=parseFloat(g.v);" +
                "if(g.c===30)cz=parseFloat(g.v);if(g.c===40)cr=parseFloat(g.v)});" +
                "for(var a=0;a<=64;a++){var ang=a/64*Math.PI*2;" +
                "pts.push(cx+Math.cos(ang)*cr,cy+Math.sin(ang)*cr,cz)" +
                "if(a>0)pts.push(cx+Math.cos((a-1)/64*Math.PI*2)*cr,cy+Math.sin((a-1)/64*Math.PI*2)*cr,cz)}" +
                "}else if(et==='ARC'){" +
                "var acx=0,acy=0,acz=0,acr=1,ast=0,aend=Math.PI*2;" +
                "eg.forEach(function(g){if(g.c===10)acx=parseFloat(g.v);if(g.c===20)acy=parseFloat(g.v);" +
                "if(g.c===30)acz=parseFloat(g.v);if(g.c===40)acr=parseFloat(g.v);" +
                "if(g.c===50)ast=parseFloat(g.v)*Math.PI/180;" +
                "if(g.c===51)aend=parseFloat(g.v)*Math.PI/180});" +
                "for(var aa=0;aa<=64;aa++){var aang=ast+(aend-ast)*aa/64;" +
                "pts.push(acx+Math.cos(aang)*acr,acy+Math.sin(aang)*acr,acz)" +
                "if(aa>0)pts.push(acx+Math.cos(ast+(aend-ast)*(aa-1)/64)*acr,acy+Math.sin(ast+(aend-ast)*(aa-1)/64)*acr,acz)}}}" +
 // 创建 线 segments
                "var geom=new TH.BufferGeometry();geom.setAttribute('position',new TH.Float32BufferAttribute(pts,3));" +
                "var mat=new TH.LineBasicMaterial({color:0x4488ff,linewidth:1});" +
                "var group=new TH.Group();group.add(new TH.LineSegments(geom,mat));" +
 // 添加 point 记号笔 for small 模型
                "return group}";
    }
}
