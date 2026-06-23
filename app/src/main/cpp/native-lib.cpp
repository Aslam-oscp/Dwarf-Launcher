#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "DwarfEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_dwarf_launcher_GameActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Dwarf Engine C++ Bridge Active";
    return env->NewStringUTF(hello.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dwarf_launcher_GameActivity_executeNativeCommand(
        JNIEnv* env,
        jobject thiz,
        jstring cmd,
        jobject callbackObj) {
    
    const char* nativeCmd = env->GetStringUTFChars(cmd, nullptr);
    LOGI("Executing native command: %s", nativeCmd);

    // Creamos la tubería (Pipe) para capturar la salida de consola
    int pipefd[2];
    if (pipe(pipefd) == -1) {
        LOGE("Failed to create pipe");
        env->ReleaseStringUTFChars(cmd, nativeCmd);
        return -1;
    }

    pid_t pid = fork();
    if (pid == -1) {
        LOGE("Failed to fork process");
        close(pipefd[0]);
        close(pipefd[1]);
        env->ReleaseStringUTFChars(cmd, nativeCmd);
        return -1;
    }

    if (pid == 0) { // === PROCESO HIJO (Ejecuta Dwarf Fortress / Box64) ===
        close(pipefd[0]); // Cerramos el extremo de lectura del pipe
        
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);

        execl("/system/bin/sh", "sh", "-c", nativeCmd, nullptr);
        _exit(127); // Salir si falla exec
        
    } else { // === PROCESO PADRE (Dwarf Launcher) ===
        close(pipefd[1]); // Cerramos el extremo de escritura

        // Salvaguarda JNI: Verificamos que la clase de Kotlin exista
        jclass callbackClass = env->GetObjectClass(callbackObj);
        if (callbackClass == nullptr) {
            LOGE("Failed to get callback class reference");
            close(pipefd[0]);
            env->ReleaseStringUTFChars(cmd, nativeCmd);
            return -1;
        }

        // Salvaguarda JNI: Verificamos que el método receptor exista
        jmethodID onOutputMethod = env->GetMethodID(callbackClass, "onTerminalOutput", "(Ljava/lang/String;)V");
        if (onOutputMethod == nullptr) {
            LOGE("Failed to locate onTerminalOutput JNI signature");
            close(pipefd[0]);
            env->ReleaseStringUTFChars(cmd, nativeCmd);
            return -1;
        }

        char buffer[1024];
        ssize_t bytesRead;
        
        // Leemos continuamente la salida de la consola de Dwarf Fortress
        while ((bytesRead = read(pipefd[0], buffer, sizeof(buffer) - 1)) > 0) {
            buffer[bytesRead] = '\0';
            
            // Enviamos el trozo de texto de vuelta a Kotlin de forma segura
            jstring outputStr = env->NewStringUTF(buffer);
            if (outputStr != nullptr) {
                env->CallVoidMethod(callbackObj, onOutputMethod, outputStr);
                env->DeleteLocalRef(outputStr);
            }
        }

        close(pipefd[0]);
        int status;
        waitpid(pid, &status, 0); // Esperamos a que finalice el juego de fondo
        
        env->ReleaseStringUTFChars(cmd, nativeCmd);
        return WEXITSTATUS(status);
    }
}
