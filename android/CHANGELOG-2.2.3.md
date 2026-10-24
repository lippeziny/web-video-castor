# Castor Android 2.2.3

## Correções e melhorias
- Busca DLNA mais compatível com Samsung: o app espera a transição de transporte, pausa antes do seek, consulta as ações disponíveis, tenta `REL_TIME` e `ABS_TIME` e faz novas tentativas controladas antes de reportar falha.
- A mensagem de erro de busca agora inclui o estado e as ações de transporte que a TV anunciou, para distinguir falha do comando de uma limitação do renderer.
- O proxy DLNA anuncia vídeo sob demanda como `Interactive`, preserva `Range`/`Content-Range` e respeita requisições `HEAD`, sem anunciar suporte HTTP de seek por tempo que ele não implementa.
- Melhorada a retomada ao transmitir: se a posição passada pelo player for zero, o Castor tenta recuperar o checkpoint da página e, em seguida, do URL direto; a duração salva também é preservada.
- O player local consulta o checkpoint do URL direto quando não existe um registro para a URL da página.
- O controle de reprodução no celular usa uma interface de vidro inspirada no REX Player e, durante o cast, vira um controle remoto da TV sem manter um segundo decoder ativo no celular.
- Notificação persistente da transmissão com controles de pausa, busca de ±10 s e parar; travas de CPU/Wi-Fi mantêm o serviço ativo durante a tela apagada.
- As teclas físicas de volume são encaminhadas para a TV por uma sessão de mídia remota quando não há áudio local ativo e o dispositivo de cast oferece controle de volume.

## Testes executados
- Harness Java de DLNA: validou pausa, espera pelo estado, negociação de seek, retry após erro 701 e retomada para PLAYING.
- Harness Java do proxy: validou resposta `206`, bytes de Range, `Content-Range`, `Interactive`, flags DLNA e encaminhamento correto de `HEAD`.
- Checagem estrutural de 15 arquivos Java: sem erros de sintaxe.

## Limitações conhecidas
- O player continua usando Media3 como motor de reprodução. O REX Player original usa libmpv com integração nativa e UI Jetpack Compose; este ZIP traz uma interface e controles inspirados nele, não uma cópia do motor libmpv. A integração real do libmpv exige bibliotecas nativas por ABI e build Android completo.
- O cast DLNA é decodificado pela TV. Mesmo com o seek negociado de modo mais compatível, um firmware que não ofereça `Seek` para aquele recurso poderá continuar recusando a busca; isso só pode ser confirmado em um teste real nessa Samsung.
- Não foi possível compilar um APK aqui: este ambiente não contém Gradle/Android SDK, e não há uma TV física conectada para teste fim a fim.
