package org.lior.gymbro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Chooses the folder that keeps the user's data (see [Repo]): shown at the first start (and after a reinstall), and
 * from the statistics page or the launcher's warning to choose another one.
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var result: TextView
    private lateinit var cont: Button

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) {
            AppLog.i("setup", "folder choice cancelled")
            return@registerForActivityResult
        }
        AppLog.i("setup", "folder chosen", "uri" to uri.toString())
        result.text = "Reading the folder…"
        Thread {
            val text = try {
                Repo.attachFolder(uri)
            } catch (e: Exception) {
                AppLog.e("setup", "the folder cannot be used", e, "uri" to uri.toString())
                null
            }
            runOnUiThread {
                if (text == null) {
                    result.text = "GymBro cannot write in that folder. Please choose another one (for example Documents)."
                } else {
                    result.text = text
                    cont.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        result = findViewById(R.id.setup_result)
        cont = findViewById(R.id.setup_continue)
        val change = intent.getBooleanExtra(EXTRA_CHANGE, false)
        if (change) {
            findViewById<TextView>(R.id.setup_title).text = "Choose GymBro's data folder"
            result.text = "Now: ${Repo.folderDescription()}"
        }
        findViewById<Button>(R.id.setup_choose).setOnClickListener {
            AppLog.i("setup", "choose folder tapped")
            // start in Documents where the phone allows it
            val start = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
            try {
                pick.launch(Repo.treeUri ?: start)
            } catch (e: Exception) {
                AppLog.e("setup", "the folder chooser could not open", e)
                result.text = "The phone's folder chooser could not open: ${e.message}"
            }
        }
        cont.setOnClickListener {
            AppLog.i("setup", "continue tapped")
            if (!change) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    companion object {
        const val EXTRA_CHANGE = "change"
    }
}
