package com.momo.swift.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.ktx.auth
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import com.momo.swift.ui.components.bounceClickable
import com.momo.swift.ui.components.BounceIconButton
import com.momo.swift.ui.components.BounceButton
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFraudScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val userEmail = remember { Firebase.auth.currentUser?.email ?: "" }

    var reporterPhone by remember { mutableStateOf("") }
    var fraudulentPhone by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    
    val fraudTypes = listOf("Fake SMS", "Fake Promotions", "Business Scam", "Fake Blocking", "Fake Agent", "Others")
    var selectedFraudType by remember { mutableStateOf(fraudTypes[0]) }
    var isDropdownExpanded by remember { mutableStateOf(false) }
    
    val currentDateTime = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        selectedImageUri = uri
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            selectedImageBitmap = BitmapFactory.decodeStream(inputStream)
        }
    }

    fun compressAndEncodeImage(bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        val outputStream = ByteArrayOutputStream()
        // Compress to reduce payload size
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Report Fraud", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    BounceIconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        if (successMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(80.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = successMessage!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(32.dp))
                BounceButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text("Go Back")
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Join us in making mobile money safer for all agents.",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Your report helps prevent others from becoming victims of fraud.\nPlease complete the form below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }

                OutlinedTextField(
                    value = reporterPhone,
                    onValueChange = { reporterPhone = it },
                    label = { Text("Your Phone Number (Optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = fraudulentPhone,
                    onValueChange = { fraudulentPhone = it },
                    label = { Text("Fraudulent Phone Number *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null && fraudulentPhone.isBlank(),
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded,
                    onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedFraudType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type of Fraud *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { isDropdownExpanded = false }
                    ) {
                        fraudTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    selectedFraudType = type
                                    isDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = currentDateTime,
                    onValueChange = {},
                    label = { Text("Date and Time of Incident") },
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / Details *") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    isError = errorMessage != null && description.isBlank(),
                    maxLines = 5
                )

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bounceClickable {
                            imagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (selectedImageBitmap != null) {
                            Image(
                                bitmap = selectedImageBitmap!!.asImageBitmap(),
                                contentDescription = "Attached Screenshot",
                                modifier = Modifier
                                    .height(150.dp)
                                    .fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Tap to change screenshot", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Attach Screenshot (Optional)", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                BounceButton(
                    onClick = {
                        if (fraudulentPhone.isBlank() || description.isBlank()) {
                            errorMessage = "Please fill in all required fields."
                            return@BounceButton
                        }
                        
                        isLoading = true
                        errorMessage = null
                        
                        coroutineScope.launch {
                            try {
                                val base64Image = compressAndEncodeImage(selectedImageBitmap)
                                val data = hashMapOf(
                                    "reporterPhone" to reporterPhone,
                                    "fraudulentPhone" to fraudulentPhone,
                                    "fraudType" to selectedFraudType,
                                    "datetime" to currentDateTime,
                                    "description" to description,
                                    "imageBase64" to base64Image,
                                    "userEmail" to userEmail
                                )
                                
                                Firebase.functions.getHttpsCallable("reportFraud").call(data)
                                
                                isLoading = false
                                successMessage = "Report sent successfully. Thank you for helping fight fraud."
                            } catch (e: Exception) {
                                isLoading = false
                                errorMessage = "Failed to send report: ${e.localizedMessage}"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Sending Report...")
                    } else {
                        Text("Submit Report", fontWeight = FontWeight.Bold)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
