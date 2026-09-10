package com.momo.swift.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.momo.swift.data.CustomButton

@Composable
fun CustomButtonDialog(
    initialButton: CustomButton? = null,
    onDismiss: () -> Unit, 
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialButton?.title ?: "") }
    var ussdTextFieldValue by remember { 
        mutableStateOf(
            TextFieldValue(
                text = initialButton?.ussdTemplate ?: "",
                selection = TextRange(initialButton?.ussdTemplate?.length ?: 0)
            )
        ) 
    }

    /** Inserts [insertion] at the current cursor position and moves cursor to the end of insertion. */
    fun insertAtCursor(insertion: String) {
        val text = ussdTextFieldValue.text
        val selection = ussdTextFieldValue.selection
        
        // Find where to insert
        val start = selection.start.coerceIn(0, text.length)
        val end = selection.end.coerceIn(0, text.length)
        
        // Construct new text
        val newText = text.replaceRange(start, end, insertion)
        
        // Move cursor to the end of the newly inserted tag
        val newCursorPosition = start + insertion.length
        
        ussdTextFieldValue = TextFieldValue(
            text = newText,
            selection = TextRange(newCursorPosition)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialButton == null) "New Custom Shortcut" else "Edit Custom Shortcut") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Shortcut Name") },
                    placeholder = { Text("e.g., Momo Pay") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = ussdTextFieldValue,
                    onValueChange = { ussdTextFieldValue = it },
                    label = { Text("USSD String") },
                    placeholder = { Text("e.g., *171*1*2*1#") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                // ── Placeholder insert chips ──
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { insertAtCursor("<number>") },
                        label = { Text("+ Number") }
                    )
                    AssistChip(
                        onClick = { insertAtCursor("<amount>") },
                        label = { Text("+ Amount") }
                    )
                }
                Text(
                    "Use <number> and <amount> as placeholders for dynamic input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            BounceButton(
                onClick = {
                    if (title.isNotBlank() && ussdTextFieldValue.text.isNotBlank()) {
                        onSave(title, ussdTextFieldValue.text)
                        onDismiss()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            BounceTextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
