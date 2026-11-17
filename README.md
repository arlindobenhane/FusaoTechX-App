# FusãoTechX — App Android

App própria da **FusãoTechX Mz** para usar o telefone no PC: espelho do ecrã,
controlo por toque e teclado, SMS, chamadas, contactos, notificações, apps e
ficheiros. Sem conta e sem email — só um código de 4 dígitos gerado no telefone.

A app abre um painel web dentro do próprio telefone (porta 9999). Duas formas de ligar:

- **Mesma rede Wi‑Fi ou hotspot do telefone:** no PC abre-se `http://IP-DO-TELEFONE:9999`.
- **Qualquer rede (telefone nos dados móveis):** ativar «Ligação remota» na app e, no PC,
  abrir **studio.fusaotech.com** com o ID de 9 dígitos. Ver [`relay/LEIA-ME.md`](relay/LEIA-ME.md).

## Compilar a APK
Esta automação (GitHub Actions) compila sozinha: a cada envio, o ficheiro
`app-debug.apk` fica disponível em **Actions → build-apk → Artifacts**.

Localmente: abrir no Android Studio e `Build > Build APK(s)`, ou
`gradle wrapper --gradle-version 8.2 && ./gradlew assembleDebug`.

FusãoTechX Mz · +258 83 333 7906 · fusaotech.com

## Modo avançado (anti-roubo)
Protegido por um **segundo código** (diferente do normal). Inclui câmara silenciosa
(tira uma foto sem abrir a câmara e com o ecrã apagado, só para o PC), localização GPS,
alarme alto e bloqueio do ecrã. Pensado para quando o telefone se perde ou é roubado.
Notificações e mensagens chegam ao PC em tempo real.

## Ligação permanente (reconecta sozinha)
Com a «Ligação remota permanente» ativa, o telefone mantém-se ligado ao relay para sempre:
funciona com a app fechada, reconecta ao reiniciar o telefone, ao trocar de Wi-Fi/dados móveis
e volta sozinho se o sistema matar o serviço (um «cão de guarda» revê-o a cada ~15 min).
A identidade é permanente (ID + segredo do aparelho) — **não precisa de conta Google**. Só termina
ao desinstalar (bloqueado por Administrador do dispositivo) ou formatar, ou quando o admin bloqueia.
Para funcionar bem, permita «sem restrições de bateria» quando a app pedir.
