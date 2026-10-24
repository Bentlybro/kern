package dev.kern.app.editor

import android.content.Context

/**
 * JavaScript and TypeScript intelligence from TypeScript's own language service - the
 * engine VS Code uses for both - running in a [WarmProcess] under node. `allowJs`, so plain
 * JavaScript gets the same completions and definitions, including node's built-in modules
 * when @types/node is installed.
 *
 * Needs node and TypeScript 5 in the guest (`npm i -g typescript@5 @types/node`) - 7 is the
 * native rewrite and has no JavaScript language-service API; without
 * them it answers nothing and the editor falls back to words from the file.
 */
object TsIntel {

    private val SERVER = """
let ts;
try { ts = require("typescript"); } catch (e) { ts = null; }
const fs = require("fs");
const files = {};
const host = ts && {
  getScriptFileNames: () => Object.keys(files),
  getScriptVersion: f => files[f] ? String(files[f].v) : "0",
  getScriptSnapshot: f => {
    const t = files[f] ? files[f].text : (fs.existsSync(f) ? fs.readFileSync(f, "utf8") : undefined);
    return t === undefined ? undefined : ts.ScriptSnapshot.fromString(t);
  },
  getCurrentDirectory: () => "/",
  // typeRoots: @types/node is installed globally, where TypeScript never looks by itself,
  // and without it `require("http")` is untyped and offers nothing.
  getCompilationSettings: () => ({
    allowJs: true, checkJs: false, target: 99, module: 1, moduleResolution: 2,
    typeRoots: [(process.env.NODE_PATH || "/usr/local/lib/node_modules") + "/@types"], types: ["node"],
  }),
  getDefaultLibFileName: o => ts.getDefaultLibFilePath(o),
  fileExists: ts.sys.fileExists, readFile: ts.sys.readFile, readDirectory: ts.sys.readDirectory,
  directoryExists: ts.sys.directoryExists, getDirectories: ts.sys.getDirectories,
};
const ls = ts && ts.createLanguageService(host);
require("readline").createInterface({ input: process.stdin }).on("line", line => {
  let r = {}, out = [];
  try {
    r = JSON.parse(line);
    if (ls) {
      const text = Buffer.from(r.s, "base64").toString("utf8");
      const f = r.p;
      files[f] = { v: (files[f] ? files[f].v : 0) + 1, text };
      const sf = ts.createSourceFile(f, text, 99);
      const pos = ts.getPositionOfLineAndCharacter(sf, r.l - 1, r.c);
      if (r.k === "c") {
        const prefix = (text.slice(0, pos).match(/[\w${'$'}]*${'$'}/) || [""])[0];
        const c = ls.getCompletionsAtPosition(f, pos, {});
        if (c) out = c.entries
          .filter(e => e.name.toLowerCase().startsWith(prefix.toLowerCase()))
          .slice(0, 80).map(e => [e.name, e.kind, prefix.length]);
      } else {
        const d = ls.getDefinitionAtPosition(f, pos) || [];
        out = d.map(x => {
          const s = ls.getProgram().getSourceFile(x.fileName);
          const lc = s ? s.getLineAndCharacterOfPosition(x.textSpan.start) : { line: 0, character: 0 };
          return [x.fileName, lc.line + 1, lc.character];
        });
      }
    }
  } catch (e) { out = []; }
  process.stdout.write("${WarmProcess.MARK}" + JSON.stringify({ i: r.i, r: out }) + "\n");
});
""".trimIndent()

    private val process = WarmProcess("Tsserver", "ts_server.js", SERVER,
        // `env`, because the command is exec'd and `exec VAR=x cmd` runs a program named
        // "VAR=x" - so node never started, and every answer was empty (caught on device).
        "env NODE_PATH=\$(npm root -g 2>/dev/null) node {}",
    )

    fun handles(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase() in setOf("js", "mjs", "cjs", "jsx", "ts", "tsx", "mts", "cts")

    fun warm(context: Context) = process.warm(context)

    fun complete(context: Context, path: String, source: String, line: Int, column: Int): List<IntelCompletion> =
        process.ask(context, "c", path, source, line, column, timeoutMs = 4_000, onlyIfIdle = true).toCompletions()

    fun definition(context: Context, path: String, source: String, line: Int, column: Int): IntelLocation? =
        process.ask(context, "g", path, source, line, column, timeoutMs = 6_000).toLocation()
}
