package org.c0fle4.FontRedirect.hook;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.c0fle4.FontRedirect.log.FileLogger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public class WebViewInjector {

    private static final String TAG = "FontRedirect";
    private static final String FONT_DOMAIN = "fontredirect.local";
    private static final String STYLE_ID = "__font_redirect_style__";
    private static final String FONT_PATH_LATIN = "/latin";
    private static final String FONT_PATH_CJK = "/cjk";

    public static void hook(String pkg, ClassLoader cl) {
        hookSetWebViewClient(pkg, cl);
        hookLoadUrl(pkg, cl);
    }

    private static void hookSetWebViewClient(String pkg, ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebView", cl, "setWebViewClient",
                    XposedHelpers.findClass("android.webkit.WebViewClient", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object original = param.args[0];
                            if (original instanceof InjectorWebViewClient) {
                                return;
                            }
                            param.args[0] = new InjectorWebViewClient((WebViewClient) original);
                            FileLogger.i(TAG, "WebView.setWebViewClient wrapped for " + pkg);
                        }
                    });
            FileLogger.i(TAG, "WebView.setWebViewClient hooked for " + pkg);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Failed to hook WebView.setWebViewClient for " + pkg, t);
        }
    }

    private static void hookLoadUrl(String pkg, ClassLoader cl) {
        String[] methods = {"loadUrl", "loadUrl", "loadData", "loadDataWithBaseURL"};
        Class<?>[][] sigs = {
                {String.class},
                {String.class, Map.class},
                {String.class, String.class, String.class},
                {String.class, String.class, String.class, String.class, String.class}
        };
        for (int i = 0; i < methods.length; i++) {
            final String methodName = methods[i];
            try {
                Object[] paramTypes = new Object[sigs[i].length + 1];
                System.arraycopy(sigs[i], 0, paramTypes, 0, sigs[i].length);
                paramTypes[sigs[i].length] = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ensureInjectingClient((WebView) param.thisObject, pkg);
                        if ("loadDataWithBaseURL".equals(methodName) && param.args != null && param.args.length > 1 && param.args[1] instanceof String) {
                            String original = (String) param.args[1];
                            String injected = injectCssIntoHtml(original);
                            if (!injected.equals(original)) {
                                param.args[1] = injected;
                                FileLogger.i(TAG, "WebView.loadDataWithBaseURL CSS pre-injected for " + pkg);
                            }
                        }
                    }
                };
                XposedHelpers.findAndHookMethod("android.webkit.WebView", cl, methodName, paramTypes);
                FileLogger.i(TAG, "WebView." + methodName + " hooked for " + pkg);
            } catch (Throwable t) {
                FileLogger.e(TAG, "Failed to hook WebView." + methodName + " for " + pkg, t);
            }
        }
    }

    private static void ensureInjectingClient(WebView webView, String pkg) {
        try {
            WebViewClient current = getCurrentWebViewClient(webView);
            if (current == null) {
                webView.setWebViewClient(new InjectorWebViewClient(null));
                FileLogger.i(TAG, "Default InjectorWebViewClient set for " + pkg);
            }
        } catch (Throwable t) {
            FileLogger.e(TAG, "ensureInjectingClient failed for " + pkg, t);
        }
    }

    private static WebViewClient getCurrentWebViewClient(WebView webView) {
        try {
            java.lang.reflect.Field f = WebView.class.getDeclaredField("mWebViewClient");
            f.setAccessible(true);
            return (WebViewClient) f.get(webView);
        } catch (NoSuchFieldException e) {
            // Some ROMs use different field names; fall back to forcing a default client.
            return null;
        } catch (Throwable t) {
            FileLogger.e(TAG, "getCurrentWebViewClient failed", t);
            return null;
        }
    }

    private static class InjectorWebViewClient extends WebViewClient {
        private final WebViewClient delegate;

        InjectorWebViewClient(WebViewClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            injectCss(view);
            if (delegate != null) {
                delegate.onPageStarted(view, url, favicon);
            } else {
                super.onPageStarted(view, url, favicon);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (delegate != null) {
                delegate.onPageFinished(view, url);
            } else {
                super.onPageFinished(view, url);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return delegate != null ? delegate.shouldOverrideUrlLoading(view, url)
                    : super.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return delegate != null ? delegate.shouldOverrideUrlLoading(view, request)
                    : super.shouldOverrideUrlLoading(view, request);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null) {
                FileLogger.i(TAG, "shouldInterceptRequest request=" + request.getUrl() + " method=" + request.getMethod());
            }
            WebResourceResponse response = interceptFontRequest(request);
            if (response != null) return response;
            return delegate != null ? delegate.shouldInterceptRequest(view, request)
                    : super.shouldInterceptRequest(view, request);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            FileLogger.i(TAG, "shouldInterceptRequest url=" + url);
            WebResourceResponse response = interceptFontRequest(url);
            if (response != null) return response;
            return delegate != null ? delegate.shouldInterceptRequest(view, url)
                    : super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            if (delegate != null) {
                delegate.onReceivedError(view, errorCode, description, failingUrl);
            } else {
                super.onReceivedError(view, errorCode, description, failingUrl);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (delegate != null) {
                delegate.onReceivedError(view, request, error);
            } else {
                super.onReceivedError(view, request, error);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
            if (delegate != null) {
                delegate.onReceivedSslError(view, handler, error);
            } else {
                super.onReceivedSslError(view, handler, error);
            }
        }
    }

    private static WebResourceResponse interceptFontRequest(WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return null;
        return interceptFontRequest(request.getUrl().toString());
    }

    private static WebResourceResponse interceptFontRequest(String urlStr) {
        if (urlStr == null) return null;
        try {
            Uri uri = Uri.parse(urlStr);
            if (!FONT_DOMAIN.equals(uri.getHost())) return null;
            String path = uri.getPath();
            FileLogger.i(TAG, "interceptFontRequest matched path=" + path);
            File fontFile;
            if (FONT_PATH_LATIN.equals(path)) {
                fontFile = FontLoader.getLatinFontFile();
            } else if (FONT_PATH_CJK.equals(path)) {
                fontFile = FontLoader.getCjkFontFile();
            } else if ("/diag.png".equals(path)) {
                FileLogger.i(TAG, "interceptFontRequest diagnostic image request");
                return serveDiagPng();
            } else {
                FileLogger.w(TAG, "interceptFontRequest unknown path=" + path);
                return null;
            }
            if (fontFile == null || !fontFile.exists()) {
                FileLogger.w(TAG, "Font file not found: " + path + " file=" + fontFile);
                return null;
            }
            InputStream is = new FileInputStream(fontFile);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, OPTIONS");
            headers.put("Cache-Control", "public, max-age=86400");
            String mimeType = getFontMimeType(fontFile);
            FileLogger.i(TAG, "Serving font " + path + " from " + fontFile.getAbsolutePath() + " (" + fontFile.length() + " bytes, mime=" + mimeType + ")");
            return new WebResourceResponse(mimeType, null, 200, "OK", headers, is);
        } catch (Throwable t) {
            FileLogger.e(TAG, "interceptFontRequest failed for " + urlStr, t);
            return null;
        }
    }

    private static WebResourceResponse serveDiagPng() {
        try {
            String base64Png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
            byte[] png = android.util.Base64.decode(base64Png, android.util.Base64.DEFAULT);
            InputStream is = new ByteArrayInputStream(png);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Content-Type", "image/png");
            FileLogger.i(TAG, "Serving diagnostic 1x1 PNG (" + png.length + " bytes)");
            return new WebResourceResponse("image/png", null, 200, "OK", headers, is);
        } catch (Throwable t) {
            FileLogger.e(TAG, "serveDiagPng failed", t);
            return null;
        }
    }

    private static void injectCss(WebView webView) {
        try {
            String js = buildJsInjector();
            webView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    FileLogger.i(TAG, "WebView CSS injection JS result: " + value);
                }
            });
            FileLogger.i(TAG, "WebView CSS injected");
            runDiagnostic(webView, "immediate");
            webView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    runDiagnostic(webView, "delayed5s");
                }
            }, 5000);
        } catch (Throwable t) {
            FileLogger.e(TAG, "injectCss failed", t);
        }
    }

    private static void runDiagnostic(final WebView webView, final String label) {
        try {
            String js = "(function(){" +
                    "var body=document.body;" +
                    "var eng=document.querySelector('.eng');" +
                    "var cjk=document.querySelector('.cjk');" +
                    "return JSON.stringify({" +
                    "  label:'" + label + "'," +
                    "  bodyFamily: body ? window.getComputedStyle(body).fontFamily : 'no-body'," +
                    "  engFamily: eng ? window.getComputedStyle(eng).fontFamily : 'no-eng'," +
                    "  cjkFamily: cjk ? window.getComputedStyle(cjk).fontFamily : 'no-cjk'," +
                    "  checkEn: document.fonts ? document.fonts.check('12px FontRedirect', 'abc') : 'no-document-fonts'," +
                    "  checkCjk: document.fonts ? document.fonts.check('12px FontRedirect', '中文') : 'no-document-fonts'," +
                    "  styleTag: !!document.getElementById('" + STYLE_ID + "')," +
                    "  readyState: document.readyState," +
                    "  url: location.href" +
                    "});" +
                    "})();";
            webView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    FileLogger.i(TAG, "WebView diagnostic [" + label + "]: " + value);
                }
            });
        } catch (Throwable t) {
            FileLogger.e(TAG, "runDiagnostic [" + label + "] failed", t);
        }
    }

    private static String buildCss() {
        String latinFormat = getFontFormat(FontLoader.getLatinFontFile());
        String cjkFormat = getFontFormat(FontLoader.getCjkFontFile());
        return "@font-face {" +
                "  font-family: 'FontRedirect';" +
                "  src: url('https://" + FONT_DOMAIN + FONT_PATH_LATIN + "') format('" + latinFormat + "');" +
                "  unicode-range: U+0000-024F, U+02B0-02FF, U+1E00-1EFF, U+2000-206F, U+2070-209F, U+20A0-20CF, U+2100-214F, U+2200-22FF, U+FB00-FB4F;" +
                "  font-display: swap;" +
                "}" +
                "@font-face {" +
                "  font-family: 'FontRedirect';" +
                "  src: url('https://" + FONT_DOMAIN + FONT_PATH_CJK + "') format('" + cjkFormat + "');" +
                "  unicode-range: U+2E80-9FFF, U+3040-309F, U+30A0-30FF, U+31F0-31FF, U+AC00-D7AF, U+F900-FAFF, U+FF00-FFEF;" +
                "  font-display: swap;" +
                "}" +
                "body, body * { font-family: 'FontRedirect', sans-serif !important; }";
    }

    private static String injectCssIntoHtml(String html) {
        if (html == null) return null;
        String css = buildCss();
        String styleTag = "<style id=\"" + STYLE_ID + "\">" + css + "</style>";
        String lower = html.toLowerCase();
        int headClose = lower.indexOf("</head>");
        if (headClose >= 0) {
            return html.substring(0, headClose) + styleTag + html.substring(headClose);
        }
        int headOpen = lower.indexOf("<head>");
        if (headOpen >= 0) {
            int afterHead = headOpen + "<head>".length();
            return html.substring(0, afterHead) + styleTag + html.substring(afterHead);
        }
        return styleTag + html;
    }

    private static String buildJsInjector() {
        String css = buildCss();
        return "(function(){" +
                "var css=" + escapeJsString(css) + ";" +
                "function inject(){" +
                "  var existing=document.getElementById('" + STYLE_ID + "');" +
                "  if(existing) existing.remove();" +
                "  var style=document.createElement('style');" +
                "  style.id='" + STYLE_ID + "';" +
                "  style.textContent=css;" +
                "  var head=document.head||document.documentElement;" +
                "  if(head) head.appendChild(style);" +
                "}" +
                "if(document.readyState==='loading'){" +
                "  document.addEventListener('DOMContentLoaded', inject);" +
                "}else{" +
                "  inject();" +
                "}" +
                "var obs=new MutationObserver(function(mutations){" +
                "  if(!document.getElementById('" + STYLE_ID + "')) inject();" +
                "});" +
                "obs.observe(document.documentElement,{childList:true,subtree:true});" +
                "return JSON.stringify({" +
                "  bodyFamily: window.getComputedStyle(document.body).fontFamily," +
                "  engFamily: document.querySelector('.eng') ? window.getComputedStyle(document.querySelector('.eng')).fontFamily : 'no-eng'," +
                "  cjkFamily: document.querySelector('.cjk') ? window.getComputedStyle(document.querySelector('.cjk')).fontFamily : 'no-cjk'," +
                "  checkEn: document.fonts ? document.fonts.check('12px FontRedirect', 'abc') : 'no-document-fonts'," +
                "  checkCjk: document.fonts ? document.fonts.check('12px FontRedirect', '中文') : 'no-document-fonts'," +
                "  styleTag: !!document.getElementById('" + STYLE_ID + "')," +
                "  readyState: document.readyState" +
                "});" +
                "})();";
    }

    private static String escapeJsString(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') sb.append("\\'");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20) sb.append(String.format("\\x%02x", (int) c));
            else sb.append(c);
        }
        sb.append("'");
        return sb.toString();
    }

    private static String getFontMimeType(File fontFile) {
        if (fontFile == null) return "font/ttf";
        String name = fontFile.getName().toLowerCase();
        if (name.endsWith(".otf")) return "font/otf";
        if (name.endsWith(".ttc") || name.endsWith(".otc")) return "font/collection";
        return "font/ttf";
    }

    private static String getFontFormat(File fontFile) {
        if (fontFile == null) return "truetype";
        String name = fontFile.getName().toLowerCase();
        if (name.endsWith(".otf") || name.endsWith(".otc")) return "opentype";
        if (name.endsWith(".ttc")) return "truetype";
        return "truetype";
    }
}
