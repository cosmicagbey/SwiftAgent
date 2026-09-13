package com.momo.swift.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.momo.swift.service.SilentEvidenceCaptureManager
import com.momo.swift.ui.components.BounceButton
import com.momo.swift.ui.components.BounceTextButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EvidenceGalleryDialog(
    initialPhoneNumber: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var photos by remember {
        mutableStateOf(SilentEvidenceCaptureManager.getEvidencePhotos(context, initialPhoneNumber))
    }
    val selectedPhotos = remember { mutableStateListOf<File>() }
    var previewPhoto by remember { mutableStateOf<File?>(null) }
    var photoToDelete by remember { mutableStateOf<File?>(null) }

    fun refreshPhotos() {
        photos = SilentEvidenceCaptureManager.getEvidencePhotos(context, initialPhoneNumber)
        selectedPhotos.removeAll { !it.exists() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Top header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column {
                            Text(
                                text = "Evidence Vault",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (photos.isEmpty()) "No photos saved" else "${photos.size} photo(s) captured",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Bar (Multi-share and Select all / Deselect)
                if (photos.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (selectedPhotos.size == photos.size) {
                                    selectedPhotos.clear()
                                } else {
                                    selectedPhotos.clear()
                                    selectedPhotos.addAll(photos)
                                }
                            }
                        ) {
                            Text(
                                if (selectedPhotos.size == photos.size) "Deselect All"
                                else if (selectedPhotos.isEmpty()) "Select Multiple"
                                else "Selected (${selectedPhotos.size})"
                            )
                        }

                        if (selectedPhotos.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Delete selected button
                                IconButton(
                                    onClick = {
                                        val count = selectedPhotos.size
                                        selectedPhotos.forEach { file ->
                                            SilentEvidenceCaptureManager.deletePhoto(file)
                                        }
                                        refreshPhotos()
                                        Toast.makeText(context, "Deleted $count photo(s)", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Selected",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }

                                // Share selected button
                                Button(
                                    onClick = {
                                        SilentEvidenceCaptureManager.sharePhotos(
                                            context = context,
                                            photos = selectedPhotos.toList(),
                                            caption = "Captured fraudster facial evidence"
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share (${selectedPhotos.size})", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                // Photo Grid
                if (photos.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = "No facial evidence photos yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Photos captured silently during scammer traps will appear here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(photos, key = { it.absolutePath }) { photoFile ->
                            val isSelected = selectedPhotos.contains(photoFile)
                            val bitmap = remember(photoFile.lastModified()) {
                                try {
                                    BitmapFactory.decodeFile(photoFile.absolutePath)
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        // Tap to preview full picture
                                        previewPhoto = photoFile
                                    }
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Evidence Photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                // Selection checkmark overlay (tap top-right to toggle selection)
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else Color.Black.copy(alpha = 0.4f)
                                        )
                                        .clickable {
                                            if (isSelected) selectedPhotos.remove(photoFile)
                                            else selectedPhotos.add(photoFile)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                // Bottom timestamp banner
                                val dateText = remember(photoFile) {
                                    try {
                                        SimpleDateFormat("HH:mm - dd/MM", Locale.getDefault()).format(Date(photoFile.lastModified()))
                                    } catch (_: Exception) {
                                        ""
                                    }
                                }
                                Surface(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .align(Alignment.BottomCenter)
                                ) {
                                    Text(
                                        text = dateText,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom close button
                BounceButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close")
                }
            }
        }
    }

    // ── Full-Screen Photo Viewer Dialog ──────────────────────────────────────
    previewPhoto?.let { photo ->
        EvidencePhotoViewerDialog(
            photo = photo,
            onDismiss = { previewPhoto = null },
            onDelete = {
                photoToDelete = photo
            },
            onShare = {
                SilentEvidenceCaptureManager.sharePhotos(
                    context = context,
                    photos = listOf(photo),
                    caption = "Suspected MoMo Fraudster Facial Evidence"
                )
            }
        )
    }

    // ── Delete Confirmation Dialog ───────────────────────────────────────────
    photoToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { photoToDelete = null },
            icon = {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Delete This Photo?") },
            text = {
                Text("This will permanently remove this captured photo. Use this if the picture is blurry, dark, or not clear.")
            },
            confirmButton = {
                BounceButton(
                    onClick = {
                        val deleted = SilentEvidenceCaptureManager.deletePhoto(target)
                        if (deleted) {
                            if (previewPhoto?.absolutePath == target.absolutePath) {
                                previewPhoto = null
                            }
                            refreshPhotos()
                            Toast.makeText(context, "Photo deleted.", Toast.LENGTH_SHORT).show()
                        }
                        photoToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete Permanently", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                BounceTextButton(onClick = { photoToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Full-screen / enlarged photo viewer dialog with Share & Delete controls.
 */
@Composable
fun EvidencePhotoViewerDialog(
    photo: File,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val bitmap = remember(photo.absolutePath) {
        try {
            BitmapFactory.decodeFile(photo.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            color = Color.Black
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar with filename and close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Evidence Inspection",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(photo.lastModified())),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Centered Full-size Image
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Full Image Preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Text("Unable to load image", color = Color.White)
                    }
                }

                // Bottom Action Bar: Share & Delete
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Delete Button
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Delete")
                    }

                    // Share via WhatsApp / Email Button
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share via App")
                    }
                }
            }
        }
    }
}
