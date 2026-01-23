package com.rifsxd.ksunext.ui.screen

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.FlashScreenDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.EmptyDestinationsNavigator
import com.rifsxd.ksunext.*
import com.rifsxd.ksunext.R
import com.rifsxd.ksunext.ui.component.DialogHandle
import com.rifsxd.ksunext.ui.component.SelectionDialog
import com.rifsxd.ksunext.ui.component.ConfirmResult
import com.rifsxd.ksunext.ui.component.rememberConfirmDialog
import com.rifsxd.ksunext.ui.component.BlurDialog
import com.rifsxd.ksunext.ui.component.rememberCustomDialog
import com.rifsxd.ksunext.ui.util.*
import kotlinx.coroutines.launch
import android.widget.Toast
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * @author weishu
 * @date 2024/3/12.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Destination<RootGraph>
@Composable
fun InstallScreen(navigator: DestinationsNavigator) {

    var installMethod by remember {
        mutableStateOf<InstallMethod?>(null)
    }

    val scope = rememberCoroutineScope()

    var lkmSelection by remember {
        mutableStateOf<LkmSelection>(LkmSelection.KmiNone)
    }

    var allowShell by rememberSaveable { mutableStateOf(false) }
    var enableAdbd by rememberSaveable { mutableStateOf(false) }
    var noInstall by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current

    val onInstall = {
        installMethod?.let { method ->
            if (method is InstallMethod.AnyKernel) {
                method.uri?.let {
                    navigator.navigate(
                        FlashScreenDestination(FlashIt.FlashAnyKernel(it))
                    )
                }
                return@let
            }

            val flashIt = FlashIt.FlashBoot(
                boot = if (method is InstallMethod.SelectFile) method.uri else null,
                lkm = lkmSelection,
                ota = method is InstallMethod.DirectInstallToInactiveSlot,
                allowShell = allowShell,
                enableAdbd = enableAdbd,
                noInstall = noInstall
            )
            navigator.navigate(FlashScreenDestination(flashIt))
        }
    }

    val currentKmi by produceState(initialValue = "") { value = getCurrentKmi() }

    val selectKmiDialog = rememberSelectKmiDialog { kmi ->
        kmi?.let {
            lkmSelection = LkmSelection.KmiString(it)
            onInstall()
        }
    }

    val onClickNext = {
        when (installMethod) {
            is InstallMethod.AnyKernel -> {
                onInstall()
            }

            else -> {
                if (!noInstall && lkmSelection == LkmSelection.KmiNone && currentKmi.isBlank()) {
                    // no lkm file selected and cannot get current kmi
                    selectKmiDialog.show()
                } else {
                    onInstall()
                }
            }
        }
    }

    val selectLkmLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                it.data?.data?.let { uri ->
                    lkmSelection = LkmSelection.LkmUri(uri)
                }
            }
        }

    val repackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                scope.launch(Dispatchers.IO) {
                    try {
                        // Copy to cache
                        val cacheFile = File(ksuApp.cacheDir, "repack_source")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                        val cmd = "magiskboot repack --source \"${cacheFile.absolutePath}\" --out \"$outDir\""
                        val success = execKsud(cmd)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, if (success) context.getString(R.string.magiskboot_repack_success) else context.getString(R.string.magiskboot_repack_failed), Toast.LENGTH_SHORT).show()
                        }
                        cacheFile.delete()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    val onLkmUpload = {
        selectLkmLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/octet-stream"
        })
    }

    val kernelVersion = getKernelVersion()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopBar(
                onBack = dropUnlessResumed { navigator.popBackStack() },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            val rootAvailable = rootAvailable()
            val isAbDevice = produceState(initialValue = false) {
                value = isAbDevice()
            }.value

            var isLkmCategory by remember { mutableStateOf(true) }

            val selectFileTip = stringResource(
                id = R.string.select_file_tip,
                if (kernelVersion.isKernel510())
                    "boot"
                else
                    "init_boot/vendor_boot"
            )

            // LKM Card
            InstallCategoryCard(
                title = stringResource(R.string.install_category_lkm),
                selected = isLkmCategory,
                infoTitle = stringResource(R.string.lkm_mode_title),
                infoDesc = stringResource(R.string.lkm_mode_desc),
                onSelect = { isLkmCategory = true; installMethod = null }
            ) {
                // LKM Methods
                val lkmMethods = mutableListOf<InstallMethod>()
                lkmMethods.add(InstallMethod.SelectFile(summary = selectFileTip))
                if (rootAvailable) {
                    if (kernelVersion.isGKI()) {
                        lkmMethods.add(InstallMethod.DirectInstall)
                        if (isAbDevice) {
                            lkmMethods.add(InstallMethod.DirectInstallToInactiveSlot)
                        }
                    }
                }

                val selectImageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    if (it.resultCode == Activity.RESULT_OK) {
                        it.data?.data?.let { uri ->
                            installMethod = InstallMethod.SelectFile(uri, summary = selectFileTip)
                        }
                    }
                }

                val confirmDialog = rememberConfirmDialog()
                val dialogTitle = stringResource(id = android.R.string.dialog_alert_title)
                val dialogContent = stringResource(id = R.string.install_inactive_slot_warning)


                Column(modifier = Modifier.padding(16.dp)) {
                    lkmMethods.forEach { method ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = (installMethod?.javaClass == method.javaClass),
                                    onClick = {
                                        when (method) {
                                            is InstallMethod.SelectFile -> installMethod = method // Just select, don't launch yet
                                            is InstallMethod.DirectInstall -> installMethod = method
                                            is InstallMethod.DirectInstallToInactiveSlot -> installMethod = method // Warning on install click? Or here?
                                            else -> {}
                                        }
                                    },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (installMethod?.javaClass == method.javaClass),
                                onClick = null
                            )
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(
                                    text = stringResource(id = method.label),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (method.summary != null) {
                                    Text(
                                        text = method.summary!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (method is InstallMethod.SelectFile && installMethod is InstallMethod.SelectFile) {
                                     (installMethod as InstallMethod.SelectFile).uri?.let { uri ->
                                         Text(
                                            text = uri.lastPathSegment ?: "(file)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                         )
                                     }
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Custom LKM Picker (Moved from TopBar)
                    if (kernelVersion.isGKI()) {
                         Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onLkmUpload() }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Icon(Icons.Filled.FileUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                             Column(modifier = Modifier.padding(start = 16.dp)) {
                                 Text(
                                     text = stringResource(R.string.install_select_custom_lkm),
                                     style = MaterialTheme.typography.bodyLarge
                                 )
                                 (lkmSelection as? LkmSelection.LkmUri)?.let {
                                     Text(
                                        text = it.uri.lastPathSegment ?: "(file)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                     )
                                 }
                             }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    }

                    // Install Options
                    SelectInstallOptions(
                        allowShell, { allowShell = it },
                        enableAdbd, { enableAdbd = it },
                        noInstall, { noInstall = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = installMethod != null && installMethod !is InstallMethod.AnyKernel,
                        onClick = {
                            if (installMethod is InstallMethod.SelectFile && (installMethod as InstallMethod.SelectFile).uri == null) {
                                selectImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                                    type = "application/octet-stream"
                                })
                            } else if (installMethod is InstallMethod.DirectInstallToInactiveSlot) {
                                scope.launch {
                                    val result = confirmDialog.awaitConfirm(dialogTitle, dialogContent)
                                    if (result == ConfirmResult.Confirmed) {
                                        onClickNext()
                                    }
                                }
                            } else {
                                onClickNext()
                            }
                        }
                    ) {
                        Text(stringResource(R.string.install_continue))
                    }
                }
            }

            // GKI / AnyKernel Card
            InstallCategoryCard(
                title = stringResource(R.string.install_category_gki),
                selected = !isLkmCategory,
                infoTitle = stringResource(R.string.gki_mode_title),
                infoDesc = stringResource(R.string.gki_mode_desc),
                onSelect = {
                    if (rootAvailable) {
                        isLkmCategory = false
                        installMethod = InstallMethod.AnyKernel() // Default to AnyKernel
                    }
                }
            ) {
                if (!rootAvailable) {
                    Text(
                        text = "Root required for AnyKernel flashing",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    val selectAnyKernelLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) {
                        if (it.resultCode == Activity.RESULT_OK) {
                            it.data?.data?.let { uri ->
                                installMethod = InstallMethod.AnyKernel(uri)
                            }
                        }
                    }

                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = true, onClick = null)
                            Column(modifier = Modifier.padding(start = 16.dp)) {
                                Text(
                                    text = stringResource(R.string.anykernel_install),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (installMethod is InstallMethod.AnyKernel) {
                                     (installMethod as InstallMethod.AnyKernel).uri?.let { uri ->
                                         Text(
                                            text = uri.lastPathSegment ?: "(file)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                         )
                                     }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                if (installMethod is InstallMethod.AnyKernel && (installMethod as InstallMethod.AnyKernel).uri == null) {
                                     selectAnyKernelLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "application/zip"
                                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    })
                                } else {
                                    onClickNext()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.install_continue))
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.magiskboot_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val outDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                                        val cmd = "magiskboot unpack --out \"$outDir\""
                                        val success = execKsud(cmd)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(context, if (success) context.getString(R.string.magiskboot_unpack_success) else context.getString(R.string.magiskboot_unpack_failed), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.magiskboot_unpack))
                            }

                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    repackLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
                                    })
                                }
                            ) {
                                Text(stringResource(R.string.magiskboot_repack))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InstallCategoryCard(
    title: String,
    selected: Boolean,
    infoTitle: String? = null,
    infoDesc: String? = null,
    onSelect: () -> Unit,
    content: @Composable () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo && infoTitle != null && infoDesc != null) {
        BlurDialog(
            onDismissRequest = { showInfo = false }
        ) {
            Text(
                text = infoTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = infoDesc,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { onSelect() },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = onSelect)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .weight(1f),
                    fontWeight = FontWeight.Bold
                )
                if (infoTitle != null && infoDesc != null) {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.issue_report_title) // Using a generic string or "Info"
                        )
                    }
                }
            }
            if (selected) {
                HorizontalDivider()
                content()
            }
        }
    }
}


