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
    private val onDebug: (String) -> Unit = {},
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

    /**
     * Reports what the script can actually see in the workbench DOM, once per load.
     *
     * This exists because of a real and expensive mistake: the editable element was
     * matched with `textarea.inputarea`, VS Code had since moved to the EditContext API,
     * and the selector silently matched nothing. Three separate attempts at fixing the
     * keyboard were no-ops on a null element and looked, from the outside, exactly like
     * fixes that did not work. One line in the log would have said so immediately.
     */
    @JavascriptInterface
    fun debug(message: String) {
        onDebug(message)
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

          /**
           * The editor's real editable element.
           *
           * VS Code used to keep a hidden <textarea class="inputarea">; since adopting the
           * EditContext API it uses a focusable <div class="native-edit-context"> instead,
           * and this build has the latter. Both are matched, newest first, because a
           * selector that matches nothing fails silently.
           */
          function editContext() {
            return document.querySelector('.native-edit-context') ||
                   document.querySelector('textarea.inputarea');
          }

          /**
           * Scrolling is not editing, so give up focus.
           *
           * Once a caret has been placed the editable stays focused, and the workbench
           * restores focus to it whenever a scroll settles - so the keyboard ducks away
           * during the drag and pops straight back up on release, over and over. Nothing
           * stops that while the element is still focused: a focused editable is, to
           * Android, a standing request for a keyboard. Blurring ends the argument. The
           * selection survives, so the caret is still there and the next tap resumes
           * editing exactly where it left off.
           */
          function blurInput() {
            var el = editContext();
            if (el) { try { el.blur(); } catch (err) {} }
          }

          var SLOP = 10;          // CSS px, a little over Android's touch slop
          var TAP_MS = 700;       // beyond this it is a long press, not a tap
          var down = null, moved = false, multi = false;

          function reset() { down = null; moved = false; multi = false; }

          // The moment a gesture becomes a scroll, drop focus. Doing it here rather than
          // on release means the keyboard goes down as the drag starts and has nothing to
          // come back to when it ends.
          function becameScroll() {
            if (moved) return;
            moved = true;
            blurInput();
          }

          document.addEventListener('pointerdown', function (e) {
            if (down) { multi = true; return; }
            down = { x: e.clientX, y: e.clientY, t: Date.now(), id: e.pointerId };
            moved = false;
          }, true);

          document.addEventListener('pointermove', function (e) {
            if (!down || e.pointerId !== down.id) return;
            if (Math.abs(e.clientX - down.x) > SLOP ||
                Math.abs(e.clientY - down.y) > SLOP) becameScroll();
          }, true);

          // Belt and braces: some scroll paths deliver touchmove or a scroll event
          // without a matching pointermove, and either still means "not a tap".
          document.addEventListener('touchmove', function () { if (down) becameScroll(); }, true);
          document.addEventListener('scroll', function () { if (down) becameScroll(); }, true);

          document.addEventListener('pointercancel', function () {
            var wasScroll = !!down && moved;
            reset();
            if (wasScroll) { blurInput(); setTimeout(blurInput, 120); }
          }, true);

          // Only a genuine tap asks for the keyboard: one finger, no meaningful movement,
          // and short. A scroll, a fling, a pinch or a long press for selection are none
          // of them a request to start typing.
          document.addEventListener('pointerup', function (e) {
            var tapped = !!down && !moved && !multi && (Date.now() - down.t) < TAP_MS;
            var target = e.target;
            reset();

            if (tapped) {
              try { $NAME.tap(inEditor(target)); } catch (err) {}
              return;
            }

            // The workbench restores focus while handling the release and again as the
            // scroll settles, so blur across that whole window rather than once - a
            // single blur here is simply undone a frame later.
            blurInput();
            setTimeout(blurInput, 120);
            setTimeout(blurInput, 400);
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

          // Say once, on load, whether the editable was found. If a future workbench
          // renames it again this line is the difference between a five-minute fix and
          // another round of changes that quietly do nothing.
          setTimeout(function () {
            try {
              var el = editContext();
              $NAME.debug('editable=' + (el ? el.tagName + '.' + el.className : 'NOT FOUND'));
            } catch (err) {}
          }, 2000);
        })();
        """.trimIndent()
    }
}
