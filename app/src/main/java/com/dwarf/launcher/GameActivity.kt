package com.dwarf.launcher

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class GameActivity : AppCompatActivity() {

    private lateinit var txtTerminal: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        
        txtTerminal = findViewById(R.id.txtTerminal)
        
        CoroutineScope(Dispatchers.IO).launch {
            startDwarfEngine()
        }
    }

    private fun startDwarfEngine() {
        val box64Exec = File(filesDir, "box64").absolutePath
        val sysrootLib = File(filesDir, "sysroot/lib/x86_64-linux-gnu").absolutePath
        val dfFolder = File(filesDir, "df_linux")
        val dfLibs = File(dfFolder, "libs").absolutePath
        
        if (!File(box64Exec).exists() || !File(sysrootLib).exists()) {
            writeToTerminal("\n[ERROR] ¡Falta el motor de traducción Box64 o el sysroot!\n")
            return
        }

        // Forzamos los permisos nativos a nivel de terminal por seguridad antes de arrancar
        try {
            Runtime.getRuntime().exec("chmod 755 $box64Exec").waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        writeToTerminal("\n[Launcher] Configurando entorno de ejecución milimétrico...\n")

        try {
            val processBuilder = ProcessBuilder()
            
            val env = processBuilder.environment()
            env["BOX64_LD_LIBRARY_PATH"] = "$sysrootLib:$dfLibs"
            env["BOX64_PATH"] = filesDir.absolutePath
            env["BOX64_DYNAREC"] = "1"  // Compilación en caliente nativa (Alto rendimiento)
            env["BOX64_LOG"] = "1"      // Logs detallados de traducción para verlos en pantalla
            env["PATH"] = "/system/bin"

            processBuilder.directory(dfFolder)
            processBuilder.command(box64Exec, "./libs/Dwarf_Fortress")
            processBuilder.redirectErrorStream(true)

            writeToTerminal("[Launcher] Ejecutando: $box64Exec ./libs/Dwarf_Fortress\n\n")

            val process = processBuilder.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val charBuffer = CharArray(1024)
            var bytesRead = reader.read(charBuffer)
            
            while (bytesRead != -1) {
                val output = String(charBuffer, 0, bytesRead)
                writeToTerminal(output)
                bytesRead = reader.read(charBuffer)
            }

            val exitCode = process.waitFor()
            writeToTerminal("\n[System] Dwarf Fortress finalizó con código de salida: $exitCode\n")

        } catch (e: Exception) {
            e.printStackTrace()
            writeToTerminal("\n[ERROR] Excepción crítica al iniciar el motor: ${e.message}\n")
        }
    }

    private fun writeToTerminal(text: String) {
        runOnUiThread {
            txtTerminal.append(text)
            val scrollView = txtTerminal.parent as? ScrollView
            scrollView?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
