# FusãoTechX — App Android

App própria da **FusãoTechX Mz** para usar o telefone no PC: espelho do ecrã,
controlo por toque e teclado, SMS, chamadas, contactos, notificações, apps e
ficheiros. Sem conta e sem email — só um código de 4 dígitos gerado no telefone.

A app abre um painel web dentro do próprio telefone (porta 9999). Duas formas de ligar:

- **Mesma rede Wi‑Fi ou hotspot do telefone:** no PC abre-se `http://IP-DO-TELEFONE:9999`.
- **Qualquer rede (telefone nos dados móveis):** ativar «Ligação remota» na app e, no PC,
  abrir **fusaotech.com/relay** com o ID de 9 dígitos. Ver [`relay/LEIA-ME.md`](relay/LEIA-ME.md).

## Compilar a APK
Esta automação (GitHub Actions) compila sozinha: a cada envio, o ficheiro
`app-debug.apk` fica disponível em **Actions → build-apk → Artifacts**.

Localmente: abrir no Android Studio e `Build > Build APK(s)`, ou
`gradle wrapper --gradle-version 8.2 && ./gradlew assembleDebug`.

FusãoTechX Mz · +258 83 333 7906 · fusaotech.com