@Composable
private fun SelectInstallOptions(
    allowShell: Boolean,
    onAllowShellChange: (Boolean) -> Unit,
    enableAdbd: Boolean,
    onEnableAdbdChange: (Boolean) -> Unit,
    noInstall: Boolean,
    onNoInstallChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        // Allow Shell
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = allowShell,
                    onValueChange = onAllowShellChange,
                    role = Role.Switch
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.install_option_allow_shell),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.install_option_allow_shell_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = allowShell, onCheckedChange = null)
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Enable Adbd
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = enableAdbd,
                    onValueChange = onEnableAdbdChange,
                    role = Role.Switch
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.install_option_enable_adbd),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.install_option_enable_adbd_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enableAdbd, onCheckedChange = null)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // No Install
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = noInstall,
                    onValueChange = onNoInstallChange,
                    role = Role.Checkbox
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.install_option_no_install),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.install_option_no_install_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Checkbox(checked = noInstall, onCheckedChange = null)
        }
    }
}

sealed class InstallMethod {
    data class SelectFile(
        val uri: Uri? = null,
        @param:StringRes override val label: Int = R.string.select_file,
        override val summary: String?
    ) : InstallMethod()

    data class AnyKernel(
        val uri: Uri? = null,
        @param:StringRes override val label: Int = R.string.anykernel_install,
        override val summary: String? = null
    ) : InstallMethod()

