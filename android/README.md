# Castor Android Browser 2.0

Navegador Android (Java + WebView + Media3/ExoPlayer) com detecção de vídeos, fila de reprodução e bloqueio de anúncios, pop-ups e redirecionamentos.

## Interface (baseada no desenho)

- **Barra superior:** endereço/pesquisa · contador de abas (abre o seletor de abas) · engrenagem (painel *Block ADS*).
- **Barra inferior:** ☰ histórico + fila · 🌐 navegador · ▶ vídeos encontrados no site (com contador).
- **Painel Block ADS:** chaves *Popup*, *Redirect* e *Ads* (todas ligadas por padrão), botão **Force search** (força a busca de vídeos na página), modo desktop e engrenagem de **mais configurações**.
- **Página inicial** com atalhos de serviços (Google, YouTube, Prime Video etc.).
- **Aviso de bloqueio:** mostra o link com **Abrir** / **Ignorar**; some em 3 s (configurável de 0 a 15 s; 0 = fica até escolher).

## Como compilar no GitHub

1. Copie `android/`, `.github/workflows/build-android-apk.yml` e este arquivo para a raiz do repositório e faça commit.
2. Vá em **Actions → Build Castor Android APK → Run workflow**.
3. Ao terminar, baixe o artefato `castor-android-debug-apk` e instale o `castor-android-debug.apk`.

Requisitos do build: JDK 17, Gradle 8.13, Android SDK 36 (o workflow já configura tudo).

## Como funciona

- **Detecção de vídeo:** intercepta as requisições da WebView e varre a página (tags video/source, links, meta og:video, scripts, iframes do mesmo domínio). Reconhece MP4, HLS (m3u8), DASH (mpd), WebM, MKV, MOV, FLV e áudio.
- **Force search:** varre a página de novo, tenta iniciar os players em mudo para revelar a fonte e lista players embutidos (iframes de outro domínio) para você abrir.
- **Player interno:** ExoPlayer com fila, próximo/anterior, envia Referer da página original e entra em picture-in-picture.
- **Bloqueio:** domínios de anúncios/rastreadores (lista embutida + regras `||dominio^` do EasyList/EasyPrivacy baixadas na primeira abertura e atualizadas a cada 24 h), CSS cosmético, bloqueio de `window.open`/pop-ups, de redirecionamentos sem toque do usuário para outro site e de abertura automática de apps.

## Limitações conhecidas

- Não contorna DRM. Sites como Prime Video/Netflix e vídeos `blob:` sem link direto não podem ser extraídos.
- O servidor Go original do Castor (api/media/scraping, Chromium e FFmpeg) **não** roda dentro do app; transmissão para dispositivos (cast) não está incluída.
- Gera APK de depuração (sem assinatura de release).
- O código foi escrito sem compilar neste ambiente: se o build do GitHub acusar erro, envie o log para correção.
