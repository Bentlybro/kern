package dev.kern.app.ui

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
        const val NAME = "KernNative"

        /**
         * Injected after every page load. Reports taps (so the host can decide whether to
         * raise the IME) and selection geometry (so the host can draw native handles).
         * Reads only rendered DOM — no dependency on Monaco's internal API, which is not
         * reachable from the workbench page.
         *
         * "Tap" here means a real tap, not merely a pointerup: telling the two apart is
         * the difference between an editor you can read and one that throws the keyboard
         * up every time you scroll.
         */
        val SCRIPT = """
        (function () {
          if (window.__kernBridge) return;
          window.__kernBridge = true;

          function inEditor(el) {
            try { return !!(el && el.closest && el.closest('.monaco-editor')); }
            catch (e) { return false; }
          }

          // Only a genuine tap asks for the keyboard.
          //
          // Reporting every pointerup meant the release that ends a scroll drag looked
          // exactly like a tap, so reading down a file raised the IME over and over.
          // A tap is: one finger, no meaningful movement, and short. Anything else is a
          // scroll, a fling, a pinch or a long-press for selection - none of which are a
          // request to start typing.
          var SLOP = 10;          // CSS px, a little over Android's touch slop
          var TAP_MS = 700;       // beyond this it is a long press, not a tap
          var down = null, moved = false, multi = false;

          function reset() { down = null; moved = false; multi = false; }

          document.addEventListener('pointerdown', function (e) {
            if (down) { multi = true; return; }
            down = { x: e.clientX, y: e.clientY, t: Date.now(), id: e.pointerId };
            moved = false;
          }, true);

          document.addEventListener('pointermove', function (e) {
            if (!down || e.pointerId !== down.id) return;
            if (Math.abs(e.clientX - down.x) > SLOP ||
                Math.abs(e.clientY - down.y) > SLOP) moved = true;
          }, true);

          // Belt and braces: some scroll paths deliver touchmove or a scroll event
          // without a matching pointermove, and either still means "not a tap".
          document.addEventListener('touchmove', function () { moved = true; }, true);
          document.addEventListener('scroll', function () { if (down) moved = true; }, true);
          document.addEventListener('pointercancel', reset, true);

          document.addEventListener('pointerup', function (e) {
            var tapped = !!down && !moved && !multi && (Date.now() - down.t) < TAP_MS;
            var target = e.target;
            reset();
            if (!tapped) return;
            try { $NAME.tap(inEditor(target)); } catch (err) {}
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
