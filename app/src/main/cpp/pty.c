/*
 * Kern native PTY.
 *
 * Allocates a pseudo-terminal and forks a child onto its slave side. This is what lets
 * the app run a shell *in its own process tree* instead of asking another app to do it.
 *
 * The child is almost always the bundled PRoot, exec'd from the app's nativeLibraryDir.
 * That directory is read-only, so the exec is permitted under Android's W^X rules;
 * binaries inside the (writable) Linux rootfs are then loaded by PRoot's own loader
 * rather than by the kernel, which is what keeps the whole scheme legal at a modern
 * targetSdk.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <errno.h>
#include <android/log.h>

#define LOG_TAG "KernPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char **to_c_array(JNIEnv *env, jobjectArray array, int *out_count) {
    int count = array ? (*env)->GetArrayLength(env, array) : 0;
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (!result) {
        *out_count = 0;
        return NULL;
    }
    for (int i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }
    result[count] = NULL;
    *out_count = count;
    return result;
}

static void free_c_array(char **array, int count) {
    if (!array) return;
    for (int i = 0; i < count; i++) free(array[i]);
    free(array);
}

JNIEXPORT jint JNICALL
Java_dev_kern_app_guest_Pty_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring j_cmd, jobjectArray j_argv, jobjectArray j_envp,
        jstring j_cwd, jint columns, jint rows, jintArray j_pid_out) {
    (void) clazz;

    /* O_CLOEXEC: without it this fd leaks into every later fork in the process. */
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) {
        LOGE("posix_openpt: %s", strerror(errno));
        return -1;
    }
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(master);
        return -1;
    }

    /* ptsname() returns a pointer to static storage; copy before forking. */
    char slave_name[PATH_MAX];
    const char *pts = ptsname(master);
    if (!pts) {
        LOGE("ptsname: %s", strerror(errno));
        close(master);
        return -1;
    }
    strncpy(slave_name, pts, sizeof(slave_name) - 1);
    slave_name[sizeof(slave_name) - 1] = '\0';

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (columns > 0 ? columns : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(master, TIOCSWINSZ, &ws);

    int argc = 0, envc = 0;
    char **argv = to_c_array(env, j_argv, &argc);
    char **envp = to_c_array(env, j_envp, &envc);
    const char *cmd = (*env)->GetStringUTFChars(env, j_cmd, NULL);
    const char *cwd = j_cwd ? (*env)->GetStringUTFChars(env, j_cwd, NULL) : NULL;

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, j_cmd, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
        free_c_array(argv, argc);
        free_c_array(envp, envc);
        close(master);
        return -1;
    }

    if (pid == 0) {
        /* Child: become a session leader so the pty is a real controlling terminal —
         * without this, job control and Ctrl-C do not work. */
        close(master);
        setsid();

        int slave = open(slave_name, O_RDWR);
        if (slave < 0) _exit(1);

#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        /* The JVM masks signals it cares about; a shell needs them at defaults. */
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        signal(SIGPIPE, SIG_DFL);

        if (cwd && cwd[0] != '\0') {
            if (chdir(cwd) != 0) { /* non-fatal: fall through to the child's default */ }
        }

        execve(cmd, argv, envp);

        /* execve only returns when it failed, and stderr is already the pty slave, so say
         * why. Exiting silently here is indistinguishable from a process that started
         * cleanly and then vanished: the parent is handed a live master fd either way, sees
         * no output, and gets EIO milliseconds later. That cost a long evening once. */
        {
            char msg[512];
            int n = snprintf(msg, sizeof(msg), "kern: could not exec %s: %s\n",
                             cmd, strerror(errno));
            if (n > 0) {
                ssize_t ignored = write(STDERR_FILENO, msg, (size_t) n);
                (void) ignored;
            }
        }
        _exit(127);
    }

    (*env)->ReleaseStringUTFChars(env, j_cmd, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, j_cwd, cwd);
    free_c_array(argv, argc);
    free_c_array(envp, envc);

    if (j_pid_out) {
        jint pid_value = (jint) pid;
        (*env)->SetIntArrayRegion(env, j_pid_out, 0, 1, &pid_value);
    }
    return master;
}

JNIEXPORT void JNICALL
Java_dev_kern_app_guest_Pty_setWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint columns, jint rows) {
    (void) env; (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) columns;
    ws.ws_row = (unsigned short) rows;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_kern_app_guest_Pty_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    (void) env; (void) clazz;
    int status = 0;
    while (waitpid((pid_t) pid, &status, 0) < 0 && errno == EINTR) { /* retry */ }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return status;
}

/**
 * Whether the leader has exited, without reaping it. WNOWAIT leaves the zombie for the
 * caller's own waitpid - which is also what keeps its pid, and so the group id, from being
 * handed to an unrelated process while this is still signalling it.
 */
static int leader_exited(pid_t pid) {
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    if (waitid(P_PID, (id_t) pid, &info, WEXITED | WNOHANG | WNOWAIT) != 0) {
        /* ECHILD: someone else already reaped it, so it is certainly gone. */
        return errno != EINTR;
    }
    return info.si_pid != 0;
}

/**
 * End the child's whole process group. The child called setsid(), so its pgid equals its
 * pid — killing only the pid would orphan everything the shell started.
 *
 * Asked first, then told. SIGKILL alone gave nothing a chance to clean up: git left
 * .git/index.lock behind and every later git command in that repo refused to run, and a
 * dpkg killed mid-unpack left the guest needing `dpkg --configure -a`. So the group gets a
 * hangup and a terminate, up to grace_ms to act on them, and only then the kill. The
 * leader is PRoot, which does not exit until everything it traces has, so its exit is the
 * signal that the group is done.
 *
 * Blocks for up to grace_ms: never call it with a grace on the main thread.
 */
JNIEXPORT void JNICALL
Java_dev_kern_app_guest_Pty_killProcessGroup(
        JNIEnv *env, jclass clazz, jint pid, jint grace_ms) {
    (void) env; (void) clazz;
    if (pid <= 0) return;
    pid_t group = (pid_t) -pid;
    kill(group, SIGHUP);
    kill(group, SIGTERM);
    /* A stopped job cannot act on either until it is continued. */
    kill(group, SIGCONT);
    for (int waited = 0; waited < grace_ms; waited += 50) {
        if (kill(group, 0) != 0 && errno == ESRCH) return;
        if (leader_exited((pid_t) pid)) break;
        usleep(50 * 1000);
    }
    kill(group, SIGKILL);
}

JNIEXPORT void JNICALL
Java_dev_kern_app_guest_Pty_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env; (void) clazz;
    if (fd >= 0) close(fd);
}
