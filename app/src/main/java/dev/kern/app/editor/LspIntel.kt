package dev.kern.app.editor

import android.content.Context

/**
 * Any Language Server Protocol server, behind the same one-line JSON requests the other
 * providers use. A small Python bridge in the guest starts the server, does the LSP
 * handshake, keeps each file open and in sync, and turns a completion or definition
 * request into the server's `textDocument/completion` or `textDocument/definition` -
 * so adding a language is one entry in [servers], not a new client.
 */
object LspIntel {

    private val BRIDGE = """
import sys, json, base64, subprocess, threading, os
cmd = sys.argv[1:]
srv = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
nid = [0]
pending = {}
lock = threading.Lock()
def send(msg):
    body = json.dumps(msg).encode()
    srv.stdin.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    srv.stdin.flush()
def request(method, params, timeout=8):
    with lock:
        nid[0] += 1
        i = nid[0]
    ev = threading.Event()
    pending[i] = [ev, None]
    send({"jsonrpc": "2.0", "id": i, "method": method, "params": params})
    ev.wait(timeout)
    return pending.pop(i, [None, None])[1]
def reader():
    while True:
        length = 0
        while True:
            line = srv.stdout.readline()
            if not line:
                return
            line = line.strip()
            if not line:
                break
            if line.lower().startswith(b"content-length:"):
                length = int(line.split(b":")[1])
        msg = json.loads(srv.stdout.read(length))
        if "id" in msg and msg["id"] in pending and "method" not in msg:
            pending[msg["id"]][1] = msg.get("result")
            pending[msg["id"]][0].set()
threading.Thread(target=reader, daemon=True).start()
request("initialize", {"processId": os.getpid(), "rootUri": None, "capabilities": {
    "textDocument": {"completion": {"completionItem": {"snippetSupport": False}}}}}, timeout=30)
send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
versions = {}
KIND = {2: "method", 3: "function", 4: "function", 5: "property", 6: "var", 7: "class",
        8: "interface", 9: "module", 10: "property", 13: "enum", 14: "keyword", 22: "class"}
for line in sys.stdin:
    r = {}
    out = []
    try:
        r = json.loads(line)
        uri = "file://" + r["p"]
        text = base64.b64decode(r["s"]).decode("utf-8", "replace")
        if uri not in versions:
            versions[uri] = 1
            send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {"textDocument": {
                "uri": uri, "languageId": r.get("lang", "plaintext"), "version": 1, "text": text}}})
            # A server answers its very first request on a file before it has parsed it,
            # with every word in the file (clangd did, on device). A moment's grace first.
            import time
            time.sleep(2.5)
        else:
            versions[uri] += 1
            send({"jsonrpc": "2.0", "method": "textDocument/didChange", "params": {
                "textDocument": {"uri": uri, "version": versions[uri]}, "contentChanges": [{"text": text}]}})
        pos = {"textDocument": {"uri": uri}, "position": {"line": r["l"] - 1, "character": r["c"]}}
        src_line = text.split("\n")[r["l"] - 1] if r["l"] - 1 < len(text.split("\n")) else ""
        prefix = len(src_line[:r["c"]]) - len(src_line[:r["c"]].rstrip("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"))
        if r["k"] == "c":
            res = request("textDocument/completion", pos) or []
            items = res.get("items", []) if isinstance(res, dict) else res
            for it in items[:80]:
                name = (it.get("filterText") or it.get("label") or "").strip()
                out.append([name, KIND.get(it.get("kind"), "text"), prefix])
        else:
            res = request("textDocument/definition", pos) or []
            if isinstance(res, dict):
                res = [res]
            for d in res:
                u = d.get("uri") or d.get("targetUri")
                rg = d.get("range") or d.get("targetSelectionRange")
                if u and rg:
                    out.append([u.replace("file://", ""), rg["start"]["line"] + 1, rg["start"]["character"]])
    except Exception:
        out = []
    print("${WarmProcess.MARK}" + json.dumps({"i": r.get("i"), "r": out}), flush=True)
""".trimIndent()

    /** One bridge per language server, by the file extensions it serves. */
    private class Server(val extensions: Set<String>, val command: String, name: String) {
        val process = WarmProcess(name, "lsp_bridge.py", BRIDGE, "python3 -u {} $command")
    }

    private val servers = listOf(
        // clangd answers C and C++; --background-index off, so a phone does not index
        // every header on the system the first time a file opens.
        Server(setOf("c", "h", "cc", "cpp", "cxx", "hpp", "hh"), "clangd --background-index=false", "Clangd"),
        // Each answers only once its server is installed in the guest; until then the
        // bridge gets no reply and the editor falls back to words from the file.
        Server(setOf("go"), "gopls", "Gopls"),
        Server(setOf("rs"), "rust-analyzer", "RustAnalyzer"),
    )

    private fun serverFor(path: String): Server? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return servers.firstOrNull { ext in it.extensions }
    }

    fun handles(path: String): Boolean = serverFor(path) != null

    val provider = object : IntelProvider {
        override fun warm(context: Context) {
            servers.forEach { it.process.warm(context) }
        }

        override fun complete(context: Context, path: String, source: String, line: Int, column: Int) =
            serverFor(path)?.process?.ask(context, "c", path, source, line, column, timeoutMs = 8_000)
                ?.toCompletions().orEmpty()

        override fun definition(context: Context, path: String, source: String, line: Int, column: Int) =
            serverFor(path)?.process?.ask(context, "g", path, source, line, column, timeoutMs = 8_000)?.toLocation()
    }
}
