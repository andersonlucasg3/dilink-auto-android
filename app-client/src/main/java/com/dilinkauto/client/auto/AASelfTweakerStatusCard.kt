package com.dilinkauto.client.auto

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dilinkauto.client.R
import com.dilinkauto.client.auto.AASelfTweakerStatus.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Status card for the Android Auto registration flow.
 *
 * Shows a checklist of the four registration prerequisites and a
 * "Re-run Registration" button. When root is unavailable, it displays
 * a warning instead of the checklist.
 */
@Composable
fun AASelfTweakerStatusCard(
    onRerun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<Result?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        status = withContext(Dispatchers.IO) {
            AASelfTweakerStatus.check(context)
        }
        loading = false
    }

    // Extract to local val to allow smart casts inside the composable body
    val resolvedStatus = status

    val noRoot = resolvedStatus?.rootAvailable != true

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (noRoot) {
                Color(0xFF332211)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CarRental,
                    contentDescription = null,
                    tint = when {
                        noRoot -> Color(0xFFFFA726)
                        resolvedStatus?.overallReady == true -> Color(0xFF4CAF50)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.aa_status_title),
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Text(
                        resolvedStatus?.message ?: stringResource(R.string.aa_status_loading),
                        fontSize = 12.sp,
                        color = when {
                            noRoot -> Color(0xFFFFA726)
                            resolvedStatus?.overallReady == true -> Color(0xFF4CAF50)
                            else -> Color.Gray
                        }
                    )
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (resolvedStatus?.overallReady == true) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Checklist (only when root is available)
            if (resolvedStatus?.rootAvailable == true && !loading) {
                val s = resolvedStatus!!
                Spacer(Modifier.height(12.dp))
                CheckItem(
                    label = stringResource(R.string.aa_check_root),
                    checked = s.rootAvailable
                )
                CheckItem(
                    label = stringResource(R.string.aa_check_installer),
                    checked = s.installerCorrect
                )
                CheckItem(
                    label = stringResource(R.string.aa_check_finsky),
                    checked = s.finskyRowsPresent
                )
                CheckItem(
                    label = stringResource(R.string.aa_check_phenotype),
                    checked = s.phenotypeFlagsApplied
                )
            }

            // Action button
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        AASelfTweaker.ensureRegistered(context)
                    }
                    onRerun()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                enabled = resolvedStatus?.rootAvailable == true
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.aa_rerun_button),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun CheckItem(label: String, checked: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (checked) Color(0xFF4CAF50) else Color(0xFFEF5350),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            fontSize = 13.sp,
            color = if (checked) Color(0xFFB0BEC5) else Color(0xFF757575)
        )
    }
}