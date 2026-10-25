# Castor Android 3.0.0 — redesign completo da interface

## Visual
- Novo sistema de design: modos **Sistema, Claro, Escuro e AMOLED** (preto puro) combinados com **9 cores de destaque** fortes e foscas (Vermelho, Laranja, Verde, Ciano, Azul, Roxo, Rosa, Pêssego e **Dinâmica**, que usa a cor do Android 12+). O tema "Sistema" acompanha o modo escuro do aparelho sem reabrir o app.
- Barra de endereço em pílula com cadeado/globo, **estrela de favoritos**, contador de abas, botão de cast e menu.
- Barra inferior flutuante em pílula (Navegar, Vídeos, Biblioteca, Ajustes) com item ativo destacado e contador de vídeos.
- **Botão flutuante** "N vídeos" sobre a página quando algum vídeo é detectado, e faixa "Transmitindo para…" enquanto há cast ativo.
- Abas, menu de proteção, opções do vídeo e escolhas viram **painéis deslizantes** (bottom sheets).
- Cartões de vídeo com chips de tipo, qualidade e idioma e ações em destaque (Reproduzir, Fila, Cast, Mais).
- Página inicial nova (busca, favoritos, serviços, dicas) que segue o tema escolhido.

## Configurações
- Tela cheia de configurações com **busca**, cartão-resumo e páginas: Aparência (com prévia ao vivo), Navegador, Bloqueio de anúncios, Reprodução, Transmissão, Dados e privacidade e Sobre.
- Opções novas: cantos arredondados, rótulos da barra inferior, mecanismo de busca (Google, DuckDuckGo, Bing, Brave, Ecosia, Startpage) e zoom do texto.

## Biblioteca
- Fila, **Favoritos** (novo) e Histórico em abas.

## Observações
- `versionCode` 10 / `versionName` 3.0.0.
- O código foi escrito sem compilar com o Android SDK; se o GitHub Actions acusar erro, envie o log.
