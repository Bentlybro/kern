package dev.foldcode.app.ui

import android.webkit.JavascriptInterface

/**
 * The native side of the workbench bridge (M4).
 *
 * Monaco cannot tell us anything through the WebView's normal Android surface — it never
 * advertises an input connection, so `onCheckIsTextEditor()` is false and taps look
 * identical whether they land on code or on the file tree. Injected JS closes that gap:
 * it reports where a tap landed and what the current selection looks like, which is what
 * the native IME control and selection handles need.
 */
class WorkbenchBridge(
    private val onEditorTap: (Boolean) -> Unit,
    private val onSelection: (SelectionInfo?) -> Unit,
) {
    data class SelectionInfo(
        /** Selection rectangle in CSS pixels, relative to the WebView. */
        val startX: Float,
        val startY: Float,
        val startBottom: Float,
        val endX: Float,
        val endY: Float,
        val endBottom: Float,
        val empty: Boolean,
    )

    @JavascriptInterface
    fun tap(inEditor: Boolean) {
        onEditorTap(inEditor)
    }

    @JavascriptInterface
    fun selection(
        startX: Float,
        startY: Float,
        startBottom: Float,
        endX: Float,
        endY: Float,
        endBottom: Float,
        empty: Boolean,
    ) {
        onSelection(
            if (empty) null
            else SelectionInfo(startX, startY, startBottom, endX, endY, endBottom, false),
        )
    }

    companion object {
        const val NAME = "FoldCodeNative"

        /**
         * Injected after every page load. Reports taps (so the host can decide whether to
         * raise the IME) and selection geometry (so the host can draw native handles).
         * Reads only rendered DOM — no dependency on Monaco's internal API, which is not
         * reachable from the workbench page.
         */
        val SCRIPT = """
        (function () {
          if (window.__foldcodeBridge) return;
          window.__foldcodeBridge = true;

          function inEditor(el) {
            try { return !!(el && el.closest && el.closest('.monaco-editor')); }
            catch (e) { return false; }
          }

          document.addEventListener('pointerup', function (e) {
            try { $NAME.tap(inEditor(e.target)); } catch (err) {}
          }, true);

          function reportSelection() {
            try {
              var nodes = document.querySelectorAll('.monaco-editor .selected-text');
              if (!nodes || nodes.length === 0) {
                $NAME.selection(0, 0, 0, 0, 0, 0, true);
                return;
              }
              var first = nodes[0].getBoundingClientRect();
              var last = nodes[nodes.length - 1].getBoundingClientRect();
              $NAME.selection(
                first.left, first.top, first.bottom,
                last.right, last.top, last.bottom,
                false
              );
            } catch (err) {}
          }

          // Monaco repaints selection into fresh DOM nodes; poll rather than observe so
          // scrolling and reflow are picked up too. Cheap: one querySelectorAll.
          setInterval(reportSelection, 250);
        })();
        """.trimIndent()
    }
}
