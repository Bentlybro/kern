# R8 rules for the release build.
#
# Two things in Kern are reached by NAME at runtime rather than by a reference R8 can
# follow, so shrinking them is silent until the feature is used — and both features are
# central rather than incidental. Neither failure would appear in a debug build, which is
# the only kind anyone runs during development.

# ---- the JNI pty ----------------------------------------------------------
#
# cpp/pty.c binds statically: its symbols are spelled Java_dev_kern_app_runtime_Pty_*, so
# the class's fully-qualified name and its native method names are part of the ABI. Rename
# either and System.loadLibrary succeeds, the class loads, and the first call fails with
# UnsatisfiedLinkError — which is to say the first time anyone opens a terminal.
#
# proguard-android-optimize.txt already carries a -keepclasseswithmembernames rule for
# native methods. This is here anyway: that file is Google's and can change, and this is
# not a dependency worth leaving implicit.
-keepclasseswithmembernames,includedescriptorclasses class dev.kern.app.runtime.Pty {
    native <methods>;
}

# PtyProcess is handed to the vendored Termux session code as a plain object whose
# getInput/getOutput/getPid it calls. Those are ordinary references R8 can follow, so no
# rule is needed for them — noted because their absence here looks like an oversight.

# ---- the workbench bridge -------------------------------------------------
#
# WorkbenchBridge's methods are called from injected JavaScript by name, through
# addJavascriptInterface. R8 sees no caller for any of them and removes the lot, which
# does not crash: the injected script's `try { KernNative.tap(...) } catch (err) {}`
# swallows it, and the result is an editor that silently stops raising the keyboard and
# stops reporting selection. That is the worst shape a release-only regression can take,
# so it is kept explicitly rather than relying on a global annotation rule.
-keepclassmembers class dev.kern.app.ui.WorkbenchBridge {
    @android.webkit.JavascriptInterface <methods>;
}
# The global form as well, so a second bridge added later is covered without anyone having
# to remember this file exists.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- diagnostics ----------------------------------------------------------
#
# Kern's failure reporting is built from stack traces the user copies out of the app
# (Diagnostics), and an obfuscated trace from a release build is unreadable to the person
# receiving the bug report. Line numbers cost a little size and are worth it.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
