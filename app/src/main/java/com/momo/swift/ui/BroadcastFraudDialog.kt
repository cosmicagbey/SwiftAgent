package com.momo.swift.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momo.swift.data.FraudDetectionManager
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastFraudDialog(
    initialPhoneNumber: String = "",
    onDismiss: () -> Unit,
    onBroadcastSuccess: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var phoneNumber by remember { mutableStateOf(initialPhoneNumber) }
    var description by remember { mutableStateOf("") }
    
    val fraudCategories = listOf(
        "Fake Reversal / Over-credit Call",
        "Fake SMS Alert",
        "Stolen Wallet / Forced Cashout",
        "Fake Agent / System Upgrade Scam",
        "Impersonation & Social Engineering",
        "Others"
    )
    var selectedCategory by remember { mutableStateOf(fraudCategories[0]) }
    var isCategoryDropdownExpanded by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Broadcast Scammer Alert",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Broadcast this fraudulent number to all SwiftAgent users. Other agents will be immediately warned if someone attempts a cash-out with this number.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Phone number field
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Scammer Phone Number *") },
                    placeholder = { Text("024XXXXXXX or +233...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // Category selector
                ExposedDropdownMenuBox(
                    expanded = isCategoryDropdownExpanded,
                    onExpandedChange = { isCategoryDropdownExpanded = !isCategoryDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Scam Category *") },
                        trailingIcon = {
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = isCategoryDropdownExpanded,
                        onDismissRequest = { isCategoryDropdownExpanded = false }
                    ) {
                        fraudCategories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = {
                                    selectedCategory = category
                                    isCategoryDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Description field
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Incident Notes / Details *") },
                    placeholder = { Text("e.g. Caller claimed they sent GHS 1,500 by mistake and guided client to dial *170#...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                errorMessage?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            BounceButton(
                onClick = {
                    if (phoneNumber.isBlank()) {
                        errorMessage = "Please enter the scammer phone number."
                        return@BounceButton
                    }
                    if (description.isBlank()) {
                        errorMessage = "Please provide brief incident notes."
                        return@BounceButton
                    }

                    coroutineScope.launch {
                        isLoading = true
                        errorMessage = null
                        val result = FraudDetectionManager.broadcastFraudNumber(
                            phoneNumber = phoneNumber,
                            fraudType = selectedCategory,
                            description = description
                        )
                        isLoading = false
                        result.fold(
                            onSuccess = {
                                onBroadcastSuccess(phoneNumber)
                            },
                            onFailure = { e ->
                                errorMessage = e.message ?: "Failed to broadcast. Please try again."
                            }
                        )
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("🚨 Broadcast to All Agents", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            BounceTextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) {
                Text("Cancel")
            }
        }
    )
}
