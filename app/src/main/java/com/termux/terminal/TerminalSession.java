package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import dev.kern.app.runtime.PtyProcess;

/**
 * A terminal session: a shell running on a pseudo-terminal owned by this app.
 *
 * Upstream Termux forks the shell through its own JNI. Kern does the same thing with
 * its own pty layer (cpp/pty.c), and the command is PRoot entering the bundled Linux
 * filesystem — so the shell is a child of this app, with no second app and no sockets
 * involved. The spawn itself is supplied by the caller through [ShellFactory], which
 * keeps this class free of any knowledge of PRoot.
 *
 * Original file: termux-app (GPLv3). Transport rewritten for Kern.
 */
public final class TerminalSession extends TerminalOutput {

    /** Supplies a live pty for a given terminal size. */
    public interface ShellFactory {
        PtyProcess spawn(int columns, int rows);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_PROCESS_EXITED = 4;

    private static final String LOG_TAG = "TerminalSession";

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    final ByteQueue mProcessToTerminalIOQueue = new ByteQueue(64 * 1024);
    final ByteQueue mTerminalToProcessIOQueue = new ByteQueue(4096);
    private final byte[] mUtf8InputBuffer = new byte[5];

    TerminalSessionClient mClient;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final ShellFactory mShellFactory;
    private final Integer mTranscriptRows;

    private volatile PtyProcess mProcess;
    private volatile boolean mRunning = true;
    private volatile int mExitStatus;

    public TerminalSession(ShellFactory shellFactory, Integer transcriptRows,
                           TerminalSessionClient client) {
        this.mShellFactory = shellFactory;
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;
    }

    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }

    /** Inform the pty of the new size and reflow, or start the shell on first call. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            initializeEmulator(columns, rows, cellWidthPixels, cellHeightPixels);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
            PtyProcess process = mProcess;
            // A real pty carries window size out of band, so the shell just gets SIGWINCH
            // and nothing is echoed to the screen.
            if (process != null) process.resize(columns, rows);
        }
    }

    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    public void initializeEmulator(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels,
            mTranscriptRows, mClient);

        final PtyProcess process = mShellFactory.spawn(columns, rows);
        if (process == null) {
            appendLocally("\r\n[Kern: could not start a shell. Is Linux set up?]\r\n");
            finish(-1);
            return;
        }
        mProcess = process;

        new Thread("KernTermReader") {
            @Override
            public void run() {
                try (InputStream in = process.getInput()) {
                    byte[] buffer = new byte[4096];
                    while (mRunning) {
                        int read = in.read(buffer);
                        if (read == -1) break;
                        if (!mProcessToTerminalIOQueue.write(buffer, 0, read)) break;
                        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    }
                } catch (Exception e) {
                    // Shell exited or the fd closed; the waiter reports the status.
                }
            }
        }.start();

        new Thread("KernTermWriter") {
            @Override
            public void run() {
                byte[] buffer = new byte[4096];
                try {
                    OutputStream out = process.getOutput();
                    while (mRunning) {
                        int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                        if (bytesToWrite == -1) return;
                        out.write(buffer, 0, bytesToWrite);
                        out.flush();
                    }
                } catch (Exception e) {
                    // Closed; nothing useful to do.
                }
            }
        }.start();

        new Thread("KernTermWaiter") {
            @Override
            public void run() {
                int status = process.waitFor();
                finish(status);
            }
        }.start();
    }

    private void appendLocally(final String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (mEmulator == null) return;
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            mEmulator.append(bytes, bytes.length);
            notifyScreenUpdate();
        });
    }

    private void finish(int status) {
        if (!mRunning) return;
        mExitStatus = status;
        mMainThreadHandler.sendMessage(
            mMainThreadHandler.obtainMessage(MSG_PROCESS_EXITED, status));
    }

    /** Write data to the shell process. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (mRunning) mTerminalToProcessIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else {
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    protected void notifyScreenUpdate() {
        if (mClient != null) mClient.onTextChanged(this);
    }

    public void reset() {
        if (mEmulator != null) mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Hang up the shell and everything it started. */
    public void finishIfRunning() {
        if (!mRunning) return;
        mRunning = false;
        mTerminalToProcessIOQueue.close();
        mProcessToTerminalIOQueue.close();
        PtyProcess process = mProcess;
        if (process != null) process.close();
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        if (mClient != null) mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return mRunning;
    }

    public synchronized int getExitStatus() {
        return mExitStatus;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        if (mClient != null) mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        if (mClient != null) mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        if (mClient != null) mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        if (mClient != null) mClient.onColorsChanged(this);
    }

    public int getPid() {
        PtyProcess process = mProcess;
        return process == null ? 0 : process.getPid();
    }

    /** Not resolvable from here; the guest's cwd lives behind PRoot. */
    public String getCwd() {
        return null;
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0 && mEmulator != null) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }

            if (msg.what == MSG_PROCESS_EXITED) {
                mRunning = false;
                int exitCode = (Integer) msg.obj;
                if (mEmulator != null) {
                    String description = "\r\n[Session ended";
                    if (exitCode > 0) description += " (code " + exitCode + ")";
                    else if (exitCode < 0) description += " (signal " + (-exitCode) + ")";
                    description += "]";
                    byte[] bytes = description.getBytes(StandardCharsets.UTF_8);
                    mEmulator.append(bytes, bytes.length);
                    notifyScreenUpdate();
                }
                if (mClient != null) mClient.onSessionFinished(TerminalSession.this);
            }
        }
    }
}
