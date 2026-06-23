package com.dwarf.launcher

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class GameActivity : AppCompatActivity() {

    init {
        System.loadLibrary("native-lib")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Lanzamos el motor optimizado
        CoroutineScope(Dispatchers.IO).launch {
            startDwarfEngine()
        }
    }

    private fun startDwarfEngine() {
        val box64Exec = File(filesDir, "box64").absolutePath
        val sysrootLib = File(filesDir, "sysroot/lib/x86_64-linux-gnu").absolutePath
        val dfFolder = File(filesDir, "df_linux")
        val dfLibs = File(dfFolder, "libs").absolutePath
        
        // Ejecución milimétrica y optimizada de Box64 sin PRoot:
        // - BOX64_DYNAREC=1: Activa la compilación en caliente para rendimiento masivo de CPU.
        // - BOX64_LD_LIBRARY_PATH: Apunta a nuestras librerías x86_64 necesarias sin buscar en Android.
        // - BOX64_LOG=1: Muestra logs detallados de emulación en consola.
        val command = "cd ${dfFolder.absolutePath} && " +
                      "export BOX64_LD_LIBRARY_PATH=$sysrootLib:$dfLibs && " +
                      "export BOX64_PATH=${filesDir.absolutePath} && " +
                      "export BOX64_DYNAREC=1 && " +
                      "export BOX64_LOG=1 && " +
                      "$box64Exec ./libs/Dwarf_Fortress"
        
        Log.i("DwarfLauncher", "Iniciando motor de alta eficiencia: $command")
        
        val exitCode = executeNativeCommand(command, this)
        
        Log.i("DwarfLauncher", "Dwarf Fortress finalizó con código de salida: $exitCode")
    }

    // Recibe los caracteres del juego en tiempo real
    fun onTerminalOutput(text: String) {
        Log.d("DwarfTerminal", text)
    }

    external fun stringFromJNI(): String
    external fun executeNativeCommand(cmd: String, callback: Any): Int
}
