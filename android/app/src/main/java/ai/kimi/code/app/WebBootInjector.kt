package ai.kimi.code.app

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 把 App 侧的两处增强塞进网页的同源 `boot.js`，不改 rootfs 里的任何文件。
 *
 * 1. `MODEL_GROUPS_JS`：模型选择弹层（切换模型 / 更多模型）在「全部」标签下
 *    把所有模型平铺成一长条，分不清哪个模型属于哪个供应商。网页的 ModelPicker
 *    渲染时只给了 `.model-row` + `.model-meta`（`讯飞 · 128k ctx`），没有分组
 *    标题。这里在渲染后按 `.model-meta` 的第一段给每组第一行打 `data-eta-group`
 *    标记，用 ::before 画出组名 —— 只加属性，不动 Vue 管理的节点树，重渲染
 *    不会打架。开关标签是单个供应商时自动不显示组名。
 *
 * 2. `AUTONOMY_JS`：全自主执行（Never Ask）。网页的权限模式是启动时从
 *    localStorage（`kimi-web.permission`）读的，默认 `manual`，每次读写文件 /
 *    跑命令都要点一次批准。手机上这个批准条又小又容易滑过去，看起来就像 AI
 *    「不会自己动手」。注入后页面初始化前就生效。
 *
 * 页面 CSP 是 `script-src 'self'`，内联脚本被禁，但 boot.js 是同源文件，允许。
 */
object WebBootInjector {

    private const val SCRIPT_NAME = "boot.js"
    private var cachedPort = -1
    private var cachedAutonomy = false
    private var cachedScript: String? = null

    /**
     * 返回注入了界面增强（以及可选的全自主执行）的 boot.js。
     * 拉取失败时返回 null，让 WebView 走原始请求。
     */
    fun pageScript(port: Int, token: String?, autonomy: Boolean): String? {
        if (port <= 0) return null
        if (cachedPort == port && cachedAutonomy == autonomy) return cachedScript
        val source = fetch(port, token) ?: return null
        val script = buildString {
            append(source)
            append("\n")
            append(MODEL_GROUPS_JS)
            append("\n")
            if (autonomy) {
                append(AUTONOMY_JS)
                append("\n")
            }
        }
        cachedPort = port
        cachedAutonomy = autonomy
        cachedScript = script
        return script
    }

    fun invalidate() {
        cachedPort = -1
        cachedAutonomy = false
        cachedScript = null
    }

    private fun fetch(port: Int, token: String?): String? {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL("http://127.0.0.1:$port/$SCRIPT_NAME").openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            if (connection!!.responseCode !in 200..299) return null
            connection!!.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    fun stream(script: String): ByteArrayInputStream = ByteArrayInputStream(script.toByteArray())

    /**
     * 模型列表按供应商分组。
     *
     * 依赖的网页结构（ModelPicker）：`.mp .model-list > .model-row > .model-main
     * > .model-name + .model-meta`。任何一环缺失都安静退出，不影响原页面。
     */
    private const val MODEL_GROUPS_JS = """;(function(){
  if (window.__etaModelGroups) return;
  window.__etaModelGroups = 1;
  var STYLE_ID = 'eta-model-groups-style';
  var CSS = [
    '.mp .model-row{position:relative}',
    '.mp .model-row[data-eta-group]{margin-top:26px}',
    '.mp .model-row[data-eta-group]::before{content:attr(data-eta-group);position:absolute;',
    'left:12px;top:-21px;right:12px;font-family:var(--font-ui);font-size:12px;line-height:16px;',
    'font-weight:600;letter-spacing:.01em;color:var(--color-text-muted);white-space:nowrap;',
    'overflow:hidden;text-overflow:ellipsis}'
  ].join('');
  function installStyle(){
    if (document.getElementById(STYLE_ID)) return;
    var el = document.createElement('style');
    el.id = STYLE_ID;
    el.textContent = CSS;
    (document.head || document.documentElement).appendChild(el);
  }
  function providerOf(row){
    var meta = row.querySelector('.model-meta');
    if (!meta) {
      var main = row.querySelector('.model-main');
      meta = main && main.children ? main.children[1] : null;
    }
    if (!meta) return '';
    var text = (meta.textContent || '').trim();
    if (!text) return '';
    var cut = text.indexOf('\u00b7');
    return (cut > 0 ? text.slice(0, cut) : text).trim();
  }
  function decorate(){
    var lists = document.querySelectorAll('.mp .model-list');
    for (var li = 0; li < lists.length; li++){
      var list = lists[li];
      var rows = [];
      var i;
      for (i = 0; i < list.children.length; i++){
        var el = list.children[i];
        if (el.classList && el.classList.contains('model-row')) rows.push(el);
      }
      if (!rows.length) continue;
      var names = [];
      var seen = {};
      var groups = 0;
      for (i = 0; i < rows.length; i++){
        var n = providerOf(rows[i]);
        names.push(n);
        if (n && !seen[n]) { seen[n] = 1; groups++; }
      }
      if (groups < 2) {
        for (i = 0; i < rows.length; i++){
          if (rows[i].hasAttribute('data-eta-group')) rows[i].removeAttribute('data-eta-group');
        }
        continue;
      }
      var last = '';
      for (i = 0; i < rows.length; i++){
        var row = rows[i];
        var name = names[i];
        var head = name && name !== last ? name : '';
        if (head) last = name;
        if (head) {
          if (row.getAttribute('data-eta-group') !== head) row.setAttribute('data-eta-group', head);
        } else {
          if (row.hasAttribute('data-eta-group')) row.removeAttribute('data-eta-group');
        }
      }
    }
  }
  var timer = 0;
  function schedule(){
    if (timer) return;
    timer = setTimeout(function(){
      timer = 0;
      try { decorate(); } catch (e) {}
    }, 120);
  }
  function start(){
    installStyle();
    try { decorate(); } catch (e) {}
    var mo = new MutationObserver(function(records){
      for (var i = 0; i < records.length; i++){
        if (records[i].type === 'childList') { schedule(); return; }
      }
    });
    mo.observe(document.body, {childList: true, subtree: true});
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();"""

    /**
     * `auto` = Never Ask：工具调用、命令执行、文件改写全部自动决定，只有
     * Plan 模式的方案还需要人看。与设置页「全自主执行」开关一一对应。
     */
    private const val AUTONOMY_JS =
        ";(function(){try{localStorage.setItem('kimi-web.permission','auto');" +
            "localStorage.setItem('kimi-web.permission-sticky-at',String(Date.now()));}catch(e){}})();"
}
