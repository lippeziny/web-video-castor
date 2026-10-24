# Castor Android

Versão do app: **2.2.4**. Consulte `CHANGELOG-2.2.4.md` para a correção de compilação. Os testes de protocolo da versão 2.2.3 permanecem descritos em `CHANGELOG-2.2.3.md`.

# Castor Android Browser 2.2

Navegador Android (Java + WebView + Media3/ExoPlayer) com detecção de vídeos, fila de reprodução e bloqueio de anúncios, pop-ups e redirecionamentos.

## Interface (baseada no desenho)

- **Barra superior:** endereço/pesquisa · contador de abas (abre o seletor de abas) · engrenagem (painel *Block ADS*).
- **Barra inferior:** ☰ histórico + fila · 🌐 navegador · ▶ vídeos encontrados no site (com contador).
- **Painel Block ADS:** chaves *Popup*, *Redirect* e *Ads* (todas ligadas por padrão), botão **Force search** (força a busca de vídeos na página), modo desktop e engrenagem de **mais configurações**.
- **Página inicial** com atalhos de serviços (Google, YouTube, Prime Video etc.).
- **Aviso de bloqueio:** mostra o link com **Abrir** / **Ignorar**; some em 3 s (configurável de 0 a 15 s; 0 = fica até escolher).

## Novidades da 2.2 (v4)

- **Player em segundo plano:** a reprodução interna passou para `MediaSessionService` (Media3), com sessão de mídia, notificação/controlos do sistema e suporte a continuar com a tela apagada. A compatibilidade final depende do fabricante e das configurações de bateria do Android.
- **Qualidade e idioma:** a lista de vídeos mostra a resolução quando o elemento de vídeo informa `videoWidth`/`videoHeight` ou quando a resolução é identificável na URL. Idioma de áudio/legenda aparece quando o navegador expõe esses metadados; em outros casos, o app informa que a fonte não disponibilizou a informação.
- **Reprodução automática opcional:** em Configurações, pode ativar a reprodução automática quando uma fonte de vídeo é detectada. Por padrão, ela fica desligada. A preferência de qualidade permite tentar a maior disponível, limitar a média (até 720p) ou baixa (até 480p) em fontes adaptativas. Arquivos de qualidade fixa não podem ser convertidos magicamente para outra resolução.
- **Áudio e cast:** o player define atributos de áudio e mantém os cabeçalhos `Referer`/`Origin` por item. O cast envia o MIME correspondente e o áudio que já faz parte do arquivo/stream; isso não converte formatos nem remove DRM. A compatibilidade depende dos codecs suportados pela TV/receptor.
- **Aparência:** tema Claro, Escuro e AMOLED de preto puro; a seleção fica salva.
- **Volume da TV:** os botões físicos de volume durante um cast ativo e o controle deslizante na tela do cast ajustam DLNA/UPnP RenderingControl ou Chromecast quando disponível. Dispositivos/servidores que não oferecem esse controle não conseguem ser ajustados pelo app.
- **Ícone por imagem:** em Configurações pode escolher uma imagem e pedir ao Android para fixar um atalho Castor com esse ícone na tela inicial. Isso não substitui o ícone do pacote instalado, pois essa troca não é suportada de modo universal pelos launchers.
- **Proteção durante cast:** o serviço de cast mantém `WakeLock` parcial e `WifiLock` enquanto está ativo para reduzir interrupções com a tela apagada. Alguns fabricantes ainda podem encerrar processos por economia de bateria.

## Novidades da 2.1

- **Cast nativo (sem Google SDK):** descobre TVs **DLNA/UPnP** (SSDP) e **Chromecast** (mDNS + protocolo CASTV2) na rede Wi-Fi. Botão **Cast** em cada vídeo achado, no menu ⋯ e dentro do player; há também **Adicionar por IP** quando a descoberta não funciona. Depois de enviar, abre um controle com play/pause, ±10 s, barra de posição e parar.
- **Proxy local do app:** a TV não manda Referer, cookies nem User-Agent, então o app serve o vídeo para ela (reescrevendo playlists HLS). Ligado por padrão em *Configurações → Transmissão*; um serviço em primeiro plano mantém o app vivo durante a transmissão.
- **Servidor Castor remoto (opcional):** em *Configurações → Transmissão* informe a URL e o token do `castor api-server` (porta 8411 por padrão) que roda num PC/NAS/Docker da mesma rede. As TVs dele aparecem na lista como “via servidor Castor”; para players embutidos o servidor faz a extração, a conversão com ffmpeg e a queima de legendas.
- **Continuar de onde parou:** a posição é salva pelo **endereço da página** (sem fragmento, `www.` e parâmetros de rastreio), então vale mesmo que o link do vídeo mude. Só salva vídeos com mais de 1 min, não ao vivo, e apaga ao chegar no fim. Vale para o player interno e para o cast.
- **Abas salvas:** as abas abertas voltam ao reabrir o app (a aba ativa carrega na hora; as outras só quando você abrir).

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
- O servidor Go original do Castor (api/media/scraping) **não** roda dentro do app: ele depende de Chrome e ffmpeg, que não existem no Android. Use a opção de servidor remoto acima.
- Cast: o app envia o link do vídeo (via proxy) e a TV decodifica. Formatos que a TV não suporta (ex.: MKV/HEVC em algumas) não são convertidos no modo nativo; só o servidor remoto converte. O cast, áudio e controle de volume precisam ser testados com a TV/receptor real; este pacote não foi validado em hardware real.
- A descoberta de TVs não atravessa VLANs/sub-redes; celular e TV precisam estar na mesma rede.
- Gera APK de depuração (sem assinatura de release).
- Neste ambiente de edição não havia Android SDK nem Gradle disponíveis, e não foi possível baixá-los. Portanto, o APK não foi compilado aqui; execute o workflow do GitHub para validar o build. Também é necessário testar com uma TV real para confirmar os protocolos, o áudio e o volume suportados pelo seu modelo.

### Tela apagada e comandos de busca

A transmissão nativa mantém um serviço em primeiro plano com locks de CPU e Wi-Fi enquanto está ativa. Em aparelhos com economia de bateria agressiva, abra **Castor → Configurações → Transmissão (cast) → Configurar bateria para tela apagada** e defina o Castor como **Sem restrições** (os nomes variam por fabricante). O Android ainda pode impor Doze/restrições próprias; por isso, a continuidade por longos períodos depende também do fabricante e da rede.

Os controles do player interno usam retroceder/avançar em intervalos de 10 segundos. No Chromecast, o aplicativo aguarda a identificação da sessão de mídia antes de enviar comandos de busca e tenta reconectar a sessão se o socket cair. O suporte de busca no DLNA depende de a TV aceitar `Seek` para o tipo de mídia em reprodução (algumas TVs não permitem buscar em streams ao vivo ou certos formatos).

