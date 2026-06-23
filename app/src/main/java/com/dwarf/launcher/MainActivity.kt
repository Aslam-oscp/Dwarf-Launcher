package com.dwarf.launcher

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {
    private lateinit var btnPlay: Button
    private lateinit var btnReset: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtInstructions: TextView
    
    private val CHANNEL_ID = "dwarf_import_channel"
    private val NOTIFICATION_ID = 1

    private val runtimeUrl = "https://github.com/Aslam-oscp/Dwarf-Launcher/releases/download/v1.0-runtime/dwarf_runtime.zip"

    enum class LauncherState {
        NO_GAME,
        NO_RUNTIME,
        READY
    }

    private val openFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            importGameFile(uri)
        } else {
            Toast.makeText(this, "Selección cancelada", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnPlay = findViewById(R.id.btnPlay)
        btnReset = findViewById(R.id.btnReset)
        txtStatus = findViewById(R.id.txtStatus)
        txtInstructions = findViewById(R.id.txtInstructions)
        
        createNotificationChannel()
        updateUI()

        btnPlay.setOnClickListener {
            when (getLauncherState()) {
                LauncherState.NO_GAME -> checkPermissionAndOpenPicker()
                LauncherState.NO_RUNTIME -> downloadRuntime()
                LauncherState.READY -> startGame()
            }
        }

        // Programamos el botón de reseteo del motor
        btnReset.setOnClickListener {
            btnReset.isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Borramos físicamente Box64 y el sysroot viejo
                    val box64File = File(filesDir, "box64")
                    val sysrootFolder = File(filesDir, "sysroot")
                    
                    if (box64File.exists()) box64File.delete()
                    if (sysrootFolder.exists()) sysrootFolder.deleteRecursively()
                    
                    withContext(Dispatchers.Main) {
                        updateUI()
                        btnReset.isEnabled = true
                        Toast.makeText(this@MainActivity, "Motor de ejecución limpiado. Ya puedes reinstalarlo.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getLauncherState(): LauncherState {
        val gameInstalled = File(filesDir, "df_linux/df").exists()
        val runtimeInstalled = File(filesDir, "box64").exists() && File(filesDir, "sysroot").exists()
        
        return if (!gameInstalled) {
            LauncherState.NO_GAME
        } else if (!runtimeInstalled) {
            LauncherState.NO_RUNTIME
        } else {
            LauncherState.READY
        }
    }

    private fun updateUI() {
        val state = getLauncherState()
        
        // El botón de reseteo solo es visible si ya se ha importado el juego
        if (state == LauncherState.NO_GAME) {
            btnReset.visibility = View.GONE
        } else {
            btnReset.visibility = View.VISIBLE
        }

        when (state) {
            LauncherState.NO_GAME -> {
                txtStatus.text = "Estado: No instalado"
                txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                txtInstructions.text = "1. Descarga Dwarf Fortress para Linux (.tar.bz2 o .zip) desde bay12games.com en tu navegador.\n\n2. Presiona el botón de abajo e impórtalo."
                btnPlay.text = "Importar archivo del juego"
            }
            LauncherState.NO_RUNTIME -> {
                txtStatus.text = "Estado: Falta el Motor de Ejecución"
                txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
                txtInstructions.text = "¡Juego importado con éxito! Ahora necesitamos descargar el motor de traducción de CPU ultraligero (Box64 + Sysroot minimalista) diseñado milimétricamente para Dwarf Fortress."
                btnPlay.text = "Instalar Motor de Ejecución"
            }
            LauncherState.READY -> {
                txtStatus.text = "Estado: ¡Listo para jugar!"
                txtStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                txtInstructions.text = "¡Dwarf Fortress y su motor de traducción están instalados y configurados de forma optimizada en la memoria local segura!"
                btnPlay.text = "Strike the Earth! (Jugar)"
            }
        }
    }

    private fun checkPermissionAndOpenPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
                return
            }
        }
        openFilePicker()
    }

    private fun openFilePicker() {
        openFileLauncher.launch(arrayOf("application/x-bzip2", "application/zip", "application/octet-stream", "*/*"))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            when (getLauncherState()) {
                LauncherState.NO_GAME -> openFilePicker()
                LauncherState.NO_RUNTIME -> downloadRuntime()
                else -> {}
            }
        }
    }

    private fun importGameFile(uri: Uri) {
        btnPlay.isEnabled = false
        btnPlay.text = "Importando..."
        showNotification("Importando Dwarf Fortress", "Copiando archivo localmente...", 0, true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val fileName = getFileName(uri) ?: "df_linux.tar.bz2"
                val isZip = fileName.endsWith(".zip", ignoreCase = true)

                val tempFile = File(cacheDir, "temp_import_archive")
                if (tempFile.exists()) tempFile.delete()

                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw Exception("No se pudo abrir el archivo")
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                withContext(Dispatchers.Main) {
                    btnPlay.text = "Extrayendo... (Espera)"
                }
                showNotification("Extrayendo archivos", "Instalando enanos en la memoria privada...", 0, true)

                val dfFolder = File(filesDir, "df_linux")
                if (dfFolder.exists()) dfFolder.deleteRecursively()

                if (isZip) {
                    extractZip(tempFile, filesDir)
                } else {
                    extractTarBz2(tempFile, filesDir)
                }

                tempFile.delete()
                configureInitTxt()

                File(filesDir, "df_linux/df").setExecutable(true)
                File(filesDir, "df_linux/libs/Dwarf_Fortress").setExecutable(true)

                withContext(Dispatchers.Main) {
                    updateUI()
                    btnPlay.isEnabled = true
                    Toast.makeText(this@MainActivity, "¡Juego importado y configurado!", Toast.LENGTH_SHORT).show()
                }
                completeNotification("¡Dwarf Fortress Listo!", "Importado correctamente.")

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    btnPlay.isEnabled = true
                    updateUI()
                    Toast.makeText(this@MainActivity, "Fallo al extraer: ${e.message}", Toast.LENGTH_LONG).show()
                }
                completeNotification("Fallo de instalación", "Hubo un error al extraer el archivo del juego.")
            }
        }
    }

    private fun downloadRuntime() {
        btnPlay.isEnabled = false
        btnPlay.text = "Descargando Motor..."
        showNotification("Descargando Motor", "Conectando al servidor...", 0, true)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val runtimeZip = File(filesDir, "dwarf_runtime.zip")
                val url = URL(runtimeUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 15000
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("El servidor respondió con código: ${connection.responseCode}")
                }

                val fileLength = connection.contentLength

                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(runtimeZip).use { output ->
                        val data = ByteArray(8192)
                        var total: Long = 0
                        var count: Int
                        var lastProgress = 0
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            output.write(data, 0, count)
                            if (fileLength > 0) {
                                val progress = (total * 100 / fileLength).toInt()
                                if (progress >= lastProgress + 2) {
                                    showNotification("Descargando Motor", "$progress%", progress, false)
                                    lastProgress = progress
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) { btnPlay.text = "Instalando Motor..." }
                showNotification("Configurando Motor", "Instalando traductor dinámico y sysroot...", 0, true)

                extractZip(runtimeZip, filesDir)
                runtimeZip.delete()

                File(filesDir, "box64").setExecutable(true)

                withContext(Dispatchers.Main) {
                    updateUI()
                    btnPlay.isEnabled = true
                    Toast.makeText(this@MainActivity, "¡Motor Box64 instalado con éxito!", Toast.LENGTH_SHORT).show()
                }
                completeNotification("¡Motor Configurado!", "El motor nativo ultraligero está listo.")

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    btnPlay.isEnabled = true
                    updateUI()
                    Toast.makeText(this@MainActivity, "Fallo de motor: ${e.message}", Toast.LENGTH_LONG).show()
                }
                completeNotification("Fallo del Motor", "No se pudo descargar o instalar Box64.")
            }
        }
    }

    private fun configureInitTxt() {
        val initFile = File(filesDir, "df_linux/data/init/init.txt")
        if (initFile.exists()) {
            try {
                var content = initFile.readText()
                content = content.replace(Regex("\\[PRINT_MODE:[^\\]]+\\]"), "[PRINT_MODE:TEXT]")
                content = content.replace(Regex("\\[SOUND:[^\\]]+\\]"), "[SOUND:OFF]")
                content = content.replace(Regex("\\[INTRO:[^\\]]+\\]"), "[INTRO:OFF]")
                initFile.writeText(content)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun extractTarBz2(archiveFile: File, destDir: File) {
        FileInputStream(archiveFile).use { fileIn ->
            BufferedInputStream(fileIn).use { bufIn ->
                BZip2CompressorInputStream(bufIn).use { bzIn ->
                    TarArchiveInputStream(bzIn).use { tarIn ->
                        var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            val destFile = File(destDir, entry.name)
                            if (entry.isDirectory) {
                                destFile.mkdirs()
                            } else {
                                destFile.parentFile?.mkdirs()
                                FileOutputStream(destFile).use { out ->
                                    val buffer = ByteArray(8192)
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                                if ((entry.mode and 73) != 0) {
                                    destFile.setExecutable(true)
                                }
                            }
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        }
    }

    private fun extractZip(archiveFile: File, destDir: File) {
        FileInputStream(archiveFile).use { fileIn ->
            BufferedInputStream(fileIn).use { bufIn ->
                ZipInputStream(bufIn).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val destFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            destFile.mkdirs()
                        } else {
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                val buffer = ByteArray(8192)
                                var len: Int
                                while (zipIn.read(buffer).also { len = it } != -1) {
                                    out.write(buffer, 0, len)
                                }
                            }
                            if (destFile.name == "df" || destFile.name == "Dwarf_Fortress" || destFile.name == "box64") {
                                destFile.setExecutable(true)
                            }
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            val path = uri.path
            if (path != null) {
                val cut = path.lastIndexOf('/')
                result = if (cut != -1) {
                    path.substring(cut + 1)
                } else {
                    path
                }
            }
        }
        return result
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Importador del Juego"
            val descriptionText = "Muestra el progreso de descompresión de Dwarf Fortress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, text: String, progress: Int = 0, indeterminate: Boolean = true) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (indeterminate || progress > 0) {
            builder.setProgress(100, progress, indeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun completeNotification(title: String, text: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun startGame() {
        val intent = Intent(this, GameActivity::class.java)
        startActivity(intent)
    }
}
