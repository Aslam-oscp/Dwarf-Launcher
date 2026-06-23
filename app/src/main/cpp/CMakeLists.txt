cmake_minimum_required(VERSION 3.22.1)

project("dwarf_engine")

# Compila nuestro archivo C++ como una librería compartida (.so)
add_library(
    dwarf_engine
    SHARED
    native-lib.cpp
)

# Busca la librería de logs nativos de Android para poder depurar fallos de Box64 en el futuro
find_library(
    log-lib
    log
)

# Enlaza las librerías
target_link_libraries(
    dwarf_engine
    ${log-lib}
)
