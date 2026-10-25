# Correções aplicadas ao Castor

## Erro de compilação no Android

Os logs enviados mostravam falha em `PlaybackService.java` nas linhas que acrescentam os cabeçalhos HTTP `Referer` e `Origin`. O código usava `httpRequestProperties` e `setHttpRequestProperties`, que não são os nomes da API `DataSpec` do Media3. Foram corrigidos para `httpRequestHeaders` e `setHttpRequestHeaders`, mantendo os cabeçalhos por mídia.

## Avanço/retrocesso em Chromecast e DLNA

- A barra e os botões de +/- 10 s atualizam a posição visual imediatamente, enquanto o comando para a TV é processado na fila serial.
- A busca agora usa uma posição-base estimada para que toques rápidos acumulem em vez de partirem de uma posição antiga.
- A consulta periódica de posição é suspensa enquanto há buscas pendentes, evitando que um status concorrente anule visualmente o salto.
- No Chromecast, um `GET_STATUS` que chegue imediatamente com a posição anterior ao `SEEK` não substitui o alvo solicitado durante a janela de sincronização.
- O serviço de volume remoto foi preservado: as teclas de volume podem controlar a TV pela sessão de mídia em segundo plano/tela apagada; o controle é liberado quando o Castor está reproduzindo mídia localmente ou o Android detecta outra mídia ativa no telefone.

## Limitações de validação

O ZIP de logs contém falha de compilação, mas não inclui um erro de execução do seek. Não foi possível gerar um APK neste ambiente porque não há Gradle nem Android SDK instalados. O projeto foi revisado e o ZIP foi validado, mas a busca deve ser confirmada em execução na TV e no fluxo de vídeo afetado. Algumas TVs/servidores DLNA ou fluxos sem suporte a seek/range ainda podem recusar a busca por limitação do dispositivo ou da origem.
