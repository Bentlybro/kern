package dev.kern.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kern.app.runtime.GitHubAuth
import kotlinx.coroutines.launch

/**
 * Everything needed to make git usable: install the tools, sign in, and show who the
 * guest is signed in as.
 *
 * The device code is deliberately native rather than something to read out of a
 * terminal — on a phone, copying eight characters by hand between two apps is the part
 * that makes people give up, so the button does the copying and opens GitHub.
 */
@Composable
fun GitHubSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val step by GitHubAuth.step.collectAsStateWithLifecycle()
    var account by remember { mutableStateOf<GitHubAuth.Account?>(null) }

    // Re-read the account whenever the flow settles, so the summary below is never
    // stale after a sign-in or sign-out.
    LaunchedEffect(step) {
        if (step is GitHubAuth.Step.Idle || step is GitHubAuth.Step.Done) {
            account = GitHubAuth.account(context)
        }
    }

    val busy = step is GitHubAuth.Step.Working || step is GitHubAuth.Step.AwaitingApproval

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val current = account) {
            null -> Text(
                "Checking...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is GitHubAuth.Account.ToolsMissing -> {
                Text(
                    "Not installed: ${current.missing.joinToString(", ")}.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "git is what clones and pushes your repositories; the GitHub CLI is " +
                        "what signs it in.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { GitHubAuth.installTools(context) } },
                ) { Text("Install git & GitHub CLI") }
            }

            is GitHubAuth.Account.SignedOut -> {
                Text(
                    "Not signed in.",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "You can clone public repositories without this, but pushing needs an " +
                        "account. Signing in also lets the editor's Git panel and anything " +
                        "running in the terminal push on your behalf.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !busy,
                    onClick = { scope.launch { GitHubAuth.signIn(context) } },
                ) { Text("Sign in to GitHub") }
            }

            is GitHubAuth.Account.SignedIn -> {
                Text(
                    "Signed in as ${current.login}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                current.email?.let {
                    Text(
                        "Commits are authored as $it",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "git push and git pull work over HTTPS with no further setup.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !busy,
                    onClick = { scope.launch { GitHubAuth.signOut(context) } },
                ) { Text("Sign out") }
            }
        }

        when (val current = step) {
            is GitHubAuth.Step.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.height(14.dp).width(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${current.what}...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            is GitHubAuth.Step.AwaitingApproval -> DeviceCodeCard(
                code = current.code,
                onCopyAndOpen = {
                    clipboard.setText(AnnotatedString(current.code))
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(GitHubAuth.DEVICE_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onCancel = { GitHubAuth.cancel() },
            )

            is GitHubAuth.Step.Failed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    current.message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = { GitHubAuth.reset() }) { Text("Dismiss") }
            }

            else -> Unit
        }
    }
}

/**
 * The one screen the user has to act on. Everything else in the flow is automatic, so
 * this is given the weight of a single instruction rather than a status line.
 */
@Composable
private fun DeviceCodeCard(
    code: String,
    onCopyAndOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary,
                RoundedCornerShape(10.dp),
            )
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                RoundedCornerShape(10.dp),
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Enter this code on github.com",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Button(onClick = onCopyAndOpen, modifier = Modifier.fillMaxWidth()) {
            Text("Copy code & open GitHub")
        }
        Text(
            "Come back here once GitHub says you are done — this finishes on its own.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
