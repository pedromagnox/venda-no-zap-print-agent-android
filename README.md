# Venda no Zap Print Agent — Android

Agente de impressão térmica pra Android: o celular do lojista (ideal: um aparelho
dedicado, plugado na tomada do balcão) recebe os pedidos da fila e imprime na
térmica **Bluetooth (SPP)** ou **de rede (TCP 9100)**.

É um membro da mesma frota do Print Agent desktop: usa o MESMO pareamento
(`vnzpa_…` gerado no painel), a MESMA fila (`/api/print-queue/*` com
lease/ack/release) e a MESMA telemetria. Os cupons chegam prontos do servidor
(ESC/POS em base64, CP858 — acentos resolvidos server-side); o app só
transporta bytes até a impressora.

## Arquitetura (sem polling!)

Não existe NENHUM timer neste app. A fila é drenada apenas em **eventos**:

- **FCM data message** (campainha primária — exige projeto Firebase + sender no backend)
- Boot do aparelho / update do APK (`BootReceiver`)
- Rede restaurada (callback de conectividade, só em transição offline→online)
- App aberto / botão "Sincronizar agora"

Um foreground service (`connectedDevice`) mantém o processo vivo; o onboarding
pede isenção de otimização de bateria.

## Build

Requisitos: JDK 17, Android SDK (`local.properties` → `sdk.dir`).

```powershell
$env:GRADLE_USER_HOME = "C:\Android\gradle-home"   # cache fora do OneDrive
.\gradlew.bat assembleDebug                         # APK de teste
.\gradlew.bat assembleRelease                       # exige keystore (ver abaixo)
```

APK em `app/build/outputs/apk/`.

## Pendências pra produção

1. **Firebase**: criar projeto no console, registrar `app.vendanozap.printagent`,
   baixar `google-services.json` pra `app/` (gitignored). Sem ele o app builda
   e funciona, mas sem a campainha FCM.
2. **Backend**: endpoint `POST /api/print-agent/fcm-token` (registro do token,
   o app já chama best-effort) + disparo FCM no enqueue do print-queue.
3. **Keystore de release**: gerar, guardar como secret de produção (perder =
   ninguém atualiza o app), configurar `signingConfigs` ou assinar no CI.
4. **Android Developer Console**: registrar identidade + package name +
   SHA-256 da keystore **antes de setembro/2026** (exigência de developer
   verification pra sideload no Brasil).
5. **Distribuição**: GitHub Releases + página `/download/android` (mesmo padrão
   do agente Windows) + in-app updater (não implementado nesta versão).

## Teste físico

Parear a térmica no Bluetooth do Android → abrir o app → colar o código
`vnzpa_…` → escolher a impressora → o teste imprime cupom com acentos
(ÁÉÍÓÚ ãõ ç) — se sair certo, a codepage CP858 da impressora está ok.
