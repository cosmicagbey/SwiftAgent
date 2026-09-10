package com.momo.swift.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun LegalDocumentDialog(
    title: String,
    content: String,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismissRequest) {
                Text("Close")
            }
        }
    )
}

@Composable
fun WipeDataConfirmDialog(
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(Icons.Rounded.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = {
            Text(
                text = "Wipe All Local Data?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "This will act as a complete factory reset. All custom shortcuts, transaction logs, limits, and settings will be permanently erased. Since this app operates locally and anonymously, this acts as your 'Account Deletion'. You cannot undo this action.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Wipe Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

object LegalDocs {
    val DISCLAIMER = """
        This app automates USSD commands solely on the user's behalf. 
        We are not a financial institution, bank, or telecom operator.
        
        The developer accepts NO liability for any financial loss, unauthorized transactions, or telecom blocking resulting from the use of this application.
        Always verify transaction amounts and destinations before executing custom scripts.
    """.trimIndent()

    val PRIVACY_STATEMENT = """
        Your privacy is our utmost priority.
        
        Swift Agent values your privacy and strives to collect only what is necessary:
        
        1. Local Data: Your transaction logs, settings, and shortcut scripts are stored locally on your device within encrypted databases.
        2. Account & Authentication: We use Firebase Authentication to securely link your email address to your account. This allows you to restore your subscription if you reinstall the app.
        3. Payments: Premium subscriptions are securely processed using Paystack. We do not store your credit card information.
        4. Fraud Reporting: If you choose to use the "Report Fraud" feature, the information you provide (fraudulent number, description, optional screenshot, and your linked email) is transmitted securely through our Firebase backend directly to the telecom fraud team.
        
        We do NOT sell your personal data to third parties.
    """.trimIndent()

    val HOW_IT_WORKS = """
        What Swift Agent DOES:
        • Replaces manual USSD dialing (like *171#) with rapid UI-based shortcuts.
        • Uses Accessibility Services to read dialog prompts and auto-fill sequences incredibly fast.
        • Aborts execution right before the final PIN entry phase to hand control back to you.
        • Records history of transaction outcomes locally.
        
        What Swift Agent DOES NOT DO:
        • Does NOT have access to your bank account or telecom wallet.
        • Cannot automatically guess or fill your PIN.
        • Does NOT intercept your telecom passwords.
        • Does NOT transmit the destination numbers to an external server.
    """.trimIndent()

    val TERMS_OF_SERVICE = """
        By using Swift Agent, you agree:
        1. You are the authorized owner of the SIM card installed on this device.
        2. You will not use custom USSD scripts for fraudulent, illegal, or scamming activities.
        3. You understand the app requires Android Accessibility permissions specifically to interact with telecom popups.
        4. You must ensure your telecom provider permits automated USSD interactions.
    """.trimIndent()
}
