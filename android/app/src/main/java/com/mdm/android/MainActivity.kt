package com.mdm.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.mdm.android.ui.MdmTheme
import com.mdm.android.ui.MainScreen
import com.mdm.android.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.ensureServiceStarted()
        handleIncomingIntent(intent)

        setContent {
            MdmTheme(themeStyle = viewModel.themeStyle) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { viewModel.addLink(it) }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                // 文件管理器里点的 .torrent 文件：按 MIME 或 .torrent 后缀认出来，
                // 走种子文件那路（先拷到应用私有目录再弹文件列表）；其余的还是按链接处理。
                if (uri != null && (intent.type == "application/x-bittorrent" ||
                        uri.path?.endsWith(".torrent", ignoreCase = true) == true)
                ) {
                    viewModel.addTorrentFile(uri)
                } else {
                    intent.dataString?.let { viewModel.addLink(it) }
                }
            }
        }
    }
}
