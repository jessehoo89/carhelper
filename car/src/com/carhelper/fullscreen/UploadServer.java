package com.carhelper.fullscreen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 车机端「手机上传安装」用的极简 HTTP 服务（实现思路参考 LIGHTBOX MAX 的 P/Q/S 三个类）。
 *
 *  · ServerSocket + 每连接一个线程，手写 HTTP 解析（只支持 GET / 与 POST /upload）
 *  · URL 带一次性 token，防止同网段其他人乱传
 *  · 上传体走 **裸 body**（网页端用 XHR/fetch 直接 POST 文件），不做 multipart 解析
 *  · body 边收边交给回调（可能几十上百 MB，不整块进内存）
 */
public class UploadServer {

    /** 处理上传：body 为请求体输入流，len 为字节数；返回值会显示在手机浏览器页面上。 */
    public interface Handler {
        String onUpload(InputStream body, long len, String name, boolean install) throws Exception;
    }

    private final int port;
    private final String token;
    private final Handler handler;
    private final String title;

    private ServerSocket server;
    private Thread acceptThread;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;

    public UploadServer(int port, String token, String title, Handler handler) {
        this.port = port;
        this.token = token;
        this.title = title;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running;
    }

    public int port() {
        return port;
    }

    public void start() throws IOException {
        if (running) return;
        server = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            public void run() {
                                serve(s);
                            }
                        });
                    } catch (IOException e) {
                        if (running) {
                            // 端口被占用/网络抖动：记录后继续尝试
                            U.log("上传服务 accept 异常: " + e.getMessage());
                        }
                    }
                }
            }
        }, "upload-accept");
        acceptThread.start();
        U.log("上传服务已启动，端口 " + port);
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    // ------------------------------------------------------------------ 单个连接

    private void serve(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            // 请求行
            String requestLine = readLine(in, 4096);
            if (requestLine == null) return;
            String[] rl = requestLine.split(" ");
            String method = rl.length > 0 ? rl[0] : "GET";
            String path = rl.length > 1 ? rl[1] : "/";

            // 请求头
            long contentLength = -1;
            String line;
            while ((line = readLine(in, 4096)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    try {
                        contentLength = Long.parseLong(line.substring(c + 1).trim());
                    } catch (Exception ignored) {
                    }
                }
            }

            String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
            path = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
            String gotToken = param(query, "token");
            if (!token.equals(gotToken)) {
                respond(out, 403, text("<h3>token 不对</h3><p>请用车机上显示的完整网址打开。</p>"));
                return;
            }

            if ("POST".equalsIgnoreCase(method) && path.startsWith("/upload")) {
                String name = param(query, "name");
                if (name == null || name.length() == 0) name = "upload.apk";
                boolean install = !"save".equals(param(query, "act"));
                String result;
                try {
                    result = handler.onUpload(in, contentLength, name, install);
                } catch (Exception e) {
                    result = "处理失败：" + e;
                }
                respond(out, 200, text("<h3>车机返回</h3><pre>" + esc(result) + "</pre><p><a href=\"./\">再传一个</a></p>"));
                return;
            }
            respond(out, 200, text(page()));
        } catch (Throwable t) {
            U.log("上传服务处理异常: " + t);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String param(String query, String key) {
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return kv.substring(i + 1);
                }
            }
        }
        return "";
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int r;
        while ((r = in.read()) != -1) {
            if (r == '\n') break;
            if (r != '\r') b.write(r);
            if (b.size() > max) break;
        }
        if (r == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    private static void respond(OutputStream out, int code, String html) throws IOException {
        byte[] body = html.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String text(String s) {
        return "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<style>body{font-family:sans-serif;margin:16px;background:#111;color:#eee}"
                + "pre{white-space:pre-wrap;background:#1b1b1b;padding:10px;border-radius:8px}</style>" + s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 上传页面：选文件 → XHR POST 裸 body（带进度）→ 显示车机返回。 */
    private String page() {
        return "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title>"
                + "<style>body{font-family:sans-serif;margin:16px;background:#111;color:#eee}"
                + "input[type=file]{width:100%;padding:10px;background:#1b1b1b;border-radius:8px;color:#eee}"
                + "button{margin-top:12px;width:100%;padding:14px;font-size:17px;border:0;border-radius:10px;background:#238636;color:#fff}"
                + "pre{white-space:pre-wrap;background:#1b1b1b;padding:10px;border-radius:8px}"
                + "label{display:block;margin:10px 0}</style>"
                + "<h3>" + esc(title) + "</h3>"
                + "<input type=file id=f accept=\".apk,application/vnd.android.package-archive\">"
                + "<label><input type=radio name=act value=install checked> 直接安装（需在车机屏点确认）</label>"
                + "<label><input type=radio name=act value=save> 只保存到车机（不安装）</label>"
                + "<button id=b onclick=\"go()\">上传</button><pre id=r>选好 APK 后点「上传」。</pre>"
                + "<script>function go(){var f=document.getElementById('f').files[0];if(!f){alert('请先选文件');return}"
                + "var act=document.querySelector('input[name=act]:checked').value;"
                + "var x=new XMLHttpRequest();x.open('POST','/upload?token=" + token + "&name='+encodeURIComponent(f.name)+'&act='+act);"
                + "document.getElementById('r').textContent='上传中… 0%';"
                + "x.upload.onprogress=function(e){if(e.lengthComputable){document.getElementById('r').textContent='上传中… '+Math.round(e.loaded*100/e.total)+'%'}};"
                + "x.onload=function(){document.getElementById('r').textContent=x.responseText.replace(/<[^>]*>/g,'')};"
                + "x.onerror=function(){document.getElementById('r').textContent='上传失败（检查是否仍连着车机热点）'};"
                + "x.send(f)}</script>";
    }
}
