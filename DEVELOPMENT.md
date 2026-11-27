# Guía de Desarrollo y Compilación — PDG3Android

Documentación técnica sobre la estructura del repositorio, compilación del motor nativo ARM64 (`libdod3.so`), firma de variantes **Debug** / **Release** y organización para GitHub.

---

## Estructura del repositorio

```text
PDG3Android/
├── README.md                          ← Documentación principal en español
├── README.en.md                       ← Documentación en inglés
├── DEVELOPMENT.md                     ← Guía técnica de compilación
├── .gitignore                         ← Excluye datos locales, APKs, llaves de firma y compilaciones
├── assets/                            ← Recursos visuales del proyecto (ícono, banners y logotipo)
├── docs/screenshots/                  ← Capturas verificadas del launcher y partida
├── releases/                          ← Carpeta local para APKs compilados (ignorada por Git)
├── _local_data/                       ← Archivos locales pesados del usuario (ISO, DLCs, Update, mods; ignorado)
└── dod3-recomp-android/               ← Código fuente del port y proyecto Android
    ├── CMakeLists.txt                 ← Configuración CMake del núcleo recompilado ARM64 / Vulkan
    ├── src/                           ← Parches UE3, VFS Android, audio MP3, cheats y runner SDL2
    ├── ps3recomp/                     ← Runtime HLE LV2, SPURS, RSX Vulkan y utilidades
    ├── recompiled_101/                ← Funciones PPU recompiladas estáticamente (Update 1.01)
    ├── shaders/                       ← Shaders SPIR-V/HLSL del backend gráfico
    ├── branding/                      ← Activos de marca del port Android
    └── android/                       ← Proyecto Gradle Android
        ├── build.gradle
        ├── keystore.properties        ← Configuración local de firma Release (ignorada por Git)
        └── app/
            ├── build.gradle           ← Configuración de versión, SDK y firma Debug/Release
            └── src/main/java/com/pdg3android/xgowther2102/
                ├── LauncherActivity.java   ← Launcher, asistente en 4 pasos, perfiles, mods y visor de errores
                ├── Dod3Activity.java       ← Actividad SDL2 en proceso `:game`, HUD y retorno al launcher
                ├── CrashReporter.java      ← Captura de fallos Java/nativos y exportación a `.txt`
                ├── VirtualPadView.java     ← Controles táctiles por plantilla
                ├── ControllerPreviewView.java
                └── IniStore.java           ← Lectura/escritura atómica de `dod3.ini`
```

---

## Requisitos de compilación

* **JDK 17** o superior
* **Android SDK** (`compileSdk 34`, `minSdk 26`, `targetSdk 34`, `CMake 3.22.1`)
* **Android NDK** (`27.x` / `28.x` para `arm64-v8a`) y **Ninja**
* **Python 3**

La ruta del SDK local se define en `dod3-recomp-android/android/local.properties` (por ejemplo: `sdk.dir=C:\\Android`).

---

## Compilación de `libdod3.so` y del APK

### 1. Biblioteca nativa (`libdod3.so`)
Se compila en `dod3-recomp-android/build-android/` con el toolchain CMake de Android para `arm64-v8a` y se coloca en `dod3-recomp-android/android/app/src/main/jniLibs/arm64-v8a/libdod3.so` tras aplicar `llvm-strip`.

### 2. Variante **Debug**
```powershell
cd dod3-recomp-android\android
.\gradlew.bat assembleDebug
# Salida: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Variante **Release** firmada
Para que `assembleRelease` genere un APK firmado listo para distribución, configurar `dod3-recomp-android/android/keystore.properties` (ignorado por Git):

```properties
storeFile=../../../_keys/pdg3android-release.jks
storePassword=PASSWORD_KEYSTORE
keyAlias=pdg3android
keyPassword=PASSWORD_KEY
```

También es posible suministrar las credenciales mediante variables de entorno (`PDG3_STORE_FILE`, `PDG3_STORE_PASSWORD`, `PDG3_KEY_ALIAS` y `PDG3_KEY_PASSWORD`):

```powershell
cd dod3-recomp-android\android
.\gradlew.bat assembleRelease
# Salida: app/build/outputs/apk/release/app-release.apk
```

Para validar la firma criptográfica del binario generado:

```powershell
apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
```

---

## Convenciones para GitHub

* Nunca incluir archivos `.iso`, `.pkg`, `.rap`, `.edat`, `libdod3.so` compilado ni llaves `.jks` / `keystore.properties` dentro de commits.
* Publicar los `.apk` firmados (como `PDG3Android-v0.6.1-release.apk`) como adjuntos en la sección **GitHub Releases** de cada versión etiquetada.