    data object DirectInstall : InstallMethod() {
        override val label: Int
            get() = R.string.direct_install
    }

    data object DirectInstallToInactiveSlot : InstallMethod() {
        override val label: Int
            get() = R.string.install_inactive_slot
    }

    abstract val label: Int
    open val summary: String? = null
}

@Composable
private fun SelectInstallMethod(onSelected: (InstallMethod) -> Unit = {}) {
    val rootAvailable = rootAvailable()
    val isAbDevice = produceState(initialValue = false) {
        value = isAbDevice()
    }.value
    val kernelVersion = getKernelVersion()
    val selectFileTip = stringResource(
        id = R.string.select_file_tip,
        if (kernelVersion.isKernel510())
            "boot"
        else
            "init_boot/vendor_boot"
    )
    val radioOptions = mutableListOf<InstallMethod>()

    radioOptions.add(InstallMethod.SelectFile(summary = selectFileTip))

    if (rootAvailable) {
        if (kernelVersion.isGKI()) {
            radioOptions.add(InstallMethod.DirectInstall)
            if (isAbDevice) {
                radioOptions.add(InstallMethod.DirectInstallToInactiveSlot)
            }
        }

        radioOptions.add(InstallMethod.AnyKernel())
    }

    var selectedOption by remember { mutableStateOf<InstallMethod?>(null) }
    val selectImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                val option = InstallMethod.SelectFile(uri, summary = selectFileTip)
                selectedOption = option
                onSelected(option)
            }
        }
    }

    val selectAnyKernelLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.data?.let { uri ->
                val option = InstallMethod.AnyKernel(uri)
                selectedOption = option
                onSelected(option)
            }
        }
    }

    val confirmDialog = rememberConfirmDialog(onConfirm = {
        selectedOption = InstallMethod.DirectInstallToInactiveSlot
        onSelected(InstallMethod.DirectInstallToInactiveSlot)
    }, onDismiss = null)
    val dialogTitle = stringResource(id = android.R.string.dialog_alert_title)
    val dialogContent = stringResource(id = R.string.install_inactive_slot_warning)

    val onClick = { option: InstallMethod ->

        when (option) {
            is InstallMethod.SelectFile -> {
                selectImageLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/octet-stream"
                })
            }

            is InstallMethod.AnyKernel -> {
                selectAnyKernelLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                    addCategory(Intent.CATEGORY_OPENABLE)
                })
            }

            is InstallMethod.DirectInstall -> {
                selectedOption = option
                onSelected(option)
            }

            is InstallMethod.DirectInstallToInactiveSlot -> {
                confirmDialog.showConfirm(dialogTitle, dialogContent)
            }
        }
    }

    Column {
        radioOptions.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = (option == selectedOption),
                        onClick = { onClick(option) },
                        role = Role.RadioButton
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (option == selectedOption),
                    onClick = null // null recommended for accessibility with selectable
                )
                Column(
                    modifier = Modifier.padding(start = 16.dp)
                ) {
                    Text(
                        text = stringResource(id = option.label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (option.summary != null) {
                        Text(
                            text = option.summary!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior? = null
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.install),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior
    )
}

private interface SelectKmiDialogHandle {
    fun show()
}

@Composable
private fun rememberSelectKmiDialog(onSelected: (String?) -> Unit): SelectKmiDialogHandle {
    var showDialog by remember { mutableStateOf(false) }
    
    val kmis by produceState<List<String>>(initialValue = emptyList()) {
        value = getSupportedKmis()
    }

    if (showDialog) {
        SelectionDialog(
            title = stringResource(R.string.select_kmi),
            options = kmis.map { it to it },
            selectedOption = "",
            onOptionSelected = {
                onSelected(it)
                showDialog = false
            },
            onDismissRequest = {
                showDialog = false
            }
        )
    }

    return remember {
        object : SelectKmiDialogHandle {
            override fun show() {
                showDialog = true
            }
        }
    }
}
